package com.deckrec.audio.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp

/**
 * Synthesises low end that is not in the source, rather than only boosting what is.
 *
 * Two things happen in parallel:
 *  1. A low shelf lifts the existing bottom end, which is the "weight" part.
 *  2. An octave divider tracks the fundamental of the 40-130 Hz band and emits a sine one octave
 *     below it, amplitude-tracked to the band's envelope. That is the "new signal derived from the
 *     input" part, and it is what makes a thin kick land on a big system.
 *
 * The divider is gated: below the noise floor there is no fundamental to track, and an ungated
 * divider would happily synthesise sub-bass out of room hiss between tracks.
 */
class SubBass(private val sampleRate: Int) {

    /** 0f = bypass, 1f = maximum. */
    var amount: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            shelf.lowShelf(SHELF_FREQ, MAX_SHELF_DB * field, sampleRate)
        }

    private val shelf = Biquad()
    private val bandLow = Biquad()
    private val bandHigh = Biquad()
    private val subSmoother = Biquad()

    private var envelope = 0f
    private val envelopeAttack = onePoleCoefficient(0.010f)
    private val envelopeRelease = onePoleCoefficient(0.120f)

    private var previousSample = 0f
    private var flipFlop = 1f
    private var lastCrossingFrame = 0L
    private var frameCounter = 0L
    private var lockedPeriodFrames = 0f

    private var dcPreviousIn = 0f
    private var dcPreviousOut = 0f
    private val dcCoefficient = 1f - (2.0 * PI * DC_BLOCK_HZ / sampleRate).toFloat()

    init {
        bandLow.lowPass(BAND_HIGH_HZ, 0.707f, sampleRate)
        bandHigh.highPass(BAND_LOW_HZ, 0.707f, sampleRate)
        subSmoother.lowPass(SUB_SMOOTH_HZ, 0.707f, sampleRate)
        shelf.lowShelf(SHELF_FREQ, 0f, sampleRate)
    }

    fun reset() {
        shelf.reset()
        bandLow.reset()
        bandHigh.reset()
        subSmoother.reset()
        envelope = 0f
        previousSample = 0f
        flipFlop = 1f
        lockedPeriodFrames = 0f
        frameCounter = 0
        lastCrossingFrame = 0
        dcPreviousIn = 0f
        dcPreviousOut = 0f
    }

    fun process(buffer: FloatArray, frames: Int) {
        if (amount <= 0f) return

        val synthLevel = SYNTH_LEVEL * amount
        val gateLinear = BrickwallLimiter.dbToLinear(GATE_DB)

        var i = 0
        repeat(frames) {
            val mono = (buffer[i] + buffer[i + 1]) * 0.5f

            // Isolate the fundamental band on channel 0 of each helper filter.
            val banded = bandLow.processSample(0, bandHigh.processSample(0, mono))

            val magnitude = abs(banded)
            val coefficient = if (magnitude > envelope) envelopeAttack else envelopeRelease
            envelope += (magnitude - envelope) * coefficient

            // Rising zero crossing marks one full cycle of the fundamental; toggling on every
            // other crossing produces a square wave exactly one octave down.
            if (previousSample <= 0f && banded > 0f) {
                val period = (frameCounter - lastCrossingFrame).toFloat()
                if (period in MIN_PERIOD_FRAMES..MAX_PERIOD_FRAMES) {
                    lockedPeriodFrames = period
                    flipFlop = -flipFlop
                }
                lastCrossingFrame = frameCounter
            }
            previousSample = banded

            // Lock is lost when the band stops crossing zero: the DJ killed the bass, the track
            // ended, or the content moved outside the divider's range. The square is held between
            // crossings, and a held square through a lowpass is DC — without this the sub emits a
            // half-second decaying DC step into both channels on every bass cut.
            if ((frameCounter - lastCrossingFrame).toFloat() > MAX_PERIOD_FRAMES) {
                lockedPeriodFrames = 0f
            }

            // Smoothing the square turns it into something close to a sine, so it adds weight
            // instead of the buzz a raw divider would inject. Feeding zero rather than skipping
            // the filter lets it decay rather than jump when the lock drops out.
            val locked = lockedPeriodFrames > 0f && envelope > gateLinear
            val smoothed = subSmoother.processSample(0, if (locked) flipFlop else 0f)
            // A divider whose half-cycles are not perfectly symmetrical still leaves a DC term,
            // and nothing downstream of here removes it.
            val sub = blockDc(smoothed * envelope * synthLevel)

            buffer[i] += sub
            buffer[i + 1] += sub
            frameCounter++
            i += CHANNELS
        }

        shelf.processInPlace(buffer, frames)
    }

    private fun onePoleCoefficient(seconds: Float): Float =
        (1.0 - exp(-1.0 / (seconds.toDouble() * sampleRate))).toFloat()

    /** First-order DC blocker: y[n] = x[n] - x[n-1] + R*y[n-1]. */
    private fun blockDc(x: Float): Float {
        val y = x - dcPreviousIn + dcCoefficient * dcPreviousOut
        dcPreviousIn = x
        dcPreviousOut = y
        return y
    }

    private val MIN_PERIOD_FRAMES: Float get() = sampleRate / BAND_HIGH_HZ
    private val MAX_PERIOD_FRAMES: Float get() = sampleRate / BAND_LOW_HZ

    private companion object {
        const val BAND_LOW_HZ = 40f
        const val BAND_HIGH_HZ = 130f
        const val SUB_SMOOTH_HZ = 90f
        const val SHELF_FREQ = 90f
        const val MAX_SHELF_DB = 5.5f
        const val SYNTH_LEVEL = 0.55f
        const val GATE_DB = -46f
        const val DC_BLOCK_HZ = 12f
        const val CHANNELS = 2
    }
}
