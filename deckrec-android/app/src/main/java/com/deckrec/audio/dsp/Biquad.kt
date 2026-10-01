package com.deckrec.audio.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Stereo biquad in transposed direct form II. One instance filters an interleaved stereo buffer;
 * state is kept per channel so the two sides never bleed into each other.
 */
class Biquad {

    private var b0 = 1f
    private var b1 = 0f
    private var b2 = 0f
    private var a1 = 0f
    private var a2 = 0f

    private val z1 = FloatArray(CHANNELS)
    private val z2 = FloatArray(CHANNELS)

    fun reset() {
        z1.fill(0f)
        z2.fill(0f)
    }

    fun lowPass(freq: Float, q: Float, sampleRate: Int) {
        val w0 = 2.0 * PI * clampFreq(freq, sampleRate) / sampleRate
        val cosW = cos(w0)
        val alpha = sin(w0) / (2.0 * q)
        val a0 = 1.0 + alpha
        setCoefficients(
            b0 = (1.0 - cosW) / 2.0 / a0,
            b1 = (1.0 - cosW) / a0,
            b2 = (1.0 - cosW) / 2.0 / a0,
            a1 = (-2.0 * cosW) / a0,
            a2 = (1.0 - alpha) / a0,
        )
    }

    fun highPass(freq: Float, q: Float, sampleRate: Int) {
        val w0 = 2.0 * PI * clampFreq(freq, sampleRate) / sampleRate
        val cosW = cos(w0)
        val alpha = sin(w0) / (2.0 * q)
        val a0 = 1.0 + alpha
        setCoefficients(
            b0 = (1.0 + cosW) / 2.0 / a0,
            b1 = -(1.0 + cosW) / a0,
            b2 = (1.0 + cosW) / 2.0 / a0,
            a1 = (-2.0 * cosW) / a0,
            a2 = (1.0 - alpha) / a0,
        )
    }

    fun lowShelf(freq: Float, gainDb: Float, sampleRate: Int, slope: Float = 1f) {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * clampFreq(freq, sampleRate) / sampleRate
        val cosW = cos(w0)
        val alpha = sin(w0) / 2.0 * sqrt((a + 1.0 / a) * (1.0 / slope - 1.0) + 2.0)
        val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha
        val a0 = (a + 1.0) + (a - 1.0) * cosW + twoSqrtAAlpha
        setCoefficients(
            b0 = a * ((a + 1.0) - (a - 1.0) * cosW + twoSqrtAAlpha) / a0,
            b1 = 2.0 * a * ((a - 1.0) - (a + 1.0) * cosW) / a0,
            b2 = a * ((a + 1.0) - (a - 1.0) * cosW - twoSqrtAAlpha) / a0,
            a1 = -2.0 * ((a - 1.0) + (a + 1.0) * cosW) / a0,
            a2 = ((a + 1.0) + (a - 1.0) * cosW - twoSqrtAAlpha) / a0,
        )
    }

    private fun setCoefficients(b0: Double, b1: Double, b2: Double, a1: Double, a2: Double) {
        this.b0 = b0.toFloat()
        this.b1 = b1.toFloat()
        this.b2 = b2.toFloat()
        this.a1 = a1.toFloat()
        this.a2 = a2.toFloat()
    }

    /** Filters one sample of [channel] (0 = left, 1 = right). */
    fun processSample(channel: Int, x: Float): Float {
        val y = b0 * x + z1[channel]
        z1[channel] = b1 * x - a1 * y + z2[channel]
        z2[channel] = b2 * x - a2 * y
        return y
    }

    /** Filters an interleaved stereo buffer in place. */
    fun processInPlace(buffer: FloatArray, frames: Int) {
        var i = 0
        repeat(frames) {
            buffer[i] = processSample(0, buffer[i])
            buffer[i + 1] = processSample(1, buffer[i + 1])
            i += 2
        }
    }

    private fun clampFreq(freq: Float, sampleRate: Int): Double =
        freq.toDouble().coerceIn(10.0, sampleRate * 0.45)

    private companion object {
        const val CHANNELS = 2
    }
}
