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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures from the selected input, runs the record bus, and streams the result to disk.
 *
 * Two threads, deliberately. The capture thread does nothing but read, process and hand off — it
 * never touches the filesystem, because a flash write stall of a few hundred milliseconds is
 * routine on a phone and would silently overrun the capture buffer, losing audio with no error
 * anywhere. The writer thread owns the output files and absorbs those stalls out of a queue deep
 * enough to ride through them.
 *
 * The other rule this class follows: **recorded audio is never deleted.** Every failure path
 * finalises what was captured rather than discarding it. A file is only removed when it contains
 * no audio at all.
 */
class RecordingEngine(
    private val context: Context,
    private val scanner: UsbAudioScanner,
    private val store: RecordingStore,
) {

    private val _state = MutableStateFlow<RecorderState>(RecorderState.Idle)
    val state: StateFlow<RecorderState> = _state.asStateFlow()

    private val _levels = MutableStateFlow(Levels())
    val levels: StateFlow<Levels> = _levels.asStateFlow()

    private val _progress = MutableStateFlow(RecordingProgress())
    val progress: StateFlow<RecordingProgress> = _progress.asStateFlow()

    private val _markers = MutableStateFlow<List<Marker>>(emptyList())
    val markers: StateFlow<List<Marker>> = _markers.asStateFlow()

    /** Non-fatal things the DJ should know about this session, e.g. a channel pair we could not honour. */
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
    private var config: RecorderConfig = RecorderConfig()

    @Volatile
    private var dsp: DspChain? = null

    @Volatile
    private var pendingManualMarker = false

    /** Set by the writer thread when it can no longer write; the capture thread then winds down. */
    @Volatile
    private var writerFailure: String? = null

    private val markerLock = Any()
    private val markerList = mutableListOf<Marker>()

    /** Frames captured across all parts of the current session. */
    @Volatile
    private var sessionFrames = 0L

    private var freeBlocks: ArrayBlockingQueue<Block>? = null
    private var queuedBlocks: ArrayBlockingQueue<Block>? = null

    @Volatile
    private var captureFinished = false

    val isRecording: Boolean get() = running.get()

    fun start(config: RecorderConfig): Boolean {
        // compareAndSet, not get-then-set: a check-then-act here lets two callers both start a
        // capture thread and fight over the same output files.
        if (!running.compareAndSet(false, true)) return false

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            running.set(false)
            _state.value = RecorderState.Failed("Microphone permission is required to record")
            return false
        }

        this.config = config
        stopRequested.set(false)
        paused.set(false)
        sessionFrames = 0L
        writerFailure = null
        captureFinished = false
        synchronized(markerLock) { markerList.clear() }
        _markers.value = emptyList()
        _progress.value = RecordingProgress()
        _notice.value = null
        _state.value = RecorderState.Starting

        val worker = Thread({ runCapture() }, "DeckRec-Audio")
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
        if (!running.get()) return
        val positionMs = sessionFrames * 1000L / config.sampleRate.coerceAtLeast(1)
        addMarkerInternal(positionMs, label, automatic = false)
        pendingManualMarker = true
    }

    fun updateGain(db: Float) {
        config = config.copy(inputGainDb = db)
        dsp?.inputGainDb = db
    }

    fun updateSubBass(amount: Float) {
        config = config.copy(subBassAmount = amount)
        dsp?.subBassAmount = amount
    }

    fun updateLoudness(amount: Float) {
        config = config.copy(loudnessAmount = amount)
        dsp?.loudnessAmount = amount
    }

    fun updateLimiter(enabled: Boolean) {
        config = config.copy(limiterEnabled = enabled)
        dsp?.limiterEnabled = enabled
    }

    fun updateAutoMarkers(enabled: Boolean) {
        config = config.copy(autoMarkersEnabled = enabled)
        dsp?.transitionDetector?.enabled = enabled
    }

    fun consumeNotice() {
        _notice.value = null
    }

    /**
     * Clears a terminal state left over from a previous session.
     *
     * [RecorderState.Failed] is sticky so the UI can show it, but the recording service treats a
     * terminal state as "this session is over and I should shut down". Without clearing it first,
     * a service started after any earlier failure sees the stale value the moment it subscribes
     * and stops itself out from under the new recording.
     */
    fun clearTerminalState() {
        if (running.get()) return
        _state.value = RecorderState.Idle
        _notice.value = null
    }

    private fun addMarkerInternal(positionMs: Long, label: String, automatic: Boolean) {
        val marker = Marker(
            id = UUID.randomUUID().toString(),
            positionMs = positionMs,
            label = label,
            automatic = automatic,
        )
        val snapshot = synchronized(markerLock) {
            markerList.add(marker)
            markerList.sortBy { it.positionMs }
            markerList.toList()
        }
        _markers.value = snapshot
        // update{} rather than value = value.copy{}: the capture thread, the writer thread and the
        // UI all mutate different fields of this object, and a read-modify-write would lose one.
        _progress.update { it.copy(markerCount = snapshot.size) }
    }

    // ---- Capture thread -------------------------------------------------------------------

    private fun runCapture() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        var capture: Capture? = null

        try {
            capture = openCapture(config)
                ?: throw IllegalStateException("No usable capture format on this input")

            if (!capture.honouredRequestedPair) {
                _notice.value = "This input would not expose channels " +
                    "${config.channelPair.left + 1}/${config.channelPair.right + 1}; " +
                    "recording channels 1/2 instead."
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
            startWriter(framesPerRead)

            capture.record.startRecording()
            if (capture.record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("The input refused to start; another app may be using it")
            }
            _state.value = RecorderState.Recording(paused = false)

            val sourceChannels = capture.deliveredChannels
            val rawFloats = if (capture.isFloat) FloatArray(framesPerRead * sourceChannels) else FloatArray(0)
            val rawShorts = if (capture.isFloat) ShortArray(0) else ShortArray(framesPerRead * sourceChannels)
            val stereo = FloatArray(framesPerRead * 2)

            while (!stopRequested.get() && writerFailure == null) {
                val framesRead = readFrames(capture, rawFloats, rawShorts, framesPerRead)
                if (framesRead < 0) throw IllegalStateException(readErrorMessage(framesRead))
                if (framesRead == 0) continue

                if (paused.get()) {
                    _levels.value = Levels()
                    continue
                }

                deinterleave(capture, rawFloats, rawShorts, stereo, framesRead)

                val blockStartFrame = sessionFrames
                _levels.value = chain.process(stereo, framesRead)

                val detected = chain.transitionDetector.analyse(stereo, framesRead, blockStartFrame)
                if (pendingManualMarker) {
                    chain.transitionDetector.noteManualMarker(blockStartFrame)
                    pendingManualMarker = false
                    // A transition the detector already consumed on this block would otherwise be
                    // thrown away entirely rather than merely deferred.
                    if (detected >= 0) {
                        addMarkerInternal(detected * 1000L / config.sampleRate, "", automatic = true)
                    }
                } else if (detected >= 0) {
                    addMarkerInternal(detected * 1000L / config.sampleRate, "", automatic = true)
                }

                enqueue(stereo, framesRead)
                sessionFrames += framesRead

                _progress.update { it.copy(elapsedMs = sessionFrames * 1000L / config.sampleRate) }
            }

            _state.value = RecorderState.Stopping
        } catch (e: Throwable) {
            Log.e(TAG, "Capture failed", e)
            _state.value = RecorderState.Failed(e.message ?: "Recording failed")
        } finally {
            runCatching { capture?.record?.stop() }
            runCatching { capture?.record?.release() }
            captureFinished = true

            // The writer owns the files; wait for it to close them before declaring the session over.
            writerThread?.let { runCatching { it.join(WRITER_JOIN_MS) } }

            dsp = null
            _levels.value = Levels()
            running.set(false)

            val failure = writerFailure
            if (failure != null) {
                _state.value = RecorderState.Failed(failure)
            } else if (_state.value !is RecorderState.Failed) {
                _state.value = RecorderState.Idle
            }
        }
    }

    private fun enqueue(stereo: FloatArray, frames: Int) {
        val free = freeBlocks ?: return
        val queue = queuedBlocks ?: return
        // Waiting briefly is right: the AudioRecord buffer is deep enough to cover it, and dropping
        // audio should be a last resort rather than the first thing that happens under load.
        val block = free.poll(ENQUEUE_WAIT_MS, TimeUnit.MILLISECONDS)
        if (block == null) {
            Log.w(TAG, "Writer is not keeping up; dropped $frames frames")
            return
        }
        System.arraycopy(stereo, 0, block.data, 0, frames * 2)
        block.frames = frames
        if (!queue.offer(block)) {
            free.offer(block)
            Log.w(TAG, "Write queue full; dropped $frames frames")
        }
    }

    // ---- Writer thread --------------------------------------------------------------------

    private fun startWriter(framesPerRead: Int) {
        val blockCount = ((config.sampleRate * QUEUE_SECONDS) / framesPerRead).coerceIn(16, 512)
        val free = ArrayBlockingQueue<Block>(blockCount)
        val queued = ArrayBlockingQueue<Block>(blockCount)
        repeat(blockCount) { free.offer(Block(FloatArray(framesPerRead * 2))) }
        freeBlocks = free
        queuedBlocks = queued

        val worker = Thread({ runWriter(free, queued) }, "DeckRec-Writer")
        worker.priority = Thread.NORM_PRIORITY + 2
        writerThread = worker
        worker.start()
    }

    private fun runWriter(free: ArrayBlockingQueue<Block>, queued: ArrayBlockingQueue<Block>) {
        var current: Part? = null
        try {
            current = newPart(partIndex = 0, startSessionFrame = 0L)

            val splitFrames = if (config.autoSplitEnabled) {
                config.autoSplitMinutes.toLong() * 60L * config.sampleRate
            } else {
                Long.MAX_VALUE
            }
            val maxPartBytes = if (config.format.isWav) WavSink.MAX_DATA_BYTES else Long.MAX_VALUE

            while (true) {
                val block = queued.poll(WRITER_POLL_MS, TimeUnit.MILLISECONDS)
                if (block == null) {
                    if (captureFinished && queued.isEmpty()) break
                    continue
                }

                val part = current!!
                part.sink.write(block.data, block.frames)
                part.peaks.write(block.data, block.frames)
                part.frames += block.frames
                free.offer(block)

                _progress.update { it.copy(sizeBytes = part.sink.bytesOnDisk, partIndex = part.index) }

                if (part.frames >= splitFrames || part.sink.bytesOnDisk >= maxPartBytes) {
                    // Commit the finished part and drop the reference *before* opening the next
                    // one. If opening fails, the failure path must not be holding a part whose
                    // audio is already on disk and already in the library.
                    val nextIndex = part.index + 1
                    val nextStart = part.startSessionFrame + part.frames
                    current = null
                    finishPart(part)
                    current = newPart(nextIndex, nextStart)
                }
            }

            current?.let {
                current = null
                finishPart(it)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Writer failed", e)
            writerFailure = e.message ?: "Could not write to storage"
            // Salvage rather than discard: whatever was captured up to this point is kept.
            current?.let { runCatching { finishPart(it) } }
        }
    }

    private fun newPart(partIndex: Int, startSessionFrame: Long): Part {
        val target = store.newRecordingTarget(config, partIndex)
        return Part(
            index = partIndex,
            target = target,
            sink = openSink(target.audioFile),
            peaks = PeakFileWriter(target.peaksFile),
            startedAt = System.currentTimeMillis(),
            startSessionFrame = startSessionFrame,
        )
    }

    private fun finishPart(part: Part) {
        runCatching { part.peaks.close() }

        val snapshot = synchronized(markerLock) { markerList.toList() }
        val partStartMs = part.startSessionFrame * 1000L / config.sampleRate
        val partDurationMs = part.frames * 1000L / config.sampleRate
        // Half-open at the top: a marker landing exactly on a split boundary belongs to the part
        // that starts there, not to both.
        val partMarkers = snapshot
            .filter { it.positionMs >= partStartMs && it.positionMs < partStartMs + partDurationMs }
            .map { it.copy(positionMs = it.positionMs - partStartMs) }

        val usable = runCatching { part.sink.finish(partMarkers) }.getOrDefault(false)
        if (!usable && part.frames == 0L) {
            // Nothing was ever captured into this file; there is no audio to lose.
            runCatching { part.target.peaksFile.delete() }
            return
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
        if (!part.target.audioFile.isFile) {
            Log.w(TAG, "Part ${part.index} left no file on disk")
            return
        }
        // refreshLibrary = false: rescanning the whole library here would block the writer while
        // audio is still arriving. The listener refreshes off-thread instead.
        store.save(meta, refreshLibrary = false)
        onPartCompleted?.invoke(meta)
    }

    private fun openSink(file: File): AudioSink = when (config.format) {
        RecordingFormat.WAV_24 -> WavSink(file, config.sampleRate, 2, 24)
        RecordingFormat.WAV_16 -> WavSink(file, config.sampleRate, 2, 16)
        RecordingFormat.AAC -> AacSink(file, config.sampleRate, 2, config.aacBitrateKbps)
    }

    // ---- Capture plumbing -----------------------------------------------------------------

    private fun readFrames(
        capture: Capture,
        floats: FloatArray,
        shorts: ShortArray,
        framesPerRead: Int,
    ): Int {
        val samples = framesPerRead * capture.deliveredChannels
        val read = if (capture.isFloat) {
            capture.record.read(floats, 0, samples, AudioRecord.READ_BLOCKING)
        } else {
            capture.record.read(shorts, 0, samples, AudioRecord.READ_BLOCKING)
        }
        return if (read < 0) read else read / capture.deliveredChannels
    }

    /** Pulls the selected stereo pair out of whatever the device handed us. */
    private fun deinterleave(
        capture: Capture,
        floats: FloatArray,
        shorts: ShortArray,
        out: FloatArray,
        frames: Int,
    ) {
        val channels = capture.deliveredChannels
        val left = capture.leftIndex
        val right = capture.rightIndex
        var outIndex = 0
        var base = 0
        if (capture.isFloat) {
            repeat(frames) {
                out[outIndex] = floats[base + left]
                out[outIndex + 1] = floats[base + right]
                outIndex += 2
                base += channels
            }
        } else {
            repeat(frames) {
                out[outIndex] = shorts[base + left] * SHORT_TO_FLOAT
                out[outIndex + 1] = shorts[base + right] * SHORT_TO_FLOAT
                outIndex += 2
                base += channels
            }
        }
    }

    /**
     * Opens the best stream the device will actually give us, most capable first.
     *
     * Multi-channel USB mixers are the interesting case: the master bus is rarely channels 1/2, so
     * we first ask the HAL for exactly the requested pair via a channel index mask, and only fall
     * back to taking every channel and slicing it ourselves if that is refused.
     */
    private fun openCapture(config: RecorderConfig): Capture? {
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
    ): Capture? {
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

        if (deviceInfo != null && !record.setPreferredDevice(deviceInfo)) {
            Log.w(TAG, "Could not pin the stream to ${deviceInfo.productName}; using the default input")
        }

        val delivered = record.channelCount.coerceAtLeast(1)
        val left = attempt.leftIndex.coerceIn(0, delivered - 1)
        val right = attempt.rightIndex.coerceIn(0, delivered - 1)

        return Capture(
            record = record,
            deliveredChannels = delivered,
            isFloat = encoding == AudioFormat.ENCODING_PCM_FLOAT,
            leftIndex = left,
            rightIndex = right,
            framesPerRead = framesPerRead,
            honouredRequestedPair = !wantsSpecificPair || attempt.selectsChannels,
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

    private fun readErrorMessage(code: Int): String = when (code) {
        AudioRecord.ERROR_INVALID_OPERATION -> "The audio stream stopped unexpectedly"
        AudioRecord.ERROR_BAD_VALUE -> "The device rejected the capture format"
        AudioRecord.ERROR_DEAD_OBJECT -> "The input was disconnected"
        else -> "Audio read failed ($code)"
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

    private class Capture(
        val record: AudioRecord,
        val deliveredChannels: Int,
        val isFloat: Boolean,
        val leftIndex: Int,
        val rightIndex: Int,
        val framesPerRead: Int,
        val honouredRequestedPair: Boolean,
    )

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
        const val SHORT_TO_FLOAT = 1f / 32768f
        const val STOP_JOIN_MS = 8000L
        const val WRITER_JOIN_MS = 10_000L
        const val WRITER_POLL_MS = 50L
        const val ENQUEUE_WAIT_MS = 500L

        /** Seconds of audio the hardware buffer holds. */
        const val CAPTURE_BUFFER_SECONDS = 2

        /** Seconds of audio the hand-off queue can absorb while storage stalls. */
        const val QUEUE_SECONDS = 6
    }
}
