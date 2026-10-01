package com.deckrec.usb.host

/** Converts the interleaved little-endian bytes off the wire into the floats the DSP expects. */
object PcmDecode {

    private const val SCALE_24 = 1f / 8_388_608f
    private const val SCALE_32 = 1f / 2_147_483_648f

    /**
     * Decodes [frames] frames of [channels]-channel audio from [src] into [dest].
     *
     * Both buffers are interleaved and [dest] receives `frames * channels` floats. Samples are
     * scaled to the conventional ±1 range; a full-scale negative sample lands exactly on -1 and a
     * full-scale positive one a hair under +1, matching how `AudioRecord` presents float audio, so
     * the limiter and the clip detector behave identically on either capture path.
     */
    /**
     * Decodes just two channels out of an interleaved multi-channel stream, as stereo.
     *
     * A DJM frame is twelve channels of which the app wants two, so decoding everything and then
     * discarding five sixths of it would be five sixths wasted on the thread that must never fall
     * behind. This walks the frame stride and touches only the two samples that matter.
     */
    fun decodePair(
        src: ByteArray,
        srcOffset: Int,
        dest: FloatArray,
        destOffset: Int,
        frames: Int,
        channels: Int,
        left: Int,
        right: Int,
        encoding: PcmEncoding,
    ) {
        val width = encoding.bytesPerSample
        val stride = channels * width
        var frame = srcOffset
        var out = destOffset
        repeat(frames) {
            dest[out] = sampleAt(src, frame + left * width, encoding)
            dest[out + 1] = sampleAt(src, frame + right * width, encoding)
            out += 2
            frame += stride
        }
    }

    private fun sampleAt(src: ByteArray, index: Int, encoding: PcmEncoding): Float = when (encoding) {
        PcmEncoding.S24_3LE -> {
            val v = ((src[index + 2].toInt() shl 24) or
                ((src[index + 1].toInt() and 0xFF) shl 16) or
                ((src[index].toInt() and 0xFF) shl 8)) shr 8
            v * SCALE_24
        }

        PcmEncoding.S32_LE -> {
            val v = (src[index].toInt() and 0xFF) or
                ((src[index + 1].toInt() and 0xFF) shl 8) or
                ((src[index + 2].toInt() and 0xFF) shl 16) or
                (src[index + 3].toInt() shl 24)
            v * SCALE_32
        }
    }

    fun decode(
        src: ByteArray,
        srcOffset: Int,
        dest: FloatArray,
        destOffset: Int,
        frames: Int,
        channels: Int,
        encoding: PcmEncoding,
    ) {
        val samples = frames * channels
        when (encoding) {
            PcmEncoding.S24_3LE -> {
                var i = srcOffset
                var o = destOffset
                repeat(samples) {
                    // Assemble into the top 24 bits and arithmetic-shift back down, which sign
                    // extends without a branch on the high bit.
                    val v = ((src[i + 2].toInt() shl 24) or
                        ((src[i + 1].toInt() and 0xFF) shl 16) or
                        ((src[i].toInt() and 0xFF) shl 8)) shr 8
                    dest[o] = v * SCALE_24
                    i += 3
                    o++
                }
            }

            PcmEncoding.S32_LE -> {
                var i = srcOffset
                var o = destOffset
                repeat(samples) {
                    val v = (src[i].toInt() and 0xFF) or
                        ((src[i + 1].toInt() and 0xFF) shl 8) or
                        ((src[i + 2].toInt() and 0xFF) shl 16) or
                        (src[i + 3].toInt() shl 24)
                    dest[o] = v * SCALE_32
                    i += 4
                    o++
                }
            }
        }
    }
}

/**
 * Works out how many bytes make up one audio frame, from the packet sizes a device delivers.
 *
 * A device with no kernel quirk entry tells us nothing about its channel count. But an asynchronous
 * isochronous source always sends whole frames, and it varies how many it sends per packet to track
 * its own clock — at 48 kHz and 8000 packets a second, 6 frames then 7. Every packet length is
 * therefore a multiple of the frame size, and the greatest common divisor of a handful of differing
 * lengths converges on the frame size itself within a few milliseconds of audio.
 *
 * It converges on a *multiple* of the frame size if every packet happens to carry the same number
 * of frames, which is why [isConfident] requires having seen more than one distinct length.
 */
class FrameSizeDetector {

    private var divisor = 0
    private val lengths = mutableSetOf<Int>()

    val distinctLengths: Int get() = lengths.size

    /** True once differing packet sizes have pinned the frame size down. */
    val isConfident: Boolean get() = divisor > 0 && lengths.size >= 2

    val bytesPerFrame: Int get() = divisor

    fun observe(packetLength: Int) {
        if (packetLength <= 0) return
        if (lengths.size < MAX_TRACKED) lengths.add(packetLength)
        divisor = if (divisor == 0) packetLength else gcd(divisor, packetLength)
    }

    /** Channel count implied by the detected frame size, or null while still unsure. */
    fun channels(encoding: PcmEncoding): Int? {
        if (!isConfident) return null
        val bytes = encoding.bytesPerSample
        if (divisor % bytes != 0) return null
        return (divisor / bytes).takeIf { it in 1..64 }
    }

    fun reset() {
        divisor = 0
        lengths.clear()
    }

    private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

    private companion object {
        const val MAX_TRACKED = 16
    }
}
