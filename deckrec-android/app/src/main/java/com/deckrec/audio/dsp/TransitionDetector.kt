package com.deckrec.audio.dsp

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Places track markers automatically by watching for the spectral signature of a DJ transition.
 *
 * The original iOS app gets its timestamps from the mixer, which reports fader movement over its
 * own control protocol. Nothing equivalent is exposed to a USB host on Android, so this listens to
 * the audio instead. A mix-out is dominated by two events: the outgoing track's bass being swapped
 * for the incoming one's, and the top end changing character as a new arrangement takes over.
 * Tracking a fast and a slow envelope of each band and firing when they diverge catches both,
 * while the minimum gap keeps a breakdown inside one track from counting as a new track.
 */
class TransitionDetector(private val sampleRate: Int) {

    var enabled: Boolean = true

    /** 0f = only obvious cuts, 1f = hair-trigger. */
    var sensitivity: Float = 0.5f
        set(value) {
            field = value.coerceIn(0f, 1f)
        }

    /** Refuses to place two markers closer together than this. */
    var minimumGapSeconds: Float = 45f

    private val lowBand = Biquad()
    private val highBand = Biquad()

    private var fastLowDb = Levels.SILENCE_DB
    private var slowLowDb = Levels.SILENCE_DB
    private var fastHighDb = Levels.SILENCE_DB
    private var slowHighDb = Levels.SILENCE_DB
    private var primed = false

    private var lastMarkerFrame = Long.MIN_VALUE

    init {
        lowBand.lowPass(LOW_BAND_HZ, 0.707f, sampleRate)
        highBand.highPass(HIGH_BAND_HZ, 0.707f, sampleRate)
    }

    fun reset() {
        lowBand.reset()
        highBand.reset()
        fastLowDb = Levels.SILENCE_DB
        slowLowDb = Levels.SILENCE_DB
        fastHighDb = Levels.SILENCE_DB
        slowHighDb = Levels.SILENCE_DB
        primed = false
        lastMarkerFrame = Long.MIN_VALUE
    }

    /**
     * Analyses one block. Returns the frame position of a detected transition, or -1 if this block
     * did not contain one. [startFrame] is the position of the block within the recording.
     */
    fun analyse(buffer: FloatArray, frames: Int, startFrame: Long): Long {
        if (!enabled || frames <= 0) return -1

        var lowSum = 0.0
        var highSum = 0.0
        var i = 0
        repeat(frames) {
            val mono = (buffer[i] + buffer[i + 1]) * 0.5f
            val low = lowBand.processSample(0, mono)
            val high = highBand.processSample(0, mono)
            lowSum += (low * low).toDouble()
            highSum += (high * high).toDouble()
            i += CHANNELS
        }

        val lowDb = BrickwallLimiter.linearToDb(sqrt(lowSum / frames).toFloat())
        val highDb = BrickwallLimiter.linearToDb(sqrt(highSum / frames).toFloat())
        val blockSeconds = frames.toFloat() / sampleRate

        if (!primed) {
            fastLowDb = lowDb
            slowLowDb = lowDb
            fastHighDb = highDb
            slowHighDb = highDb
            primed = true
            lastMarkerFrame = startFrame
            return -1
        }

        val fastCoefficient = blockCoefficient(FAST_SECONDS, blockSeconds)
        val slowCoefficient = blockCoefficient(SLOW_SECONDS, blockSeconds)
        fastLowDb += (lowDb - fastLowDb) * fastCoefficient
        fastHighDb += (highDb - fastHighDb) * fastCoefficient
        slowLowDb += (lowDb - slowLowDb) * slowCoefficient
        slowHighDb += (highDb - slowHighDb) * slowCoefficient

        // Nothing meaningful to segment when the master bus is essentially silent.
        if (fastLowDb < FLOOR_DB && fastHighDb < FLOOR_DB) return -1

        val gapFrames = (minimumGapSeconds * sampleRate).toLong()
        if (startFrame - lastMarkerFrame < gapFrames) return -1

        val novelty = abs(fastLowDb - slowLowDb) + abs(fastHighDb - slowHighDb)
        val threshold = HIGH_THRESHOLD_DB + (LOW_THRESHOLD_DB - HIGH_THRESHOLD_DB) * sensitivity
        if (novelty < threshold) return -1

        lastMarkerFrame = startFrame
        // Collapse the slow envelope onto the new material so the very next block does not read as
        // another transition while the slow average is still catching up.
        slowLowDb = fastLowDb
        slowHighDb = fastHighDb
        return startFrame
    }

    /** Tells the detector a marker was placed manually, so it does not immediately add its own. */
    fun noteManualMarker(frame: Long) {
        lastMarkerFrame = frame
    }

    private fun blockCoefficient(timeConstantSeconds: Float, blockSeconds: Float): Float =
        (1.0 - exp(-blockSeconds.toDouble() / timeConstantSeconds)).toFloat().coerceIn(0f, 1f)

    private companion object {
        const val LOW_BAND_HZ = 130f
        const val HIGH_BAND_HZ = 2200f
        const val FAST_SECONDS = 0.6f
        const val SLOW_SECONDS = 12f
        const val FLOOR_DB = -55f
        const val LOW_THRESHOLD_DB = 6f
        const val HIGH_THRESHOLD_DB = 17f
        const val CHANNELS = 2
    }
}
