package com.deckrec.audio.write

import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

/**
 * Waveform overview written alongside the audio while it records.
 *
 * Scanning a two-hour 24-bit WAV to draw a waveform means reading two gigabytes, so instead the
 * recorder emits one min/max pair per bucket as it goes. The result is a few kilobytes that the
 * detail screen can draw instantly.
 */
class PeakFileWriter(
    private val file: File,
    private val samplesPerBucket: Int = DEFAULT_SAMPLES_PER_BUCKET,
) {
    private val stream = BufferedOutputStream(FileOutputStream(file))
    private var bucketMin = 0f
    private var bucketMax = 0f
    private var bucketFrames = 0
    private var headerWritten = false

    fun write(buffer: FloatArray, frames: Int) {
        if (!headerWritten) {
            writeHeader()
            headerWritten = true
        }
        var i = 0
        repeat(frames) {
            val mono = (buffer[i] + buffer[i + 1]) * 0.5f
            if (mono < bucketMin) bucketMin = mono
            if (mono > bucketMax) bucketMax = mono
            bucketFrames++
            if (bucketFrames >= samplesPerBucket) flushBucket()
            i += 2
        }
    }

    fun close() {
        if (!headerWritten) writeHeader()
        if (bucketFrames > 0) flushBucket()
        runCatching { stream.flush() }
        runCatching { stream.close() }
    }

    fun abort() {
        runCatching { stream.close() }
        file.delete()
    }

    private fun writeHeader() {
        stream.write(MAGIC.toByteArray(Charsets.US_ASCII))
        stream.write(VERSION)
        writeIntLe(samplesPerBucket)
    }

    private fun flushBucket() {
        stream.write(quantise(bucketMin))
        stream.write(quantise(bucketMax))
        bucketMin = 0f
        bucketMax = 0f
        bucketFrames = 0
    }

    private fun quantise(value: Float): Int {
        val clamped = if (value > 1f) 1f else if (value < -1f) -1f else value
        return (Math.round(clamped * 127f) + 128).coerceIn(0, 255)
    }

    private fun writeIntLe(value: Int) {
        stream.write(value and 0xFF)
        stream.write((value ushr 8) and 0xFF)
        stream.write((value ushr 16) and 0xFF)
        stream.write((value ushr 24) and 0xFF)
    }

    companion object {
        const val MAGIC = "DRPK"
        const val VERSION = 1
        const val DEFAULT_SAMPLES_PER_BUCKET = 2048
    }
}

/** One bucket of the waveform overview, in the -1..1 range. */
data class PeakBucket(val min: Float, val max: Float)

object PeakFileReader {

    /** Reads a peak file and resamples it down to at most [maxBuckets] columns for drawing. */
    fun read(file: File, maxBuckets: Int): List<PeakBucket> {
        if (!file.isFile) return emptyList()
        val raw = try {
            DataInputStream(file.inputStream().buffered()).use { input ->
                val magic = ByteArray(4)
                input.readFully(magic)
                if (String(magic, Charsets.US_ASCII) != PeakFileWriter.MAGIC) return emptyList()
                input.read() // version
                repeat(4) { input.read() } // samplesPerBucket
                val buckets = ArrayList<PeakBucket>()
                while (true) {
                    val lo = input.read()
                    val hi = input.read()
                    if (lo < 0 || hi < 0) break
                    buckets.add(PeakBucket(dequantise(lo), dequantise(hi)))
                }
                buckets
            }
        } catch (e: Exception) {
            return emptyList()
        }

        if (raw.isEmpty() || raw.size <= maxBuckets) return raw
        val step = raw.size.toFloat() / maxBuckets
        return (0 until maxBuckets).map { index ->
            val from = (index * step).toInt()
            val to = (((index + 1) * step).toInt()).coerceAtMost(raw.size)
            var min = 0f
            var max = 0f
            for (i in from until to) {
                if (raw[i].min < min) min = raw[i].min
                if (raw[i].max > max) max = raw[i].max
            }
            PeakBucket(min, max)
        }
    }

    /** Largest absolute value across the overview, used to normalise the drawing. */
    fun normalisationFactor(buckets: List<PeakBucket>): Float {
        val peak = buckets.maxOfOrNull { maxOf(abs(it.min), abs(it.max)) } ?: 0f
        return if (peak < 0.05f) 1f else 1f / peak
    }

    private fun dequantise(value: Int): Float = (value - 128) / 127f
}
