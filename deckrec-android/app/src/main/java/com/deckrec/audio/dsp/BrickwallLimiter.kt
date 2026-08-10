package com.deckrec.audio.dsp

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/**
 * Look-ahead brickwall limiter on the interleaved stereo bus.
 *
 * The signal is delayed by the look-ahead window while gain is computed from the *undelayed*
 * samples, so the gain has already finished falling by the time the transient that caused it
 * reaches the output. Gain reduction is stereo-linked, which keeps the image stable instead of
 * pulling the mix sideways whenever one side peaks.
 */
class BrickwallLimiter(private val sampleRate: Int) {

    /** Output ceiling in dBFS. */
    var ceilingDb: Float = DEFAULT_CEILING_DB
        set(value) {
            field = value
            ceilingLinear = dbToLinear(value)
        }

    private var ceilingLinear = dbToLinear(DEFAULT_CEILING_DB)

    private val lookaheadFrames = max(8, (LOOKAHEAD_SECONDS * sampleRate).toInt())
    private val delayLine = FloatArray(lookaheadFrames * CHANNELS)
    private var writeIndex = 0

    private var gain = 1f
    private val attackCoefficient = onePoleCoefficient(ATTACK_SECONDS)
    private val releaseCoefficient = onePoleCoefficient(RELEASE_SECONDS)

    /** Most recent gain reduction in dB (a positive number means the limiter is working). */
    var gainReductionDb: Float = 0f
        private set

    fun reset() {
        delayLine.fill(0f)
        writeIndex = 0
        gain = 1f
        gainReductionDb = 0f
    }

    /** Number of frames of latency this stage adds. */
    fun latencyFrames(): Int = lookaheadFrames

    fun process(buffer: FloatArray, frames: Int) {
        var maxReduction = 0f
        var i = 0
        repeat(frames) {
            val inL = buffer[i]
            val inR = buffer[i + 1]

            // Pull the delayed frame out before overwriting the slot with the incoming one.
            val readIndex = writeIndex
            val outL = delayLine[readIndex]
            val outR = delayLine[readIndex + 1]
            delayLine[readIndex] = inL
            delayLine[readIndex + 1] = inR
            writeIndex += CHANNELS
            if (writeIndex >= delayLine.size) writeIndex = 0

            // Gain is computed from the incoming (future) peak: that is the look-ahead.
            val peak = max(abs(inL), abs(inR))
            val target = if (peak > ceilingLinear) ceilingLinear / peak else 1f
            val coefficient = if (target < gain) attackCoefficient else releaseCoefficient
            gain += (target - gain) * coefficient

            var l = outL * gain
            var r = outR * gain
            // Safety net for the sub-sample overshoot a one-pole envelope cannot catch.
            if (l > ceilingLinear) l = ceilingLinear else if (l < -ceilingLinear) l = -ceilingLinear
            if (r > ceilingLinear) r = ceilingLinear else if (r < -ceilingLinear) r = -ceilingLinear
            buffer[i] = l
            buffer[i + 1] = r

            val reduction = 1f - gain
            if (reduction > maxReduction) maxReduction = reduction
            i += CHANNELS
        }
        gainReductionDb = if (maxReduction <= 0f) 0f else -linearToDb(1f - maxReduction)
    }

    private fun onePoleCoefficient(seconds: Float): Float =
        (1.0 - exp(-1.0 / (seconds.toDouble() * sampleRate))).toFloat()

    companion object {
        const val DEFAULT_CEILING_DB = -0.3f
        private const val LOOKAHEAD_SECONDS = 0.003f
        private const val ATTACK_SECONDS = 0.0015f
        private const val RELEASE_SECONDS = 0.12f
        private const val CHANNELS = 2

        fun dbToLinear(db: Float): Float = 10f.pow(db / 20f)

        fun linearToDb(linear: Float): Float =
            if (linear <= 1e-7f) -140f else (20.0 * ln(linear.toDouble()) / ln(10.0)).toFloat()
    }
}
