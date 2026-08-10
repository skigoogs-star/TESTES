package com.deckrec.audio.dsp

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/**
 * Stereo-linked soft-knee compressor with automatic make-up, driven by a single 0..1 "amount".
 *
 * At 0 it is a bypass. As the amount rises the threshold drops, the ratio steepens and make-up
 * gain comes up with it, which is what a single "loudness" control has to do to raise perceived
 * level without the user also having to balance a threshold against a make-up knob.
 */
class Loudness(private val sampleRate: Int) {

    /** 0f = bypass, 1f = maximum density. */
    var amount: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            recomputeParameters()
        }

    private var thresholdDb = 0f
    private var ratio = 1f
    private var makeupLinear = 1f

    private var envelope = 0f
    private val attackCoefficient = onePoleCoefficient(ATTACK_SECONDS)
    private val releaseCoefficient = onePoleCoefficient(RELEASE_SECONDS)

    var gainReductionDb: Float = 0f
        private set

    init {
        recomputeParameters()
    }

    fun reset() {
        envelope = 0f
        gainReductionDb = 0f
    }

    fun process(buffer: FloatArray, frames: Int) {
        if (amount <= 0f) {
            gainReductionDb = 0f
            return
        }
        var maxReductionDb = 0f
        var i = 0
        repeat(frames) {
            val detector = max(abs(buffer[i]), abs(buffer[i + 1]))
            val coefficient = if (detector > envelope) attackCoefficient else releaseCoefficient
            envelope += (detector - envelope) * coefficient

            val levelDb = BrickwallLimiter.linearToDb(envelope)
            val over = levelDb - thresholdDb
            val reductionDb = if (over <= 0f) {
                0f
            } else if (over < KNEE_DB) {
                // Quadratic knee: the ratio eases in over the first KNEE_DB above threshold.
                val kneeFactor = over / KNEE_DB
                (over - over / ratio) * kneeFactor * kneeFactor
            } else {
                over - over / ratio
            }

            val gain = BrickwallLimiter.dbToLinear(-reductionDb) * makeupLinear
            buffer[i] *= gain
            buffer[i + 1] *= gain

            if (reductionDb > maxReductionDb) maxReductionDb = reductionDb
            i += CHANNELS
        }
        gainReductionDb = maxReductionDb
    }

    private fun recomputeParameters() {
        thresholdDb = lerp(-6f, -24f, amount)
        ratio = lerp(1f, 4.5f, amount)
        // Give back roughly what the compressor took at a typical programme level.
        makeupLinear = BrickwallLimiter.dbToLinear(lerp(0f, 7.5f, amount))
    }

    private fun onePoleCoefficient(seconds: Float): Float =
        (1.0 - exp(-1.0 / (seconds.toDouble() * sampleRate))).toFloat()

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private companion object {
        const val ATTACK_SECONDS = 0.008f
        const val RELEASE_SECONDS = 0.18f
        const val KNEE_DB = 6f
        const val CHANNELS = 2
    }
}
