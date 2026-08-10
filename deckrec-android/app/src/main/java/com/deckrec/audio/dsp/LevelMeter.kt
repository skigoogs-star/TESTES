package com.deckrec.audio.dsp

import kotlin.math.abs
import kotlin.math.sqrt

/** Immutable meter snapshot handed to the UI once per processed block. */
data class Levels(
    val peakDbL: Float = SILENCE_DB,
    val peakDbR: Float = SILENCE_DB,
    val rmsDbL: Float = SILENCE_DB,
    val rmsDbR: Float = SILENCE_DB,
    val holdDbL: Float = SILENCE_DB,
    val holdDbR: Float = SILENCE_DB,
    val clipping: Boolean = false,
    val limiterReductionDb: Float = 0f,
) {
    companion object {
        const val SILENCE_DB = -90f
    }
}

/**
 * Peak/RMS metering with peak-hold, matching the ballistics DJs expect from mixer meters:
 * instantaneous rise, slow fall, and a hold bar that lingers so a single stray transient is still
 * visible a moment later.
 */
class LevelMeter(sampleRate: Int) {

    private var peakL = 0f
    private var peakR = 0f
    private var holdL = 0f
    private var holdR = 0f
    private var holdFramesRemainingL = 0
    private var holdFramesRemainingR = 0
    private var clipFramesRemaining = 0

    private val holdFrames = (HOLD_SECONDS * sampleRate).toInt()
    private val clipHoldFrames = (CLIP_HOLD_SECONDS * sampleRate).toInt()
    private val decayPerFrame = BrickwallLimiter.dbToLinear(-DECAY_DB_PER_SECOND / sampleRate)

    fun reset() {
        peakL = 0f
        peakR = 0f
        holdL = 0f
        holdR = 0f
        holdFramesRemainingL = 0
        holdFramesRemainingR = 0
        clipFramesRemaining = 0
    }

    /** Analyses an interleaved stereo block and returns the snapshot for this block. */
    fun measure(buffer: FloatArray, frames: Int, limiterReductionDb: Float): Levels {
        if (frames <= 0) return Levels(limiterReductionDb = limiterReductionDb)

        var blockPeakL = 0f
        var blockPeakR = 0f
        var sumSquaresL = 0.0
        var sumSquaresR = 0.0
        var clipped = false

        var i = 0
        repeat(frames) {
            val l = buffer[i]
            val r = buffer[i + 1]
            val absL = abs(l)
            val absR = abs(r)
            if (absL > blockPeakL) blockPeakL = absL
            if (absR > blockPeakR) blockPeakR = absR
            sumSquaresL += (l * l).toDouble()
            sumSquaresR += (r * r).toDouble()
            if (absL >= CLIP_THRESHOLD || absR >= CLIP_THRESHOLD) clipped = true
            i += CHANNELS
        }

        peakL = decayToward(peakL, blockPeakL, frames)
        peakR = decayToward(peakR, blockPeakR, frames)

        if (blockPeakL >= holdL) {
            holdL = blockPeakL
            holdFramesRemainingL = holdFrames
        } else {
            holdFramesRemainingL -= frames
            if (holdFramesRemainingL <= 0) holdL = decayToward(holdL, 0f, frames)
        }
        if (blockPeakR >= holdR) {
            holdR = blockPeakR
            holdFramesRemainingR = holdFrames
        } else {
            holdFramesRemainingR -= frames
            if (holdFramesRemainingR <= 0) holdR = decayToward(holdR, 0f, frames)
        }

        if (clipped) clipFramesRemaining = clipHoldFrames else clipFramesRemaining -= frames

        return Levels(
            peakDbL = BrickwallLimiter.linearToDb(peakL),
            peakDbR = BrickwallLimiter.linearToDb(peakR),
            rmsDbL = BrickwallLimiter.linearToDb(sqrt(sumSquaresL / frames).toFloat()),
            rmsDbR = BrickwallLimiter.linearToDb(sqrt(sumSquaresR / frames).toFloat()),
            holdDbL = BrickwallLimiter.linearToDb(holdL),
            holdDbR = BrickwallLimiter.linearToDb(holdR),
            clipping = clipFramesRemaining > 0,
            limiterReductionDb = limiterReductionDb,
        )
    }

    private fun decayToward(current: Float, target: Float, frames: Int): Float {
        if (target >= current) return target
        // Exponential decay evaluated once per block rather than once per sample.
        val value = current * pow(decayPerFrame, frames)
        return if (value < target) target else value
    }

    private fun pow(base: Float, exponent: Int): Float {
        var result = 1f
        var b = base
        var e = exponent
        while (e > 0) {
            if (e and 1 == 1) result *= b
            b *= b
            e = e shr 1
        }
        return result
    }

    private companion object {
        const val HOLD_SECONDS = 1.4f
        const val CLIP_HOLD_SECONDS = 1.5f
        const val DECAY_DB_PER_SECOND = 22f
        const val CLIP_THRESHOLD = 0.9999f
        const val CHANNELS = 2
    }
}
