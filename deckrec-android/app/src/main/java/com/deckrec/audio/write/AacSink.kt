package com.deckrec.audio.write

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import com.deckrec.data.Marker
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * AAC-LC encoder writing an MPEG-4 (.m4a) container via [MediaCodec] and [MediaMuxer].
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
        while (offset < needed) {
            val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
            if (inputIndex < 0) {
                drain(endOfStream = false)
                continue
            }
            val inputBuffer: ByteBuffer = codec.getInputBuffer(inputIndex) ?: continue
            inputBuffer.clear()
            inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
            val chunk = minOf(inputBuffer.remaining(), needed - offset)
            inputBuffer.put(pcmScratch, offset, chunk)

            val framesInChunk = chunk / (2 * channels)
            val presentationTimeUs = framesWritten * 1_000_000L / sampleRate
            codec.queueInputBuffer(inputIndex, 0, chunk, presentationTimeUs, 0)

            framesWritten += framesInChunk
            offset += chunk
            drain(endOfStream = false)
        }
    }

    override fun finish(markers: List<Marker>) {
        if (finished) return
        finished = true
        try {
            signalEndOfStream()
            drain(endOfStream = true)
        } catch (e: Exception) {
            // Fall through to releasing everything; a partial file still beats no file.
        } finally {
            release()
        }
    }

    override fun abort() {
        finished = true
        try {
            release()
        } finally {
            file.delete()
        }
    }

    private fun signalEndOfStream() {
        var attempts = 0
        while (attempts < END_OF_STREAM_ATTEMPTS) {
            val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
            if (inputIndex >= 0) {
                val presentationTimeUs = framesWritten * 1_000_000L / sampleRate
                codec.queueInputBuffer(
                    inputIndex,
                    0,
                    0,
                    presentationTimeUs,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                )
                return
            }
            drain(endOfStream = false)
            attempts++
        }
    }

    private fun drain(endOfStream: Boolean) {
        while (true) {
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
        if (muxerStarted) runCatching { muxer.stop() }
        runCatching { muxer.release() }
    }

    private companion object {
        const val MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_AAC
        const val TIMEOUT_US = 10_000L
        const val MAX_INPUT_SIZE = 32 * 1024
        const val END_OF_STREAM_ATTEMPTS = 50
    }
}
