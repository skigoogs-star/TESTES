package com.deckrec.audio.dsp

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Regression tests for DSP defects found in review.
 *
 * Each one is written so that it fails on the behaviour that was there before the fix, rather than
 * merely asserting the code runs. The DSP package is pure Kotlin with no Android dependencies, so
 * these run on the JVM in seconds.
 */
class DspRegressionTest {

    private val sampleRate = 48_000

    /** Runs a mono signal through the limiter as interleaved stereo and returns the mono output. */
    private fun limit(input: FloatArray, ceilingDb: Float): FloatArray {
        val limiter = BrickwallLimiter(sampleRate).apply { this.ceilingDb = ceilingDb }
        val out = FloatArray(input.size)
        val block = FloatArray(BLOCK * 2)
        var i = 0
        while (i < input.size) {
            val n = minOf(BLOCK, input.size - i)
            for (k in 0 until n) {
                block[k * 2] = input[i + k]
                block[k * 2 + 1] = input[i + k]
            }
            limiter.process(block, n, applyGain = true)
            for (k in 0 until n) out[i + k] = block[k * 2]
            i += n
        }
        return out
    }

    @Test
    fun `limiter never exceeds its ceiling`() {
        val ceilingDb = -0.3f
        val ceiling = BrickwallLimiter.dbToLinear(ceilingDb)
        val input = FloatArray(sampleRate) { n ->
            // Deliberately brutal: a loud sine with a full-scale spike every 100 ms.
            val base = 1.6 * sin(2.0 * PI * 220.0 * n / sampleRate)
            val spike = if (n % 4800 == 0) 6.0 else 0.0
            (base + spike).toFloat()
        }
        val out = limit(input, ceilingDb)
        val worst = out.maxOf { abs(it) }
        assertTrue(
            "peak $worst exceeded ceiling $ceiling",
            worst <= ceiling * 1.0001f,
        )
    }

    /**
     * The old one-pole attack could not converge inside the look-ahead window — it was still ~13%
     * short when a transient arrived, so the safety clamp hard-clipped the leading edge. A burst
     * arriving from silence at +6 dB over the ceiling must come out as a cleanly scaled sinusoid.
     */
    @Test
    fun `limiter does not flat-top a transient`() {
        val ceilingDb = -0.3f
        val freq = 220.0
        val burstStart = sampleRate / 2
        val total = sampleRate
        val input = FloatArray(total)
        for (n in burstStart until total) {
            input[n] = (2.0 * sin(2.0 * PI * freq * (n - burstStart) / sampleRate)).toFloat()
        }

        val out = limit(input, ceilingDb)

        var onset = burstStart
        while (onset < total && abs(out[onset]) < 1e-4f) onset++
        val window = 480
        assertTrue("burst never reached the output", onset + window < total)

        // Phase-free fit of a*sin + b*cos: the limiter delays by its own look-ahead, and what is
        // being measured is waveform shape. A clipped wave cannot be fitted by one sinusoid.
        var sinSin = 0.0
        var cosCos = 0.0
        var sinCos = 0.0
        var ySin = 0.0
        var yCos = 0.0
        val s = DoubleArray(window)
        val c = DoubleArray(window)
        for (k in 0 until window) {
            val phase = 2.0 * PI * freq * k / sampleRate
            s[k] = sin(phase)
            c[k] = cos(phase)
            val y = out[onset + k].toDouble()
            sinSin += s[k] * s[k]
            cosCos += c[k] * c[k]
            sinCos += s[k] * c[k]
            ySin += y * s[k]
            yCos += y * c[k]
        }
        val det = sinSin * cosCos - sinCos * sinCos
        val a = (ySin * cosCos - yCos * sinCos) / det
        val b = (yCos * sinSin - ySin * sinCos) / det
        var err = 0.0
        var sig = 0.0
        for (k in 0 until window) {
            val fitted = a * s[k] + b * c[k]
            val y = out[onset + k].toDouble()
            err += (y - fitted) * (y - fitted)
            sig += fitted * fitted
        }
        val relative = sqrt(err / sig)
        assertTrue("transient was flat-topped: residual $relative", relative < 0.15)
    }

