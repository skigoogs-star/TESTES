package com.deckrec.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import com.deckrec.audio.dsp.DspChain
import com.deckrec.audio.dsp.Levels
import com.deckrec.audio.write.AacSink
import com.deckrec.audio.write.AudioSink
import com.deckrec.audio.write.PeakFileWriter
import com.deckrec.audio.write.WavSink
import com.deckrec.data.Marker
import com.deckrec.data.RecordingFormat
import com.deckrec.data.RecordingMeta
import com.deckrec.data.RecordingProgress
import com.deckrec.data.RecordingStore
import com.deckrec.usb.UsbAudioScanner
import com.deckrec.usb.host.UsbCaptureController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Captures from the selected input, runs the record bus, and streams the result to disk.
 *
 * Two threads, deliberately. The capture thread does nothing but read, process and hand off — it
 * never touches the filesystem, because a flash write stall of a few hundred milliseconds is
 * routine on a phone and would silently overrun the capture buffer. The writer thread owns the
 * output files and absorbs those stalls out of a queue deep enough to ride through them.
 *
 * Two rules this class follows:
 *  - **Recorded audio is never deleted.** Every failure path finalises what was captured. A file
 *    is only removed when it contains no audio at all.
 *  - **All per-recording state lives in a [Session].** Nothing that a writer thread reads is stored
 *    on the engine, because a writer can outlive the join timeout and would otherwise read the
 *    *next* session's flags — spinning forever on a stale termination condition, stamping one
 *    session's markers onto another's file, or failing a live session on behalf of a dead one.
 */
