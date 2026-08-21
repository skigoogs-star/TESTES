package com.deckrec.audio

import android.media.AudioRecord
import com.deckrec.usb.ChannelPair
import com.deckrec.usb.host.PcmDecode
import com.deckrec.usb.host.UsbIsoSession

/** A capture failure carrying a message already fit to show a user. */
class CaptureException(message: String) : Exception(message)

/**
 * Where the engine gets audio from, whichever way it is being obtained.
 *
 * The point of this interface is that everything downstream of it — the DSP chain, the limiter, the
 * WAV and AAC writers, the marker timeline, the foreground service, the whole UI — never learns
 * which implementation is running. One delivers what the platform routes through `AudioRecord`; the
 * other drives a USB endpoint directly because the platform refuses to route it at all.
 *
 * It deliberately hands back the *selected stereo pair* rather than raw frames. That keeps sample
 * formats, channel counts and index juggling out of the engine entirely, and it lets the USB path
 * decode only the two channels anyone wants instead of converting twelve and discarding ten.
 */
internal interface CaptureSource : AutoCloseable {

    /** The block size the engine sizes its buffers and writer queue around. */
    val framesPerRead: Int

    /** False when the requested channel pair was unavailable and 1/2 was substituted. */
    val honouredRequestedPair: Boolean

    /** False when the stream could not be tied to the chosen device and may be the wrong input. */
    val pinnedToDevice: Boolean

    /** Begins delivery. Throws [CaptureException] if the input will not start. */
    fun start()

    /**
     * Fills [dest] with interleaved stereo frames, up to [maxFrames].
     *
     * Returns the number of frames written, or 0 if none arrived before the read timed out — a
     * timeout is not an error and the caller should simply ask again. Throws [CaptureException]
     * when the stream has failed for good.
     */
    fun readStereo(dest: FloatArray, maxFrames: Int): Int

    /** Idempotent, and must never throw: it runs on the failure path too. */
    override fun close()
}

/**
 * Capture through the platform, for any input Android is willing to route.
 *
 * Holds its own scratch buffers because the shape of them depends on what the device agreed to
 * deliver — float or 16-bit, one channel or twelve — and none of that is the engine's business.
 */
internal class AudioRecordSource(
    private val record: AudioRecord,
    private val deliveredChannels: Int,
    private val isFloat: Boolean,
    private val leftIndex: Int,
    private val rightIndex: Int,
    override val framesPerRead: Int,
    override val honouredRequestedPair: Boolean,
    override val pinnedToDevice: Boolean,
) : CaptureSource {

    private val rawFloats = if (isFloat) FloatArray(framesPerRead * deliveredChannels) else FloatArray(0)
    private val rawShorts = if (isFloat) ShortArray(0) else ShortArray(framesPerRead * deliveredChannels)

    override fun start() {
        record.startRecording()
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            throw CaptureException(
                "The input refused to start — another app may be holding it. Close other " +
                    "recording apps, or pick a different input."
            )
        }
    }

    override fun readStereo(dest: FloatArray, maxFrames: Int): Int {
        val frames = minOf(maxFrames, framesPerRead)
        val samples = frames * deliveredChannels
        val read = if (isFloat) {
            record.read(rawFloats, 0, samples, AudioRecord.READ_BLOCKING)
        } else {
            record.read(rawShorts, 0, samples, AudioRecord.READ_BLOCKING)
        }
        if (read < 0) throw CaptureException(errorMessage(read))

        val framesRead = read / deliveredChannels
        var out = 0
        var base = 0
        if (isFloat) {
            repeat(framesRead) {
                dest[out] = rawFloats[base + leftIndex]
                dest[out + 1] = rawFloats[base + rightIndex]
                out += 2
                base += deliveredChannels
            }
        } else {
            repeat(framesRead) {
                dest[out] = rawShorts[base + leftIndex] * SHORT_TO_FLOAT
                dest[out + 1] = rawShorts[base + rightIndex] * SHORT_TO_FLOAT
                out += 2
                base += deliveredChannels
            }
        }
        return framesRead
    }

    override fun close() {
        runCatching { record.stop() }
        runCatching { record.release() }
    }

    private fun errorMessage(code: Int): String = when (code) {
        AudioRecord.ERROR_INVALID_OPERATION -> "The audio stream stopped unexpectedly"
        AudioRecord.ERROR_BAD_VALUE -> "The device rejected the capture format"
        AudioRecord.ERROR_DEAD_OBJECT -> "The input was disconnected"
        else -> "Audio read failed ($code)"
    }

    private companion object {
        const val SHORT_TO_FLOAT = 1f / 32768f
    }
}

/**
 * Capture straight from a USB endpoint, for hardware the platform will not route.
 *
 * The session is already streaming by the time this exists — it is opened where the input is
 * chosen, not here, because opening it needs a permission dialog and the engine's threads are
 * joined on a timeout. This only reads.
 *
 * It also does not own the session. A monitor and a recording hand over one to the other, and a
 * monitor thread that wakes late must not be able to close the stream the recording is now using.
 */
internal class UsbCaptureSource(
    private val session: UsbIsoSession,
    pair: ChannelPair,
    override val framesPerRead: Int,
) : CaptureSource {

    private val channels = session.profile.channels
    private val bytesPerFrame = session.bytesPerFrame
    private val buffer = ByteArray(framesPerRead * bytesPerFrame)

    private val left = pair.left.coerceIn(0, channels - 1)
    private val right = pair.right.coerceIn(0, channels - 1)

    override val honouredRequestedPair: Boolean = pair.left == left && pair.right == right
    override val pinnedToDevice: Boolean = true

    override fun start() = Unit

    override fun readStereo(dest: FloatArray, maxFrames: Int): Int {
        val wanted = minOf(maxFrames, framesPerRead) * bytesPerFrame
        val read = session.read(buffer, 0, wanted, READ_TIMEOUT_MS)
        if (read < 0) throw CaptureException("The mixer was disconnected.")
        if (read == 0) return 0

        val frames = read / bytesPerFrame
        PcmDecode.decodePair(
            src = buffer,
            srcOffset = 0,
            dest = dest,
            destOffset = 0,
            frames = frames,
            channels = channels,
            left = left,
            right = right,
            encoding = session.profile.encoding,
        )
        return frames
    }

    /** Stops reading only. The session outlives this, and is closed by whoever opened it. */
    override fun close() = Unit

    private companion object {
        /**
         * Comfortably shorter than the engine's three-second joins, so a thread waiting here is
         * never the reason a stop takes too long.
         */
        const val READ_TIMEOUT_MS = 250
    }
}
