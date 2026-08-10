package com.deckrec.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures from the selected input, runs the record bus, and streams the result to disk.
 *
 * All audio work happens on one dedicated urgent-priority thread. The UI only ever sees the
 * immutable snapshots published through the state flows, which keeps the read loop free of locks
 * on the hot path — the one exception is the marker list, which is touched rarely and guarded.
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

    /** Invoked on the audio thread whenever a file is closed, including auto-split parts. */
    var onPartCompleted: ((RecordingMeta) -> Unit)? = null

    private val running = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)

    @Volatile
    private var thread: Thread? = null

    @Volatile
    private var config: RecorderConfig = RecorderConfig()

    @Volatile
    private var dsp: DspChain? = null

    @Volatile
    private var pendingManualMarker = false

    private val markerLock = Any()
    private val markerList = mutableListOf<Marker>()

    /** Frames written across all parts of the current session. */
    @Volatile
    private var sessionFrames = 0L

    val isRecording: Boolean get() = running.get()

    fun start(config: RecorderConfig): Boolean {
        if (running.get()) return false
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            _state.value = RecorderState.Failed("Microphone permission is required to record")
            return false
        }

        this.config = config
        stopRequested.set(false)
        paused.set(false)
        sessionFrames = 0L
        synchronized(markerLock) { markerList.clear() }
        _markers.value = emptyList()
        _progress.value = RecordingProgress()
        _state.value = RecorderState.Starting

        running.set(true)
        val worker = Thread({ runLoop() }, "DeckRec-Audio")
        worker.priority = Thread.MAX_PRIORITY
        thread = worker
        worker.start()
        return true
    }

    fun stop() {
        stopRequested.set(true)
        thread?.let { worker ->
            runCatching { worker.join(STOP_JOIN_MS) }
        }
        thread = null
    }

    fun setPaused(value: Boolean) {
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

    private fun addMarkerInternal(positionMs: Long, label: String, automatic: Boolean) {
        val marker = Marker(
            id = UUID.randomUUID().toString(),
            positionMs = positionMs,
            label = label,
            automatic = automatic,
        )
        val snapshot = synchronized(markerLock) {
            markerList.add(marker)
            markerList.toList()
        }
        _markers.value = snapshot
        _progress.value = _progress.value.copy(markerCount = snapshot.size)
    }

    private fun runLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

        var capture: Capture? = null
        var part: Part? = null

        try {
            capture = openCapture(config)
                ?: throw IllegalStateException("No usable capture format on this input")

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

            var current = newPart(partIndex = 0)
            part = current

            capture.record.startRecording()
            if (capture.record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("The input refused to start; another app may be using it")
            }
            _state.value = RecorderState.Recording(paused = false)

            val framesPerRead = capture.framesPerRead
            val sourceChannels = capture.deliveredChannels
            val rawFloats = if (capture.isFloat) FloatArray(framesPerRead * sourceChannels) else FloatArray(0)
            val rawShorts = if (capture.isFloat) ShortArray(0) else ShortArray(framesPerRead * sourceChannels)
            val stereo = FloatArray(framesPerRead * 2)

            val splitFrames = if (config.autoSplitEnabled) {
                config.autoSplitMinutes.toLong() * 60L * config.sampleRate
            } else {
                Long.MAX_VALUE
            }
            val maxPartBytes = if (config.format.isWav) WavSink.MAX_DATA_BYTES else Long.MAX_VALUE

            while (!stopRequested.get()) {
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
                } else if (detected >= 0) {
                    addMarkerInternal(
                        positionMs = detected * 1000L / config.sampleRate,
                        label = "",
                        automatic = true,
                    )
                }

                current.sink.write(stereo, framesRead)
                current.peaks.write(stereo, framesRead)

                sessionFrames += framesRead
                current.frames += framesRead

                _progress.value = _progress.value.copy(
                    elapsedMs = sessionFrames * 1000L / config.sampleRate,
                    sizeBytes = current.sink.bytesOnDisk,
                    partIndex = current.index,
                )

                val shouldSplit =
                    current.frames >= splitFrames || current.sink.bytesOnDisk >= maxPartBytes
                if (shouldSplit) {
                    finishPart(current)
                    current = newPart(partIndex = current.index + 1)
                    part = current
                }
            }

            _state.value = RecorderState.Stopping
            finishPart(current)
            part = null
            _state.value = RecorderState.Idle
        } catch (e: Throwable) {
            Log.e(TAG, "Recording failed", e)
            part?.let { open ->
                runCatching { open.peaks.abort() }
                runCatching { open.sink.abort() }
            }
            _state.value = RecorderState.Failed(e.message ?: "Recording failed")
        } finally {
            runCatching { capture?.record?.stop() }
            runCatching { capture?.record?.release() }
            dsp = null
            running.set(false)
            _levels.value = Levels()
        }
    }

    private fun newPart(partIndex: Int): Part {
        val target = store.newRecordingTarget(config, partIndex)
        return Part(
            index = partIndex,
            target = target,
            sink = openSink(target.audioFile),
            peaks = PeakFileWriter(target.peaksFile),
            startedAt = System.currentTimeMillis(),
        )
    }

    private fun finishPart(part: Part) {
        part.peaks.close()

        val snapshot = synchronized(markerLock) { markerList.toList() }
        // Markers are kept against the whole-session timeline; rebase them onto this part.
        val partStartMs = (sessionFrames - part.frames) * 1000L / config.sampleRate
        val partDurationMs = part.frames * 1000L / config.sampleRate
        val partMarkers = snapshot
            .filter { it.positionMs in partStartMs..(partStartMs + partDurationMs) }
            .map { it.copy(positionMs = it.positionMs - partStartMs) }

        part.sink.finish(partMarkers)

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
        store.save(meta)
        onPartCompleted?.invoke(meta)
    }

    /** One output file of a session: everything auto-split has to swap out together. */
    private class Part(
        val index: Int,
        val target: RecordingStore.RecordingTarget,
        val sink: AudioSink,
        val peaks: PeakFileWriter,
        val startedAt: Long,
        var frames: Long = 0L,
    )

    private fun openSink(file: File): AudioSink = when (config.format) {
        RecordingFormat.WAV_24 -> WavSink(file, config.sampleRate, 2, 24)
        RecordingFormat.WAV_16 -> WavSink(file, config.sampleRate, 2, 16)
        RecordingFormat.AAC -> AacSink(file, config.sampleRate, 2, config.aacBitrateKbps)
    }

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

        val attempts = buildList {
            if (sourceChannels > 2) {
                val pairMask = pair.indexMask
                add(Attempt.IndexMask(pairMask, AudioFormat.ENCODING_PCM_FLOAT, 0, 1))
                add(Attempt.IndexMask(pairMask, AudioFormat.ENCODING_PCM_16BIT, 0, 1))
                val allMask = (1 shl sourceChannels) - 1
                add(Attempt.IndexMask(allMask, AudioFormat.ENCODING_PCM_FLOAT, pair.left, pair.right))
                add(Attempt.IndexMask(allMask, AudioFormat.ENCODING_PCM_16BIT, pair.left, pair.right))
            }
            add(Attempt.PositionMask(AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_FLOAT))
            add(Attempt.PositionMask(AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT))
            add(Attempt.PositionMask(AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT))
            add(Attempt.PositionMask(AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT))
        }

        for (attempt in attempts) {
            val capture = tryOpen(attempt, config.sampleRate, source, deviceInfo)
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
        deviceInfo: android.media.AudioDeviceInfo?,
    ): Capture? {
        val encoding = attempt.encoding
        val channels = attempt.channelCount
        val bytesPerSample = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2

        val legacyMask = if (channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, legacyMask, encoding)
        if (minBuffer <= 0) return null

        val framesPerRead = FRAMES_PER_READ
        val requestedBytes = maxOf(minBuffer * 2, framesPerRead * channels * bytesPerSample * 4)

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

        val record = try {
            AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(format)
                .setBufferSizeInBytes(requestedBytes)
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "Capture attempt $attempt rejected: ${e.message}")
            return null
        }

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
        )
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

        data class IndexMask(
            val mask: Int,
            override val encoding: Int,
            override val leftIndex: Int,
            override val rightIndex: Int,
        ) : Attempt {
            override val channelCount: Int get() = Integer.bitCount(mask)
        }

        data class PositionMask(val mask: Int, override val encoding: Int) : Attempt {
            override val channelCount: Int
                get() = if (mask == AudioFormat.CHANNEL_IN_MONO) 1 else 2
            override val leftIndex: Int get() = 0
            override val rightIndex: Int get() = if (channelCount == 1) 0 else 1
        }
    }

    private class Capture(
        val record: AudioRecord,
        val deliveredChannels: Int,
        val isFloat: Boolean,
        val leftIndex: Int,
        val rightIndex: Int,
        val framesPerRead: Int,
    )

    private companion object {
        const val TAG = "RecordingEngine"
        const val FRAMES_PER_READ = 1024
        const val SHORT_TO_FLOAT = 1f / 32768f
        const val STOP_JOIN_MS = 4000L
    }
}
