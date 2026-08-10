package com.deckrec.audio.write

import com.deckrec.data.Marker
import java.io.File
import java.io.RandomAccessFile

/**
 * Streaming RIFF/WAVE writer for 16- and 24-bit PCM.
 *
 * Sizes are written as placeholders up front and patched on close, so a recording that is killed
 * mid-set still leaves a file on disk; [repairTruncated] rebuilds the header for exactly that case.
 * Markers are emitted as a real `cue ` chunk plus `LIST/adtl` labels, which means the cue points
 * show up in Rekordbox, Audacity and every other DAW rather than only inside this app.
 */
class WavSink(
    override val file: File,
    private val sampleRate: Int,
    private val channels: Int,
    private val bitsPerSample: Int,
) : AudioSink {

    private val bytesPerSample = bitsPerSample / 8
    private val frameBytes = bytesPerSample * channels
    private val raf = RandomAccessFile(file, "rw")

    private var scratch = ByteArray(0)
    private var dataBytes = 0L

    override var framesWritten: Long = 0L
        private set

    override val bytesOnDisk: Long
        get() = HEADER_BYTES + dataBytes

    init {
        require(bitsPerSample == 16 || bitsPerSample == 24) { "Unsupported bit depth $bitsPerSample" }
        raf.setLength(0)
        writeHeader(dataChunkBytes = 0)
    }

    override fun write(buffer: FloatArray, frames: Int) {
        if (frames <= 0) return
        val needed = frames * frameBytes
        if (scratch.size < needed) scratch = ByteArray(needed)

        var out = 0
        var i = 0
        val total = frames * channels
        if (bitsPerSample == 24) {
            while (i < total) {
                val v = quantise24(buffer[i])
                scratch[out] = (v and 0xFF).toByte()
                scratch[out + 1] = ((v shr 8) and 0xFF).toByte()
                scratch[out + 2] = ((v shr 16) and 0xFF).toByte()
                out += 3
                i++
            }
        } else {
            while (i < total) {
                val v = quantise16(buffer[i])
                scratch[out] = (v and 0xFF).toByte()
                scratch[out + 1] = ((v shr 8) and 0xFF).toByte()
                out += 2
                i++
            }
        }

        raf.write(scratch, 0, needed)
        dataBytes += needed
        framesWritten += frames
    }

    override fun finish(markers: List<Marker>) {
        try {
            raf.seek(HEADER_BYTES.toLong() + dataBytes)
            val extraBytes = writeMarkerChunks(markers)
            writeHeader(dataChunkBytes = dataBytes, extraTrailingBytes = extraBytes)
        } finally {
            raf.close()
        }
    }

    override fun abort() {
        try {
            raf.close()
        } finally {
            file.delete()
        }
    }

    /** Rewrites the header and appends markers without holding the stream open. */
    private fun writeHeader(dataChunkBytes: Long, extraTrailingBytes: Long = 0L) {
        val position = raf.filePointer
        raf.seek(0)
        val byteRate = sampleRate.toLong() * channels * bytesPerSample
        val riffSize = HEADER_BYTES - 8 + dataChunkBytes + extraTrailingBytes

        raf.write("RIFF".toByteArray(Charsets.US_ASCII))
        writeIntLe(riffSize.toInt())
        raf.write("WAVE".toByteArray(Charsets.US_ASCII))

        raf.write("fmt ".toByteArray(Charsets.US_ASCII))
        writeIntLe(16)
        writeShortLe(PCM_FORMAT)
        writeShortLe(channels)
        writeIntLe(sampleRate)
        writeIntLe(byteRate.toInt())
        writeShortLe(channels * bytesPerSample)
        writeShortLe(bitsPerSample)

        raf.write("data".toByteArray(Charsets.US_ASCII))
        writeIntLe(dataChunkBytes.toInt())

        if (position > HEADER_BYTES) raf.seek(position)
    }

    /** Returns the number of bytes appended after the data chunk. */
    private fun writeMarkerChunks(markers: List<Marker>): Long {
        if (markers.isEmpty()) return 0L
        val sorted = markers.sortedBy { it.positionMs }
        val start = raf.filePointer

        raf.write("cue ".toByteArray(Charsets.US_ASCII))
        writeIntLe(4 + sorted.size * CUE_POINT_BYTES)
        writeIntLe(sorted.size)
        sorted.forEachIndexed { index, marker ->
            val sampleOffset = (marker.positionMs * sampleRate / 1000L).toInt()
            writeIntLe(index + 1)          // dwName / cue id
            writeIntLe(sampleOffset)       // dwPosition
            raf.write("data".toByteArray(Charsets.US_ASCII))
            writeIntLe(0)                  // dwChunkStart
            writeIntLe(0)                  // dwBlockStart
            writeIntLe(sampleOffset)       // dwSampleOffset
        }

        val labels = sorted.mapIndexed { index, marker ->
            (index + 1) to marker.displayLabel(index).toByteArray(Charsets.UTF_8)
        }
        // "adtl" + for each label: "labl" + size + cueId + text + NUL (+ pad to even)
        val adtlBytes = 4 + labels.sumOf { (_, text) ->
            val payload = 4 + text.size + 1
            8 + payload + (payload % 2)
        }
        raf.write("LIST".toByteArray(Charsets.US_ASCII))
        writeIntLe(adtlBytes)
        raf.write("adtl".toByteArray(Charsets.US_ASCII))
        labels.forEach { (cueId, text) ->
            val payload = 4 + text.size + 1
            raf.write("labl".toByteArray(Charsets.US_ASCII))
            writeIntLe(payload)
            writeIntLe(cueId)
            raf.write(text)
            raf.write(0)
            if (payload % 2 != 0) raf.write(0)
        }

        return raf.filePointer - start
    }

    private fun writeIntLe(value: Int) {
        raf.write(value and 0xFF)
        raf.write((value ushr 8) and 0xFF)
        raf.write((value ushr 16) and 0xFF)
        raf.write((value ushr 24) and 0xFF)
    }

    private fun writeShortLe(value: Int) {
        raf.write(value and 0xFF)
        raf.write((value ushr 8) and 0xFF)
    }

    companion object {
        const val HEADER_BYTES = 44
        private const val PCM_FORMAT = 1

        /** RIFF sizes are unsigned 32-bit, so a WAV cannot exceed 4 GiB. */
        const val MAX_DATA_BYTES = 0xF0000000L
        private const val CUE_POINT_BYTES = 24

        private fun quantise24(sample: Float): Int {
            val clamped = if (sample > 1f) 1f else if (sample < -1f) -1f else sample
            return Math.round(clamped * 8388607f)
        }

        private fun quantise16(sample: Float): Int {
            val clamped = if (sample > 1f) 1f else if (sample < -1f) -1f else sample
            return Math.round(clamped * 32767f)
        }

        /** The parts of a WAV header this app needs to describe a file it did not just write. */
        data class WavInfo(
            val sampleRate: Int,
            val channels: Int,
            val bitsPerSample: Int,
            val dataBytes: Long,
        ) {
            val durationMs: Long
                get() {
                    val frameBytes = channels * (bitsPerSample / 8)
                    if (frameBytes <= 0 || sampleRate <= 0) return 0
                    return dataBytes / frameBytes * 1000L / sampleRate
                }
        }

        /** Reads a canonical 44-byte PCM header; null if the file is not one. */
        fun readHeader(file: File): WavInfo? = runCatching {
            RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < HEADER_BYTES) return null
                val header = ByteArray(HEADER_BYTES)
                raf.readFully(header)
                if (String(header, 0, 4, Charsets.US_ASCII) != "RIFF") return null
                if (String(header, 8, 4, Charsets.US_ASCII) != "WAVE") return null
                val declaredData = readIntLe(header, 40).toLong() and 0xFFFFFFFFL
                val actualData = raf.length() - HEADER_BYTES
                WavInfo(
                    channels = readShortLe(header, 22),
                    sampleRate = readIntLe(header, 24),
                    bitsPerSample = readShortLe(header, 34),
                    // A file killed before finish() ran still declares zero; trust the length then.
                    dataBytes = if (declaredData in 1..actualData) declaredData else actualData,
                )
            }
        }.getOrNull()

        private fun readIntLe(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 3].toInt() and 0xFF) shl 24)

        private fun readShortLe(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

        /**
         * Patches the header of a WAV whose writer was killed before [finish] ran, using the file
         * length to work out how much audio actually made it to disk.
         */
        fun repairTruncated(file: File): Boolean {
            if (!file.isFile || file.length() <= HEADER_BYTES) return false
            return try {
                RandomAccessFile(file, "rw").use { raf ->
                    val dataBytes = raf.length() - HEADER_BYTES
                    raf.seek(4)
                    val riffSize = (raf.length() - 8).toInt()
                    raf.write(byteArrayOf(
                        (riffSize and 0xFF).toByte(),
                        ((riffSize ushr 8) and 0xFF).toByte(),
                        ((riffSize ushr 16) and 0xFF).toByte(),
                        ((riffSize ushr 24) and 0xFF).toByte(),
                    ))
                    raf.seek(40)
                    val size = dataBytes.toInt()
                    raf.write(byteArrayOf(
                        (size and 0xFF).toByte(),
                        ((size ushr 8) and 0xFF).toByte(),
                        ((size ushr 16) and 0xFF).toByte(),
                        ((size ushr 24) and 0xFF).toByte(),
                    ))
                }
                true
            } catch (e: Exception) {
                false
            }
        }
    }
}
