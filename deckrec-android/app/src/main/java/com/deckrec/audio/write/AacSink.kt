package com.deckrec.audio.write

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import com.deckrec.data.Marker
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * AAC-LC encoder writing an MPEG-4 (.m4a) container via [MediaCodec] and [MediaMuxer].
 *
 * Every loop that waits on the codec is bounded by a deadline. An encoder that stops handing back
 * buffers is a real failure mode on stressed devices, and an unbounded wait here would hang the
 * stop button forever — leaving the muxer un-stopped and the file without a `moov` atom, which is
 * to say unplayable by anything.
 *
 * Markers are not embedded here — MPEG-4 has no cue-point atom that DJ software reads — so they
 * live in the JSON sidecar alongside the file, which is where the app reads them from anyway.
 */
class AacSink(
    override val file: File,
    private val sampleRate: Int,
    private val channels: Int,
    bitrateKbps: Int,
) : AudioSink {

    private val codec: MediaCodec = MediaCodec.createEncoderByType(MIME_TYPE)
    private val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val bufferInfo = MediaCodec.BufferInfo()

    private var trackIndex = -1
    private var muxerStarted = false
    private var pcmScratch = ByteArray(0)
    private var encodedBytes = 0L
    private var muxedSamples = 0L
    private var finished = false

    override var framesWritten: Long = 0L
        private set

    override val bytesOnDisk: Long
        get() = encodedBytes

    init {
        val format = MediaFormat.createAudioFormat(MIME_TYPE, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateKbps * 1000)
            setInteger(MediaFormat.KEY_PCM_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    override fun write(buffer: FloatArray, frames: Int) {
        if (finished || frames <= 0) return
        val samples = frames * channels
        val needed = samples * 2
        if (pcmScratch.size < needed) pcmScratch = ByteArray(needed)

        var out = 0
        for (i in 0 until samples) {
            val sample = buffer[i]
            val clamped = if (sample > 1f) 1f else if (sample < -1f) -1f else sample
            val value = Math.round(clamped * 32767f)
            pcmScratch[out] = (value and 0xFF).toByte()
            pcmScratch[out + 1] = ((value shr 8) and 0xFF).toByte()
            out += 2
        }

        var offset = 0
        val deadline = System.nanoTime() + WRITE_TIMEOUT_NANOS
        while (offset < needed) {
            if (System.nanoTime() > deadline) {
                Log.w(TAG, "Encoder stalled; dropped ${needed - offset} bytes of this block")
                return
            }
            val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
            if (inputIndex < 0) {
                drain(endOfStream = false)
                continue
            }
            val inputBuffer: ByteBuffer? = codec.getInputBuffer(inputIndex)
            if (inputBuffer == null) {
                // Hand the index straight back rather than leaking it.
                codec.queueInputBuffer(inputIndex, 0, 0, presentationTimeUs(), 0)
                continue
            }
            inputBuffer.clear()
            inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
            val chunk = minOf(inputBuffer.remaining(), needed - offset)
            inputBuffer.put(pcmScratch, offset, chunk)

            val framesInChunk = chunk / (2 * channels)
            codec.queueInputBuffer(inputIndex, 0, chunk, presentationTimeUs(), 0)

            framesWritten += framesInChunk
            offset += chunk
            drain(endOfStream = false)
        }
    }

    override fun finish(markers: List<Marker>): Boolean {
        if (finished) return muxedSamples > 0
        finished = true
        try {
            val queued = signalEndOfStream()
            drain(endOfStream = queued)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to drain the encoder cleanly", e)
        } finally {
            release()
        }

        // A muxer that was never started, or started but never given a sample, leaves a file with
        // no moov atom. Better no file than one the library lists and no player will open.
        val usable = muxedSamples > 0 && file.length() > 0
        if (!usable) {
            Log.w(TAG, "AAC encoder produced no samples; discarding ${file.name}")
            file.delete()
        }
        return usable
    }

    override fun abort() {
        finished = true
        try {
            release()
        } finally {
            file.delete()
        }
    }

    private fun presentationTimeUs(): Long = framesWritten * 1_000_000L / sampleRate

    /** @return true if the end-of-stream buffer was actually queued. */
    private fun signalEndOfStream(): Boolean {
        val deadline = System.nanoTime() + EOS_TIMEOUT_NANOS
        while (System.nanoTime() < deadline) {
            val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
            if (inputIndex >= 0) {
                codec.queueInputBuffer(
                    inputIndex,
                    0,
                    0,
                    presentationTimeUs(),
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                )
                return true
            }
            drain(endOfStream = false)
        }
        Log.w(TAG, "Encoder never accepted the end-of-stream buffer")
        return false
    }

    private fun drain(endOfStream: Boolean) {
        val deadline = System.nanoTime() + DRAIN_TIMEOUT_NANOS
        while (true) {
            if (endOfStream && System.nanoTime() > deadline) {
                Log.w(TAG, "Gave up waiting for end-of-stream from the encoder")
                return
            }
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                }

                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                }

                outputIndex >= 0 -> {
                    val outputBuffer = codec.getOutputBuffer(outputIndex)
                    if (outputBuffer != null && muxerStarted &&
                        bufferInfo.size > 0 &&
                        bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                    ) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, outputBuffer, bufferInfo)
                        encodedBytes += bufferInfo.size
                        muxedSamples++
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    private fun release() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        // stop() throws if the muxer was started but never written to; that is exactly the case
        // the caller is told about via finish()'s return value.
        if (muxerStarted) runCatching { muxer.stop() }
        runCatching { muxer.release() }
    }

    private companion object {
        const val TAG = "AacSink"
        const val MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_AAC
        const val TIMEOUT_US = 10_000L
        const val MAX_INPUT_SIZE = 32 * 1024
        const val EOS_TIMEOUT_NANOS = 2_000_000_000L
        const val DRAIN_TIMEOUT_NANOS = 3_000_000_000L
        const val WRITE_TIMEOUT_NANOS = 2_000_000_000L
    }
}
