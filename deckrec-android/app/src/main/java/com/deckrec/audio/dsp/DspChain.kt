package com.deckrec.audio.dsp

import kotlin.math.exp

/**
 * The full record-bus chain, in the order the signal actually needs to travel:
 * input trim -> sub bass -> loudness -> brickwall limiter -> metering.
 *
 * Metering sits last on purpose. The number on screen is then the number that lands in the file,
 * so a DJ watching the meter is watching the recording rather than the input.
 */
class DspChain(private val sampleRate: Int) {

    /** Input trim in dB, applied before everything else. */
    var inputGainDb: Float = 0f
        set(value) {
            field = value.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
            targetGain = BrickwallLimiter.dbToLinear(field)
        }

    var subBassAmount: Float
        get() = subBass.amount
        set(value) {
            subBass.amount = value
        }

    var loudnessAmount: Float
        get() = loudness.amount
        set(value) {
            loudness.amount = value
        }

    var limiterEnabled: Boolean = true

    var limiterCeilingDb: Float
        get() = limiter.ceilingDb
        set(value) {
            limiter.ceilingDb = value
        }

    private val subBass = SubBass(sampleRate)
    private val loudness = Loudness(sampleRate)
    private val limiter = BrickwallLimiter(sampleRate)
    private val meter = LevelMeter(sampleRate)
    val transitionDetector = TransitionDetector(sampleRate)

    private var currentGain = 1f
    private var targetGain = 1f
    private val gainSmoothing = (1.0 - exp(-1.0 / (GAIN_SMOOTH_SECONDS * sampleRate))).toFloat()

    fun reset() {
        subBass.reset()
        loudness.reset()
        limiter.reset()
        meter.reset()
        transitionDetector.reset()
        currentGain = targetGain
    }

    /** Latency the chain adds, in frames. Used to keep marker positions honest. */
    fun latencyFrames(): Int = if (limiterEnabled) limiter.latencyFrames() else 0

    /**
     * Processes an interleaved stereo block in place and returns the meter snapshot for it.
     */
    fun process(buffer: FloatArray, frames: Int): Levels {
        applyGain(buffer, frames)
        subBass.process(buffer, frames)
        loudness.process(buffer, frames)
        if (limiterEnabled) {
            limiter.process(buffer, frames)
        } else {
            limiter.reset()
        }
        return meter.measure(
            buffer,
            frames,
            if (limiterEnabled) limiter.gainReductionDb else 0f,
        )
    }

    private fun applyGain(buffer: FloatArray, frames: Int) {
        if (currentGain == targetGain && targetGain == 1f) return
        var i = 0
        repeat(frames) {
            currentGain += (targetGain - currentGain) * gainSmoothing
            buffer[i] *= currentGain
            buffer[i + 1] *= currentGain
            i += CHANNELS
        }
    }

    companion object {
        const val MIN_GAIN_DB = -24f
        const val MAX_GAIN_DB = 24f
        private const val GAIN_SMOOTH_SECONDS = 0.02f
        private const val CHANNELS = 2
    }
}
