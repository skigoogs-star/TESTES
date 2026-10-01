package com.deckrec.audio.dsp

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/**
 * Look-ahead brickwall limiter on the interleaved stereo bus.
 *
 * The signal is delayed by the look-ahead window while the gain is derived from samples that have
 * not been output yet. The gain target is the *minimum* required gain across the whole look-ahead
 * window rather than the gain the current sample happens to need, which is what makes the
 * difference between limiting and clipping: the moment a loud sample enters the window the target
 * drops, giving the smoother a full look-ahead window to ride down before that sample reaches the
 * output. A one-pole referenced to the instantaneous peak cannot do this — it is still 13% short
 * when the transient arrives, and the hard clamp below then has to clip it.
 *
 * Gain reduction is stereo-linked, which keeps the image stable instead of pulling the mix
 * sideways whenever one side peaks.
 */
class BrickwallLimiter(private val sampleRate: Int) {

    /** Output ceiling in dBFS. */
    var ceilingDb: Float = DEFAULT_CEILING_DB
        set(value) {
            field = value
            ceilingLinear = dbToLinear(value)
        }

    private var ceilingLinear = dbToLinear(DEFAULT_CEILING_DB)

    private val lookaheadFrames = max(16, (LOOKAHEAD_SECONDS * sampleRate).toInt())
    private val delayLine = FloatArray(lookaheadFrames * CHANNELS)
    private var writeIndex = 0

    // Monotonically increasing deque of required gains, oldest at the head. The head is always the
    // minimum required gain over the look-ahead window.
    private val windowCapacity = lookaheadFrames + 2
    private val windowGain = FloatArray(windowCapacity)
    private val windowAt = LongArray(windowCapacity)
    private var windowHead = 0
    private var windowCount = 0
    private var frameCounter = 0L

    private var gain = 1f
    // Converge well inside the look-ahead window: after `lookahead` seconds a one-pole with this
    // time constant is within e^-5 (~0.7%) of the target, so residual overshoot is negligible.
    private val attackCoefficient = onePoleCoefficient(LOOKAHEAD_SECONDS / 5f)
    private val releaseCoefficient = onePoleCoefficient(RELEASE_SECONDS)

    /** Most recent gain reduction in dB (a positive number means the limiter is working). */
    var gainReductionDb: Float = 0f
        private set

    fun reset() {
        delayLine.fill(0f)
        writeIndex = 0
        windowHead = 0
        windowCount = 0
        frameCounter = 0L
        gain = 1f
        gainReductionDb = 0f
    }

    /** Number of frames of latency this stage adds. Constant whether or not gain is applied. */
    fun latencyFrames(): Int = lookaheadFrames

    /**
     * Delays [buffer] by the look-ahead window and limits it.
     *
     * When [applyGain] is false the delay still runs but no gain is imposed, so switching the
     * limiter on and off mid-recording does not change latency or splice a discontinuity into the
     * file.
     */
    fun process(buffer: FloatArray, frames: Int, applyGain: Boolean = true) {
        var minGain = 1f
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

            val peak = max(abs(inL), abs(inR))
            val required = if (peak > ceilingLinear) ceilingLinear / peak else 1f
            pushRequiredGain(required, frameCounter)
            evictExpired(frameCounter)
            val target = windowGain[windowHead]

            val coefficient = if (target < gain) attackCoefficient else releaseCoefficient
            gain += (target - gain) * coefficient

            if (applyGain) {
                var l = outL * gain
                var r = outR * gain
                // Safety net for the sub-sample overshoot no finite-rate envelope can catch. With
                // the windowed target above this is a fraction of a dB, not audible clipping.
                if (l > ceilingLinear) l = ceilingLinear else if (l < -ceilingLinear) l = -ceilingLinear
                if (r > ceilingLinear) r = ceilingLinear else if (r < -ceilingLinear) r = -ceilingLinear
                buffer[i] = l
                buffer[i + 1] = r
                if (gain < minGain) minGain = gain
            } else {
                buffer[i] = outL
                buffer[i + 1] = outR
            }

            frameCounter++
            i += CHANNELS
        }
        gainReductionDb = if (minGain >= 1f) 0f else -linearToDb(minGain)
    }

    private fun pushRequiredGain(required: Float, at: Long) {
        // Anything already in the deque that is no smaller than the incoming value can never be
        // the window minimum again, so drop it. This keeps the deque monotonically increasing.
        while (windowCount > 0) {
            var tail = windowHead + windowCount - 1
            if (tail >= windowCapacity) tail -= windowCapacity
            if (windowGain[tail] < required) break
            windowCount--
        }
        var slot = windowHead + windowCount
        if (slot >= windowCapacity) slot -= windowCapacity
        windowGain[slot] = required
        windowAt[slot] = at
        windowCount++
    }

    private fun evictExpired(now: Long) {
        val oldest = now - lookaheadFrames
        while (windowCount > 0 && windowAt[windowHead] < oldest) {
            windowHead++
            if (windowHead >= windowCapacity) windowHead = 0
            windowCount--
        }
    }

    private fun onePoleCoefficient(seconds: Float): Float =
        (1.0 - exp(-1.0 / (seconds.toDouble() * sampleRate))).toFloat()

    companion object {
        const val DEFAULT_CEILING_DB = -0.3f
        private const val LOOKAHEAD_SECONDS = 0.005f
        private const val RELEASE_SECONDS = 0.12f
        private const val CHANNELS = 2

        fun dbToLinear(db: Float): Float = 10f.pow(db / 20f)

        fun linearToDb(linear: Float): Float =
            if (linear <= 1e-7f) -140f else (20.0 * ln(linear.toDouble()) / ln(10.0)).toFloat()
    }
}
