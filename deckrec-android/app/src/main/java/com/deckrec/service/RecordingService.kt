package com.deckrec.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.deckrec.DeckRecApp
import com.deckrec.MainActivity
import com.deckrec.R
import com.deckrec.audio.RecorderConfig
import com.deckrec.audio.RecorderState
import com.deckrec.data.formatBytes
import com.deckrec.data.formatDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keeps the process alive for the length of a set and puts the transport in the notification shade.
 *
 * The engine itself lives in [DeckRecApp]; this service exists so Android does not reclaim the
 * process when the screen goes off, and so a DJ can drop a cue point from the lock screen without
 * unlocking the phone mid-mix.
 */
class RecordingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotifiedSecond = -1L

    /**
     * Guards against the service shutting itself down on the engine's initial Idle value, which
     * the state flow replays the moment we subscribe — before [ACTION_START] has been handled.
     */
    private var sawRecording = false

    /** Latest start id, so shutdown can be refused when a newer start command has arrived. */
    private var latestStartId = 0

    /** True between accepting an ACTION_START and the engine actually starting (or refusing). */
    @Volatile
    private var pendingStart = false

    private var shutdownPending = false

    /** The in-flight ACTION_START, so a Stop arriving during the wait can cancel it. */
    private var startJob: kotlinx.coroutines.Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val app = DeckRecApp.from(this)

        combine(app.recordingEngine.progress, app.recordingEngine.state) { progress, state ->
            progress to state
        }
            .onEach { (progress, state) ->
                if (state is RecorderState.Recording || state is RecorderState.Starting) {
                    sawRecording = true
                }

                // Both Idle and Failed are terminal, but only once this service has actually seen
                // its session begin. A StateFlow replays its current value on subscribe, so
                // without the guard a leftover value from a previous session shuts the service
                // down before ACTION_START has even been handled.
                val finished = sawRecording &&
                    (state is RecorderState.Failed || state is RecorderState.Idle)
                if (finished) {
                    if (state is RecorderState.Failed) {
                        postFinishedNotification(
                            "Recording stopped — ${formatDuration(progress.elapsedMs)} captured",
                            state.message,
                        )
                        stopEverything(cancelNotification = true)
                    } else {
                        stopEverything()
                    }
                    return@onEach
                }

                // The notification only ever shows whole seconds, so rebuilding it more often
                // than that is wasted work on the main thread while audio is running.
                val second = progress.elapsedMs / 1000
                if (second != lastNotifiedSecond) {
                    lastNotifiedSecond = second
                    notifyIfPermitted(
                        buildNotification(
                            state,
                            progress.elapsedMs,
                            progress.sizeBytes,
                            progress.markerCount,
                        )
                    )
                }
            }
            .launchIn(scope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = DeckRecApp.from(this)
        val engine = app.recordingEngine
        val action = intent?.action
        latestStartId = startId

        // Every path through here must either go foreground immediately or shut the service down.
        // Anything else leaves a started service that is neither foreground nor stoppable — and
        // for a mic-typed service on Android 12+, being started without going foreground in time
        // is a crash.
        // pendingStart counts too: the "Starting…" notification carries Stop/Pause/Mark, and a
        // start can be waiting up to twenty seconds for the previous session to drain. Treating
        // that window as "nothing in progress" threw the user's Stop away and recorded anyway.
        if (action != ACTION_START && !engine.isRecording && !pendingStart) {
            // A null intent (sticky restart after the process was killed) or a button press from a
            // stale notification. The session is already gone and cannot be resumed.
            Log.i(TAG, "Ignoring $action with no recording in progress")
            stopEverything()
            return START_NOT_STICKY
        }

        when (action) {
            ACTION_START -> {
                val config = pendingConfig ?: app.settingsStore.current.toRecorderConfig(null, "", 2)
                pendingConfig = null
                startForegroundCompat(buildNotification(RecorderState.Starting, 0, 0, 0))
                acquireWakeLock()
                pendingStart = true
                startJob?.cancel()
                startJob = scope.launch {
                    var started = false
                    try {
                        started = withContext(Dispatchers.IO) {
                            // The previous session can still be draining its write queue for
                            // several seconds. Waiting it out is the difference between the user's
                            // Record tap being honoured and it silently evaporating.
                            val deadline = System.currentTimeMillis() + START_WAIT_MS
                            while (engine.isRecording && System.currentTimeMillis() < deadline) {
                                // delay(), not Thread.sleep(): cancellation has to reach this loop,
                                // or a destroyed service still starts an orphaned recording that
                                // has no foreground service, no wake lock and no way to stop it.
                                delay(50)
                            }
                            if (!isActive || engine.isRecording) false else engine.start(config)
                        }
                    } finally {
                        pendingStart = false
                    }
                    if (!started) {
                        Log.w(TAG, "Engine refused to start")
                        val reason = (engine.state.value as? RecorderState.Failed)?.message
                            ?: "The previous recording was still finishing."
                        postFinishedNotification("Could not start recording", reason)
                        stopEverything(cancelNotification = false)
                    }
                }
            }

            ACTION_MARK -> engine.addMarker()

            ACTION_TOGGLE_PAUSE -> {
                engine.togglePause()
                val progress = engine.progress.value
                notifyIfPermitted(
                    buildNotification(
                        engine.state.value,
                        progress.elapsedMs,
                        progress.sizeBytes,
                        progress.markerCount,
                    )
                )
            }

            ACTION_STOP -> {
                // Cancel a start that has not happened yet, or it would begin recording moments
                // after the user asked everything to stop.
                startJob?.cancel()
                startJob = null
                pendingStart = false
                // stop() joins the audio and writer threads, so it must not run on the main thread.
                scope.launch {
                    withContext(Dispatchers.IO) { engine.stop() }
                    stopEverything()
                }
            }
        }
        // Nothing here can be resumed after a process kill, so a sticky restart would only produce
        // a zombie service and a notification claiming to be recording when it is not.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // A set in progress must survive the task being swiped away.
        if (!DeckRecApp.from(this).recordingEngine.isRecording) stopEverything()
        super.onTaskRemoved(rootIntent)
    }

    /**
     * Posting is best-effort: on Android 13+ the user can deny notifications outright, and that
     * must not be allowed to take the recording down with it.
     */
    private fun notifyIfPermitted(notification: Notification) {
        runCatching { NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification) }
    }

    private fun stopEverything(cancelNotification: Boolean = true) {
        // A stop belonging to a finished session must never tear down a session that has just
        // started. engine.stop() takes seconds, so a Record tap landing in that window is easily
        // delivered before the old stop's continuation runs.
        if (DeckRecApp.from(this).recordingEngine.isRecording || pendingStart) {
            // Deferred, not dropped. Returning here and hoping another emission arrives is how the
            // service ends up pinned foreground with a stale notification and a held wake lock.
            Log.i(TAG, "Deferring shutdown: work still in progress")
            scheduleShutdownRetry(cancelNotification)
            return
        }

        releaseWakeLock()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        // stopForeground only removes a notification the service itself owns. One posted through
        // notify() outlives it, so cancel explicitly or a phantom "Recording" sits in the shade.
        if (cancelNotification) {
            runCatching { NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID) }
        }
        // stopSelfResult, not stopSelf: the bare form ignores start ids and will destroy the
        // service even when a newer start command has already been delivered.
        if (!stopSelfResult(latestStartId)) {
            Log.i(TAG, "A newer start command arrived; staying alive")
        }
    }

    /**
     * Waits for the engine to settle, then shuts down for real.
     *
     * There is no second chance from the state flow: once the engine has published its terminal
     * state, nothing else is coming, so a shutdown request that arrives too early has to be held
     * rather than discarded.
     */
    private fun scheduleShutdownRetry(cancelNotification: Boolean) {
        if (shutdownPending) return
        shutdownPending = true
        val engine = DeckRecApp.from(this).recordingEngine
        val elapsedAtStart = engine.progress.value.elapsedMs
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val deadline = System.currentTimeMillis() + SHUTDOWN_WAIT_MS
                    while ((engine.isRecording || pendingStart) &&
                        System.currentTimeMillis() < deadline
                    ) {
                        delay(100)
                    }
                }
            } finally {
                shutdownPending = false
            }

            when {
                !engine.isRecording && !pendingStart -> stopEverything(cancelNotification)

                // Still "recording" but not a single frame in the whole window: the capture thread
                // is wedged in a blocking read on a dead device and will never publish a terminal
                // state, because it is the thread that would have to publish it. Without this the
                // service stays foreground with a held wake lock until the user force-stops the app.
                engine.progress.value.elapsedMs == elapsedAtStart -> {
                    Log.w(TAG, "Capture appears wedged; forcing shutdown")
                    postFinishedNotification(
                        "Recording stopped — ${formatDuration(elapsedAtStart)} captured",
                        "The input stopped responding. What was captured has been saved.",
                    )
                    forceStop()
                }

                // A new session is genuinely live; its own terminal state will drive shutdown.
                else -> Log.i(TAG, "Abandoning shutdown: a recording is running")
            }
        }
    }

    /** Shuts down without the in-progress guard, for when waiting has already failed. */
    private fun forceStop() {
        releaseWakeLock()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        runCatching { NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID) }
        stopSelfResult(latestStartId)
    }

    /**
     * Replaces the ongoing notification with a dismissible one describing how the session ended.
     *
     * Without this, a set that dies in the user's pocket — cable pulled, storage full — simply
     * makes the notification disappear. Nothing else tells them until they next open the app, by
     * which time the gig is over.
     */
    private fun postFinishedNotification(title: String, detail: String) {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, DeckRecApp.RECORDING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_rec)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setOngoing(false)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .build()
        runCatching {
            NotificationManagerCompat.from(this).notify(FINISHED_NOTIFICATION_ID, notification)
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(
        state: RecorderState,
        elapsedMs: Long,
        sizeBytes: Long,
        markerCount: Int,
    ): Notification {
        val paused = state is RecorderState.Recording && state.paused
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = when {
            state is RecorderState.Starting -> "Starting…"
            paused -> "Paused · ${formatDuration(elapsedMs)}"
            else -> "Recording · ${formatDuration(elapsedMs)}"
        }
        val markerText = if (markerCount == 1) "1 marker" else "$markerCount markers"

        return NotificationCompat.Builder(this, DeckRecApp.RECORDING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_rec)
            .setContentTitle(title)
            .setContentText("${formatBytes(sizeBytes)} · $markerText")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, "Mark", servicePendingIntent(ACTION_MARK, 1))
            .addAction(0, if (paused) "Resume" else "Pause", servicePendingIntent(ACTION_TOGGLE_PAUSE, 2))
            .addAction(0, "Stop", servicePendingIntent(ACTION_STOP, 3))
            .build()
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, RecordingService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire(MAX_SET_MILLIS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        wakeLock = null
    }

    companion object {
        const val ACTION_START = "com.deckrec.action.START"
        const val ACTION_STOP = "com.deckrec.action.STOP"
        const val ACTION_MARK = "com.deckrec.action.MARK"
        const val ACTION_TOGGLE_PAUSE = "com.deckrec.action.TOGGLE_PAUSE"

        private const val TAG = "RecordingService"
        private const val NOTIFICATION_ID = 1001
        private const val FINISHED_NOTIFICATION_ID = 1002
        private const val START_WAIT_MS = 20_000L
        private const val SHUTDOWN_WAIT_MS = 40_000L
        private const val WAKE_LOCK_TAG = "DeckRec::Recording"
        private const val MAX_SET_MILLIS = 12L * 60L * 60L * 1000L

        @Volatile
        private var pendingConfig: RecorderConfig? = null

        fun start(context: Context, config: RecorderConfig) {
            pendingConfig = config
            val intent = Intent(context, RecordingService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun send(context: Context, action: String) {
            val intent = Intent(context, RecordingService::class.java).setAction(action)
            context.startService(intent)
        }
    }
}