    /**
     * The old cubic knee had a slope steeper than the ratio, so between roughly 4 and 6 dB over
     * threshold the transfer curve ran backwards: louder in, quieter out.
     */
    @Test
    fun `loudness transfer curve is monotonic`() {
        var previousOut = -200.0
        var worstDrop = 0.0
        var worstAt = 0.0
        var levelDb = -40.0
        while (levelDb <= 0.0) {
            val amplitude = BrickwallLimiter.dbToLinear(levelDb.toFloat())
            val loudness = Loudness(sampleRate).apply { amount = 1f }
            val block = FloatArray(BLOCK * 2)
            var peak = 0f
            val settleBlocks = sampleRate / 2 / BLOCK
            val measureBlocks = sampleRate / 10 / BLOCK
            var n = 0
            for (b in 0 until settleBlocks + measureBlocks) {
                for (k in 0 until BLOCK) {
                    val v = (amplitude * sin(2.0 * PI * 440.0 * n / sampleRate)).toFloat()
                    block[k * 2] = v
                    block[k * 2 + 1] = v
                    n++
                }
                loudness.process(block, BLOCK)
                if (b >= settleBlocks) {
                    for (k in 0 until BLOCK) peak = maxOf(peak, abs(block[k * 2]))
                }
            }
            val outDb = BrickwallLimiter.linearToDb(peak).toDouble()
            val drop = previousOut - outDb
            if (drop > worstDrop) {
                worstDrop = drop
                worstAt = levelDb
            }
            previousOut = outDb
            levelDb += 0.25
        }
        assertTrue(
            "output fell ${"%.3f".format(worstDrop)} dB as input rose, at $worstAt dBFS",
            worstDrop < 0.05,
        )
    }

    /**
     * The divider holds its square between zero crossings and the smoother is a lowpass, so a
     * frozen flip-flop used to become a decaying DC step lasting half a second on every bass cut.
     */
    @Test
    fun `sub bass leaves no DC after the bass is cut`() {
        val sub = SubBass(sampleRate).apply { amount = 1f }
        val block = FloatArray(BLOCK * 2)
        var n = 0

        repeat(2 * sampleRate / BLOCK) {
            for (k in 0 until BLOCK) {
                val v = (0.5 * sin(2.0 * PI * 60.0 * n / sampleRate)).toFloat()
                block[k * 2] = v
                block[k * 2 + 1] = v
                n++
            }
            sub.process(block, BLOCK)
        }

        // The first stretch after the cut legitimately carries the sub's own decay. By half a
        // second in, a working divider is silent; a frozen one is still leaking DC.
        val blocks = sampleRate / BLOCK
        var sum = 0.0
        var maxAbs = 0f
        var count = 0
        repeat(blocks) { b ->
            block.fill(0f)
            sub.process(block, BLOCK)
            if (b >= blocks / 2) {
                for (k in 0 until BLOCK) {
                    sum += block[k * 2]
                    maxAbs = maxOf(maxAbs, abs(block[k * 2]))
                    count++
                }
            }
        }
        val mean = sum / count
        assertTrue("residual DC $mean, peak $maxAbs", abs(mean) < 1e-4 && maxAbs < 1e-3f)
    }

    /**
     * The clip-hold counter was decremented without a floor, so after 2^31 frames (~12.4 h at
     * 48 kHz) it wrapped positive and latched the clip light on over a clean recording.
     */
    @Test
    fun `clip indicator does not latch over a long session`() {
        val meter = LevelMeter(sampleRate)
        val frames = sampleRate
        val block = FloatArray(frames * 2) { 0.05f }
        var latched = false
        for (second in 0 until 13 * 3600) {
            if (meter.measure(block, frames, 0f).clipping) {
                latched = true
                break
            }
        }
        assertTrue("clip light latched on quiet audio", !latched)
    }

    @Test
    fun `lowpass passes DC at unity and rejects well above the corner`() {
        val lp = Biquad().apply { lowPass(200f, 0.707f, sampleRate) }
        var dc = 0f
        repeat(4000) { dc = lp.processSample(0, 1f) }
        assertTrue("DC gain $dc", abs(dc - 1f) < 0.01f)

        val hf = Biquad().apply { lowPass(200f, 0.707f, sampleRate) }
        var peak = 0f
        for (n in 0 until 8000) {
            val v = sin(2.0 * PI * 8000.0 * n / sampleRate).toFloat()
            val y = hf.processSample(0, v)
            if (n > 4000) peak = maxOf(peak, abs(y))
        }
        assertTrue("8 kHz leaked through at $peak", peak < 0.01f)
    }

    /** Switching the limiter off must not change latency, or toggling it splices the recording. */
    @Test
    fun `limiter latency is the same whether or not gain is applied`() {
        val chain = DspChain(sampleRate)
        chain.limiterEnabled = true
        val withGain = chain.latencyFrames()
        chain.limiterEnabled = false
        assertTrue("latency changed with the limiter switch", withGain == chain.latencyFrames())
        assertTrue("limiter reported no latency", withGain > 0)
    }

    private companion object {
        const val BLOCK = 1024
    }
}