class RecordingEngine(
    private val context: Context,
    private val scanner: UsbAudioScanner,
    private val store: RecordingStore,
    /**
     * Supplies already-open USB sessions for hardware the platform will not route.
     *
     * Only ever read here. Opening one needs a permission dialog and would block this engine's
     * threads past their join timeouts, so it happens where the input is chosen instead.
     */
    private val usbCapture: UsbCaptureController? = null,
) {

    private val _state = MutableStateFlow<RecorderState>(RecorderState.Idle)
    val state: StateFlow<RecorderState> = _state.asStateFlow()

    private val _levels = MutableStateFlow(Levels())
    val levels: StateFlow<Levels> = _levels.asStateFlow()

    private val _progress = MutableStateFlow(RecordingProgress())
    val progress: StateFlow<RecordingProgress> = _progress.asStateFlow()

    private val _markers = MutableStateFlow<List<Marker>>(emptyList())
    val markers: StateFlow<List<Marker>> = _markers.asStateFlow()

    /** Non-fatal things the DJ should know about this session. */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /** Invoked off the audio thread whenever a file is closed, including auto-split parts. */
    var onPartCompleted: ((RecordingMeta) -> Unit)? = null

    private val running = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)

    @Volatile
    private var captureThread: Thread? = null

    @Volatile
    private var writerThread: Thread? = null

    @Volatile
    private var session: Session? = null

    @Volatile
    private var dsp: DspChain? = null

    @Volatile
    private var pendingManualMarker = false

    /**
     * Bumped on every monitor start and stop. A monitor thread only touches shared state while its
     * own generation is still current, which makes a thread that outlived its join inert rather
     * than dangerous — the alternative is a wedged monitor waking up mid-set and overwriting the
     * live recording's DSP chain, after which every gain and limiter control moves a dead object.
     */
    private val monitorGeneration = AtomicInteger(0)
    private val monitorStop = AtomicBoolean(false)

    @Volatile
    private var monitorThread: Thread? = null

    /**
     * Serialises monitor control off any cancellable scope. Teardown requested as the UI is being
     * destroyed must still run: dispatching it through a ViewModel scope means cancellation can
     * eat it and leave the microphone open in a process with no UI.
     */
    private val monitorControl: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "DeckRec-MonitorControl").apply { isDaemon = true }
        }

    private val _isMonitoring = MutableStateFlow(false)
    val isMonitoring: StateFlow<Boolean> = _isMonitoring.asStateFlow()

    /**
     * Why monitoring is not producing levels, or null when it is.
     *
     * Every early return in the monitor path used to be silent, so an input could sit selected and
     * highlighted with dead meters and nothing anywhere saying why.
     */
    private val _monitorStatus = MutableStateFlow<String?>(null)
    val monitorStatus: StateFlow<String?> = _monitorStatus.asStateFlow()

    /** Fire-and-forget monitor start; requests are applied in the order they were made. */
    fun requestMonitor(config: RecorderConfig) {
        monitorControl.execute { startMonitor(config) }
    }

    /** Fire-and-forget monitor stop. Safe to call from the main thread. */
    fun releaseMonitor() {
        monitorControl.execute { stopMonitor() }
    }

    val isRecording: Boolean get() = running.get()

    /**
     * Opens the input and runs the record bus without writing anything.
     *
     * Without this the meters are dead until REC is pressed, which makes the two things you must
     * get right before a set — that the selected channel pair really is the master bus, and that
     * the gain is staged sensibly — verifiable only by recording, watching, stopping and trying
     * again. Monitoring runs only while the record screen is on top, so it never holds the
     * microphone in the background.
     */
    @Synchronized
    fun startMonitor(config: RecorderConfig): Boolean {
        if (running.get()) return false
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            _monitorStatus.value = "Microphone permission is required — Android gates USB audio " +
                "input behind it too."
            return false
        }

        // Always supersedes: whatever was monitoring is retired before the new one opens the input.
        retireMonitor()
        val generation = monitorGeneration.incrementAndGet()
        monitorStop.set(false)
        val worker = Thread({ runMonitor(config, generation) }, "DeckRec-Monitor")
        worker.priority = Thread.MAX_PRIORITY
        monitorThread = worker
        worker.start()
        return true
    }

    /**
     * Blocks briefly while the monitor releases the input. Never call from the main thread.
     *
     * Synchronized with [startMonitor] so a stop and a start cannot interleave — the screen's
     * lifecycle can issue them back to back, and an interleaved pair would leave the monitor
     * running with its stop flag already consumed.
     */
    @Synchronized
    fun stopMonitor() {
        retireMonitor()
    }

    /** Invalidates the running monitor and waits briefly for it to let go of the input. */
    private fun retireMonitor() {
        monitorGeneration.incrementAndGet()
        monitorStop.set(true)
        monitorThread?.let { worker ->
            runCatching { worker.join(MONITOR_JOIN_MS) }
            if (worker.isAlive) {
                // It can no longer touch anything shared — its generation is stale — but it may
                // still be holding the input, which is worth knowing about when REC then fails.
                Log.w(TAG, "Monitor thread did not stop in time; it is now inert but may hold the input")
            }
        }
        monitorThread = null
        _isMonitoring.value = false
    }

    private fun runMonitor(config: RecorderConfig, generation: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        fun current() = monitorGeneration.get() == generation
        fun status(message: String?) {
            if (current()) _monitorStatus.value = message
        }

        var capture: CaptureSource? = null
        try {
            capture = openCapture(config)
            if (capture == null) {
                status(
                    "${config.deviceName} would not open. If this is a DJ mixer, check that its " +
                        "USB audio output is switched on."
                )
                return
            }
            if (!current()) return

            when {
                !capture.pinnedToDevice && config.deviceId != null -> status(
                    "Could not route from ${config.deviceName} — these levels are the phone's " +
                        "default input, not the mixer."
                )

                !capture.honouredRequestedPair -> status(
                    "This input would not expose channels " +
                        "${config.channelPair.left + 1}/${config.channelPair.right + 1}; " +
                        "showing channels 1/2."
                )

                else -> status("Waiting for audio from ${config.deviceName}…")
            }

            val chain = DspChain(config.sampleRate).apply {
                inputGainDb = config.inputGainDb
                subBassAmount = config.subBassAmount
                loudnessAmount = config.loudnessAmount
                limiterEnabled = config.limiterEnabled
                transitionDetector.enabled = false
            }
            if (!current()) return
            dsp = chain

            capture.start()
            if (current()) _isMonitoring.value = true
            var sawAudio = false

            val framesPerRead = capture.framesPerRead
            val stereo = FloatArray(framesPerRead * 2)

            while (!monitorStop.get() && !running.get() && current()) {
                // A read failure throws; the catch below turns it into a monitor status. A zero is
                // only a timeout, and means nothing has arrived yet.
                val framesRead = capture.readStereo(stereo, framesPerRead)
                if (framesRead == 0) continue
                // Frames are arriving, so whatever the opening status said is now obsolete.
                if (!sawAudio) {
                    sawAudio = true
                    if (capture.pinnedToDevice && capture.honouredRequestedPair) status(null)
                }
                val measured = chain.process(stereo, framesRead)
                if (current()) _levels.value = measured
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Monitoring stopped: ${e.message}")
            status(e.message ?: "Monitoring stopped unexpectedly.")
        } finally {
            runCatching { capture?.close() }
            // Only a monitor that is still the current one may clear shared state. A stale thread
            // waking up here must not null the DSP chain of a recording that has since started.
            if (current()) {
                if (!running.get()) {
                    dsp = null
                    _levels.value = Levels()
                }
                _isMonitoring.value = false
            }
        }
    }

    /** File names the engine currently has open, so the library never adopts a live recording. */
    fun openFileNames(): Set<String> = session?.openFileNames() ?: emptySet()

    fun start(config: RecorderConfig): Boolean {
        // compareAndSet, not get-then-set: a check-then-act here lets two callers both start a
        // capture thread and fight over the same output files.
        if (!running.compareAndSet(false, true)) return false

        // Checked before tearing the monitor down: killing the meters and then refusing to start
        // leaves the record screen dead with nothing to explain why.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            running.set(false)
            _state.value = RecorderState.Failed("Microphone permission is required to record")
            return false
        }

        // The monitor owns the input until it lets go; recording cannot open it underneath.
        stopMonitor()

        val newSession = Session(config)
        session = newSession
        stopRequested.set(false)
        paused.set(false)
        _markers.value = emptyList()
        _progress.value = RecordingProgress()
        _notice.value = null
        _state.value = RecorderState.Starting

        val worker = Thread({ runCapture(newSession) }, "DeckRec-Audio")
        worker.priority = Thread.MAX_PRIORITY
        captureThread = worker
        worker.start()
        return true
    }

    /** Blocks until both threads have wound down. Never call from the main thread. */
    fun stop() {
        stopRequested.set(true)
        captureThread?.let { runCatching { it.join(STOP_JOIN_MS) } }
        writerThread?.let { runCatching { it.join(STOP_JOIN_MS) } }
        captureThread = null
        writerThread = null
    }

    fun setPaused(value: Boolean) {
        // Only meaningful while a session is live. Without this guard a pause tap racing a failure
        // republishes Recording over the terminal state, and the UI shows a session that is gone.
        if (!running.get()) return
        paused.set(value)
        val current = _state.value
        if (current is RecorderState.Recording) {
            _state.value = RecorderState.Recording(paused = value)
        }
    }

    fun togglePause() = setPaused(!paused.get())

    /** Drops a cue point at the current position. Safe to call from any thread. */
    fun addMarker(label: String = "") {
        val live = session ?: return
        if (!running.get()) return
        val positionMs = live.frames * 1000L / live.config.sampleRate.coerceAtLeast(1)
        addMarkerInternal(live, positionMs, label, automatic = false)
        pendingManualMarker = true
    }

    fun updateGain(db: Float) {
        dsp?.inputGainDb = db
    }

    fun updateSubBass(amount: Float) {
        dsp?.subBassAmount = amount
    }

    fun updateLoudness(amount: Float) {
        dsp?.loudnessAmount = amount
    }

    fun updateLimiter(enabled: Boolean) {
        dsp?.limiterEnabled = enabled
    }

    fun updateAutoMarkers(enabled: Boolean) {
        dsp?.transitionDetector?.enabled = enabled
    }

    fun updateMarkerSensitivity(value: Float) {
        dsp?.transitionDetector?.sensitivity = value
    }

    fun updateMarkerGapSeconds(seconds: Float) {
        dsp?.transitionDetector?.minimumGapSeconds = seconds
    }

    fun consumeNotice() {
        _notice.value = null
    }

    /**
     * Clears a terminal state left over from a previous session.
     *
     * [RecorderState.Failed] is sticky so the UI can show it, but the recording service treats a
     * terminal state as "this session is over and I should shut down". Without clearing it first,
     * a service started after any earlier failure sees the stale value the moment it subscribes.
     */
    fun clearTerminalState() {
        if (running.get()) return
        _state.value = RecorderState.Idle
        _notice.value = null
    }

    private fun addMarkerInternal(
        live: Session,
        positionMs: Long,
        label: String,
        automatic: Boolean,
    ) {
        val marker = Marker(
            id = UUID.randomUUID().toString(),
            positionMs = positionMs,
            label = label,
            automatic = automatic,
        )
        val snapshot = synchronized(live.markerLock) {
            live.markers.add(marker)
            live.markers.sortBy { it.positionMs }
            live.markers.toList()
        }
        if (session !== live) return
        _markers.value = snapshot
        _progress.update { it.copy(markerCount = snapshot.size) }
    }

    /** Publishes only on behalf of the live session, so a lingering writer cannot stamp on it. */
    private fun publish(live: Session, transform: (RecordingProgress) -> RecordingProgress) {
        if (session !== live) return
        _progress.update(transform)
    }

    private fun notice(live: Session, message: String) {
        if (session !== live) return
        _notice.value = message
    }

    // ---- Capture thread -------------------------------------------------------------------

    private fun runCapture(live: Session) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val config = live.config
        var capture: CaptureSource? = null

        try {
            capture = openCapture(config)
                ?: throw IllegalStateException("No usable capture format on this input")

            if (!capture.honouredRequestedPair) {
                notice(
                    live,
                    "This input would not expose channels " +
                        "${config.channelPair.left + 1}/${config.channelPair.right + 1}; " +
                        "recording channels 1/2 instead.",
                )
            }
            if (!capture.pinnedToDevice && config.deviceId != null) {
                // Worth shouting about: the alternative is two hours of room noise off the phone
                // microphone with the meters moving convincingly the whole time.
                notice(
                    live,
                    "Could not route from ${config.deviceName}. Recording the phone's default " +
                        "input instead — check the cable and the mixer's USB setting.",
                )
            }

            val chain = DspChain(config.sampleRate).apply {
                inputGainDb = config.inputGainDb
                subBassAmount = config.subBassAmount
                loudnessAmount = config.loudnessAmount
                limiterEnabled = config.limiterEnabled
                transitionDetector.enabled = config.autoMarkersEnabled
                transitionDetector.sensitivity = config.autoMarkerSensitivity
                transitionDetector.minimumGapSeconds = config.autoMarkerMinimumGapSeconds
            }
            dsp = chain

            val framesPerRead = capture.framesPerRead
            startWriter(live, framesPerRead)

            capture.start()
            _state.value = RecorderState.Recording(paused = false)

            val stereo = FloatArray(framesPerRead * 2)

            while (!stopRequested.get() && live.failure == null) {
                val framesRead = capture.readStereo(stereo, framesPerRead)
                if (framesRead == 0) continue

                if (paused.get()) {
                    _levels.value = Levels()
                    continue
                }

                // The marker timeline is the *file* timeline, so it is only ever advanced by audio
                // that actually reached the writer.
                val blockStartFrame = live.frames
                _levels.value = chain.process(stereo, framesRead)

                val detected = chain.transitionDetector.analyse(stereo, framesRead, blockStartFrame)
                if (pendingManualMarker) {
                    chain.transitionDetector.noteManualMarker(blockStartFrame)
                    pendingManualMarker = false
                }
                if (detected >= 0) {
                    addMarkerInternal(
                        live,
                        detected * 1000L / config.sampleRate,
                        "",
                        automatic = true,
                    )
                }

                if (enqueue(live, stereo, framesRead)) {
                    live.frames += framesRead
                } else {
                    live.droppedFrames += framesRead
                    notice(live, "Storage could not keep up; some audio was dropped.")
                }

                publish(live) {
                    it.copy(
                        elapsedMs = live.frames * 1000L / config.sampleRate,
                        droppedFrames = live.droppedFrames,
                    )
                }
            }

            _state.value = RecorderState.Stopping
        } catch (e: Throwable) {
            Log.e(TAG, "Capture failed", e)
            // Recorded, not published. A terminal state announced while isRecording is still true
            // is unobservable to the service — it declines to shut down mid-recording, and by the
            // time the flag clears there is no further emission to react to, so the foreground
            // service and its notification would be pinned for good.
            live.captureFailure = e.message ?: "Recording failed"
        } finally {
            runCatching { capture?.close() }
            live.captureFinished = true

            // The writer owns the files; wait for it to close them before declaring the session over.
            writerThread?.let { runCatching { it.join(WRITER_JOIN_MS) } }

            dsp = null
            _levels.value = Levels()
            running.set(false)

            // Terminal state goes out last, and only for the live session: a previous session
            // winding down must not stamp Idle over a new one's Starting.
            if (session === live) {
                val failure = live.captureFailure ?: live.failure
                _state.value = if (failure != null) {
                    RecorderState.Failed(failure)
                } else {
                    RecorderState.Idle
                }
                // Nothing reads the session once it is over, and its queues are megabytes.
                session = null
            }
        }
    }

    /** @return true if the block was handed to the writer. */
    private fun enqueue(live: Session, stereo: FloatArray, frames: Int): Boolean {
        val free = live.free ?: return false
        val queue = live.queued ?: return false
        // Waiting briefly is right: the AudioRecord buffer is deep enough to cover it, and dropping
        // audio should be a last resort rather than the first thing that happens under load.
        val block = free.poll(ENQUEUE_WAIT_MS, TimeUnit.MILLISECONDS)
        if (block == null) {
            Log.w(TAG, "Writer is not keeping up; dropped $frames frames")
            return false
        }
        System.arraycopy(stereo, 0, block.data, 0, frames * 2)
        block.frames = frames
        if (!queue.offer(block)) {
            free.offer(block)
            Log.w(TAG, "Write queue full; dropped $frames frames")
            return false
        }
        return true
    }

    // ---- Writer thread --------------------------------------------------------------------

    private fun startWriter(live: Session, framesPerRead: Int) {
        val blockCount = ((live.config.sampleRate * QUEUE_SECONDS) / framesPerRead).coerceIn(16, 512)
        val free = ArrayBlockingQueue<Block>(blockCount)
        val queued = ArrayBlockingQueue<Block>(blockCount)
        repeat(blockCount) { free.offer(Block(FloatArray(framesPerRead * 2))) }
        live.free = free
        live.queued = queued

        val worker = Thread({ runWriter(live, free, queued) }, "DeckRec-Writer")
        worker.priority = Thread.NORM_PRIORITY + 2
        writerThread = worker
        worker.start()
    }

    private fun runWriter(
        live: Session,
        free: ArrayBlockingQueue<Block>,
        queued: ArrayBlockingQueue<Block>,
    ) {
        val config = live.config
        var current: Part? = null
        try {
            current = newPart(live, partIndex = 0, startSessionFrame = 0L)

            val splitFrames = if (config.autoSplitEnabled) {
                config.autoSplitMinutes.toLong() * 60L * config.sampleRate
            } else {
                Long.MAX_VALUE
            }
            val maxPartBytes = if (config.format.isWav) WavSink.MAX_DATA_BYTES else Long.MAX_VALUE

            while (true) {
                val block = queued.poll(WRITER_POLL_MS, TimeUnit.MILLISECONDS)
                if (block == null) {
                    // Reads this session's own flag, never the engine's: a writer that outlived its
                    // join must still terminate on its own session ending, not on the next one's.
                    if (live.captureFinished && queued.isEmpty()) break
                    continue
                }

                val part = current!!
                part.sink.write(block.data, block.frames)
                // Counted as soon as the audio is in the file. Counting after the waveform write
                // would let a peaks failure on the very first block leave frames at zero, and the
                // zero-frame path deletes the file.
                part.frames += block.frames
                part.peaks.write(block.data, block.frames)
                free.offer(block)

                publish(live) { it.copy(sizeBytes = part.sink.bytesOnDisk, partIndex = part.index) }

                if (part.frames >= splitFrames || part.sink.bytesOnDisk >= maxPartBytes) {
                    // Commit the finished part and drop the reference *before* opening the next
                    // one. If opening fails, the failure path must not be holding a part whose
                    // audio is already on disk and already in the library.
                    val nextIndex = part.index + 1
                    val nextStart = part.startSessionFrame + part.frames
                    current = null
                    finishPart(live, part)
                    current = newPart(live, nextIndex, nextStart)
                }
            }

            current?.let {
                current = null
                finishPart(live, it)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Writer failed", e)
            live.failure = e.message ?: "Could not write to storage"
            // Salvage rather than discard: whatever was captured up to this point is kept.
            current?.let { runCatching { finishPart(live, it) } }
        }
    }

    private fun newPart(live: Session, partIndex: Int, startSessionFrame: Long): Part {
        val target = store.newRecordingTarget(live.config, partIndex)
        val part = Part(
            index = partIndex,
            target = target,
            sink = openSink(live.config, target.audioFile),
            peaks = PeakFileWriter(target.peaksFile),
            startedAt = System.currentTimeMillis(),
            startSessionFrame = startSessionFrame,
        )
        live.openPart = part
        return part
    }

    private fun finishPart(live: Session, part: Part) {
        runCatching { part.peaks.close() }
        val config = live.config

        val snapshot = synchronized(live.markerLock) { live.markers.toList() }
        val partStartMs = part.startSessionFrame * 1000L / config.sampleRate
        val partDurationMs = part.frames * 1000L / config.sampleRate
        // Half-open at the top: a marker landing exactly on a split boundary belongs to the part
        // that starts there, not to both.
        val partMarkers = snapshot
            .filter { it.positionMs >= partStartMs && it.positionMs < partStartMs + partDurationMs }
            .map { it.copy(positionMs = it.positionMs - partStartMs) }

        val usable = runCatching { part.sink.finish(partMarkers) }.getOrDefault(false)
        live.openPart = null

        val fileLength = part.target.audioFile.length()
        // Deleting is gated on the file being empty as well as the counter reading zero: a write
        // that threw partway still put audio on disk, and "recorded audio is never deleted" has to
        // hold even when the bookkeeping disagrees with the filesystem.
        if (part.frames == 0L && fileLength <= WavSink.HEADER_BYTES) {
            // Nothing was ever captured, so there is no audio to lose — and leaving the stub behind
            // would have the library adopt it as a phantom "recovered" recording at next launch.
            runCatching { part.target.peaksFile.delete() }
            runCatching { part.target.audioFile.delete() }
            return
        }

        if (!part.target.audioFile.isFile) {
            Log.w(TAG, "Part ${part.index} left no file on disk")
            return
        }

        if (!usable && config.format.isWav) {
            // finish() failed partway, so the header still declares a zero-length data chunk. Once
            // a sidecar exists the launch-time orphan scan will skip this file forever, so this is
            // the only chance to make the salvaged audio playable.
            runCatching { WavSink.repairTruncated(part.target.audioFile) }
        }

        val meta = RecordingMeta(
            id = part.target.id,
            fileName = part.target.audioFile.name,
            createdAtEpochMs = part.startedAt,
            durationMs = partDurationMs,
            sampleRate = config.sampleRate,
            channels = 2,
            format = config.format,
            aacBitrateKbps = config.aacBitrateKbps,
            sourceDeviceName = config.deviceName,
            markers = partMarkers,
            peaksFileName = part.target.peaksFile.name,
            sizeBytes = part.target.audioFile.length(),
            partIndex = part.index,
        )
        // refreshLibrary = false: rescanning the whole library here would block the writer while
        // audio is still arriving. The listener refreshes off-thread instead.
        store.save(meta, refreshLibrary = false)
        onPartCompleted?.invoke(meta)
    }

    private fun openSink(config: RecorderConfig, file: File): AudioSink = when (config.format) {
        RecordingFormat.WAV_24 -> WavSink(file, config.sampleRate, 2, 24)
        RecordingFormat.WAV_16 -> WavSink(file, config.sampleRate, 2, 16)
        RecordingFormat.AAC -> AacSink(file, config.sampleRate, 2, config.aacBitrateKbps)
    }

    // ---- Capture plumbing -----------------------------------------------------------------

    /**
     * Opens the best stream the device will actually give us, most capable first.
     *
     * Multi-channel USB mixers are the interesting case: the master bus is rarely channels 1/2, so
     * we first ask the HAL for exactly the requested pair via a channel index mask, and only fall
     * back to taking every channel and slicing it ourselves if that is refused.
     */
    private fun openCapture(config: RecorderConfig): CaptureSource? {
        // Hardware the platform refuses to route is read straight off its USB endpoint. The session
        // must already exist: see the constructor note on why this cannot open one itself.
        config.usbDeviceName?.let { deviceName ->
            val session = usbCapture?.sessionFor(deviceName)
                ?: throw CaptureException(
                    "Direct USB capture is not open for ${config.deviceName}. " +
                        "Select it again to grant access."
                )
            Log.i(TAG, "Capturing directly from $deviceName")
            return UsbCaptureSource(session, config.channelPair, FRAMES_PER_READ)
        }

        val deviceInfo = config.deviceId?.let { scanner.deviceInfoFor(it) }
        val source = preferredAudioSource()
        val pair = config.channelPair
        val sourceChannels = config.sourceChannelCount.coerceAtLeast(2)
        val wantsSpecificPair = sourceChannels > 2 && pair.left != 0

        val attempts = buildList {
            if (sourceChannels > 2) {
                val pairMask = pair.indexMask
                add(Attempt.IndexMask(pairMask, AudioFormat.ENCODING_PCM_FLOAT, 0, 1, true))
                add(Attempt.IndexMask(pairMask, AudioFormat.ENCODING_PCM_16BIT, 0, 1, true))
                val allMask = (1 shl sourceChannels) - 1
                add(Attempt.IndexMask(allMask, AudioFormat.ENCODING_PCM_FLOAT, pair.left, pair.right, true))
                add(Attempt.IndexMask(allMask, AudioFormat.ENCODING_PCM_16BIT, pair.left, pair.right, true))
            }
            add(Attempt.PositionMask(AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_FLOAT))
            add(Attempt.PositionMask(AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT))
            add(Attempt.PositionMask(AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT))
            add(Attempt.PositionMask(AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT))
        }

        for (attempt in attempts) {
            val capture = tryOpen(attempt, config.sampleRate, source, deviceInfo, wantsSpecificPair)
            if (capture != null) {
                Log.i(TAG, "Opened capture: $attempt at ${config.sampleRate} Hz")
                return capture
            }
        }
        return null
    }

    private fun tryOpen(
        attempt: Attempt,
        sampleRate: Int,
        source: Int,
        deviceInfo: AudioDeviceInfo?,
        wantsSpecificPair: Boolean,
    ): CaptureSource? {
        val encoding = attempt.encoding
        val channels = attempt.channelCount
        val bytesPerSample = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2

        val legacyMask = if (channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, legacyMask, encoding)
        if (minBuffer <= 0) return null

        val framesPerRead = FRAMES_PER_READ
        // A deep hardware buffer is the first line of defence against scheduling and storage
        // hiccups; the minimum buffer is only a few tens of milliseconds and overruns silently.
        val generousBytes = sampleRate * channels * bytesPerSample * CAPTURE_BUFFER_SECONDS
        val modestBytes = maxOf(minBuffer * 4, framesPerRead * channels * bytesPerSample * 4)

        val format = AudioFormat.Builder()
            .setEncoding(encoding)
            .setSampleRate(sampleRate)
            .apply {
                when (attempt) {
                    is Attempt.IndexMask -> setChannelIndexMask(attempt.mask)
                    is Attempt.PositionMask -> setChannelMask(attempt.mask)
                }
            }
            .build()

        val record = buildRecord(source, format, generousBytes)
            ?: buildRecord(source, format, modestBytes)
            ?: return null

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return null
        }

        val pinned = if (deviceInfo == null) true else record.setPreferredDevice(deviceInfo)
        if (!pinned) {
            Log.w(TAG, "Could not pin the stream to ${deviceInfo?.productName}")
        }

        val delivered = record.channelCount.coerceAtLeast(1)
        val left = attempt.leftIndex.coerceIn(0, delivered - 1)
        val right = attempt.rightIndex.coerceIn(0, delivered - 1)

        return AudioRecordSource(
            record = record,
            deliveredChannels = delivered,
            isFloat = encoding == AudioFormat.ENCODING_PCM_FLOAT,
            leftIndex = left,
            rightIndex = right,
            framesPerRead = framesPerRead,
            honouredRequestedPair = !wantsSpecificPair || attempt.selectsChannels,
            pinnedToDevice = pinned,
        )
    }

    private fun buildRecord(source: Int, format: AudioFormat, bufferBytes: Int): AudioRecord? = try {
        AudioRecord.Builder()
            .setAudioSource(source)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferBytes)
            .build()
    } catch (e: Exception) {
        Log.w(TAG, "Capture attempt with ${bufferBytes}B buffer rejected: ${e.message}")
        null
    }

    private fun preferredAudioSource(): Int {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val unprocessedSupported =
            audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        // UNPROCESSED bypasses the AGC/NS/AEC the voice sources apply, which would otherwise pump
        // and gate a DJ mix. Where it is unavailable, CAMCORDER is the least-processed fallback.
        return when {
            unprocessedSupported && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N ->
                MediaRecorder.AudioSource.UNPROCESSED
            else -> MediaRecorder.AudioSource.CAMCORDER
        }
    }

    private sealed interface Attempt {
        val encoding: Int
        val channelCount: Int
        val leftIndex: Int
        val rightIndex: Int

        /** True when this attempt actually honours the user's chosen channel pair. */
        val selectsChannels: Boolean

        data class IndexMask(
            val mask: Int,
            override val encoding: Int,
            override val leftIndex: Int,
            override val rightIndex: Int,
            override val selectsChannels: Boolean,
        ) : Attempt {
            override val channelCount: Int get() = Integer.bitCount(mask)
        }

        data class PositionMask(val mask: Int, override val encoding: Int) : Attempt {
            override val channelCount: Int
                get() = if (mask == AudioFormat.CHANNEL_IN_MONO) 1 else 2
            override val leftIndex: Int get() = 0
            override val rightIndex: Int get() = if (channelCount == 1) 0 else 1
            override val selectsChannels: Boolean get() = false
        }
    }

    /**
     * Everything belonging to one recording session.
     *
     * Held by both threads for the life of that session and by nothing else afterwards, so a writer
     * that outlives its join keeps reading its own flags rather than the next session's.
     */
    private class Session(val config: RecorderConfig) {
        @Volatile var captureFinished = false

        /** Set by the writer thread. */
        @Volatile var failure: String? = null

        /** Set by the capture thread; published only once the session is fully wound down. */
        @Volatile var captureFailure: String? = null

        /** Frames that actually reached the writer — the timeline the file and markers share. */
        @Volatile var frames = 0L

        @Volatile var droppedFrames = 0L

        @Volatile var free: ArrayBlockingQueue<Block>? = null
        @Volatile var queued: ArrayBlockingQueue<Block>? = null

        @Volatile var openPart: Part? = null

        val markerLock = Any()
        val markers = mutableListOf<Marker>()

        fun openFileNames(): Set<String> =
            openPart?.let { setOf(it.target.audioFile.name) } ?: emptySet()
    }

    /** One output file of a session: everything auto-split has to swap out together. */
    private class Part(
        val index: Int,
        val target: RecordingStore.RecordingTarget,
        val sink: AudioSink,
        val peaks: PeakFileWriter,
        val startedAt: Long,
        val startSessionFrame: Long,
        var frames: Long = 0L,
    )

    /** A block of interleaved stereo audio in flight between the capture and writer threads. */
    private class Block(val data: FloatArray) {
        var frames: Int = 0
    }

    private companion object {
        const val TAG = "RecordingEngine"
        const val FRAMES_PER_READ = 1024
        const val STOP_JOIN_MS = 8000L
        const val WRITER_JOIN_MS = 10_000L
        const val WRITER_POLL_MS = 50L
        const val ENQUEUE_WAIT_MS = 500L
        const val MONITOR_JOIN_MS = 3000L

        /** Seconds of audio the hardware buffer holds. */
        const val CAPTURE_BUFFER_SECONDS = 2

        /** Seconds of audio the hand-off queue can absorb while storage stalls. */
        const val QUEUE_SECONDS = 6
    }
}
