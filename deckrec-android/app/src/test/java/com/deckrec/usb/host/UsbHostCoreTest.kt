package com.deckrec.usb.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Everything about the direct USB capture path that can be checked without a phone attached.
 *
 * The split is deliberate: the descriptor parse, the transfer geometry, the sample conversion, the
 * frame-size inference and the ring buffer are all pure functions over plain types, so they are
 * exercised here, and the code that touches `UsbManager` and the kernel is kept as thin and
 * logic-free as possible because none of it can be reached from a JVM test.
 */
class UsbHostCoreTest {

    // ---- Descriptor parsing ----------------------------------------------------------------

    /**
     * A DJM-A9 as it appears on the wire: device descriptor, configuration, interface 0 with its
     * empty alternate setting 0, then alternate setting 1 carrying the playback and capture
     * isochronous endpoints.
     */
    private fun djmA9Descriptors(): ByteArray = buildDescriptors {
        device(vendorId = 0x2B73, productId = 0x003C)
        config(interfaceCount = 1)
        iface(number = 0, alt = 0, endpoints = 0, cls = 0xFF)
        iface(number = 0, alt = 1, endpoints = 2, cls = 0xFF)
        endpoint(address = 0x01, attributes = 0x05, maxPacket = 512, interval = 1)
        endpoint(address = 0x82, attributes = 0x25, maxPacket = 512, interval = 1)
    }

    @Test
    fun `parses a vendor-specific device down to its isochronous capture endpoint`() {
        val parsed = UsbDescriptors.parse(djmA9Descriptors())

        assertEquals(0x2B73, parsed.device?.vendorId)
        assertEquals(0x003C, parsed.device?.productId)
        assertEquals(2, parsed.interfaces.size)

        val streaming = parsed.interfaceAt(0, 1)
        assertNotNull(streaming)
        assertTrue("class 0xFF is the whole reason Android ignores this device", streaming!!.isVendorSpecific)

        val capture = streaming.endpoints.single { it.isInput }
        assertEquals(0x82, capture.address)
        assertTrue(capture.isIsochronous)
        assertEquals("asynchronous sync type", 1, capture.syncType)
        assertEquals("implicit feedback usage", 2, capture.usageType)
        assertEquals(512, capture.maxPacketSize)
    }

    @Test
    fun `alternate setting zero is skipped when hunting for a stream`() {
        val candidates = UsbDescriptors.parse(djmA9Descriptors()).isochronousInputCandidates()
        assertEquals(1, candidates.size)
        assertEquals(1, candidates.first().first.alternateSetting)
    }

    @Test
    fun `high-bandwidth multiplier bits are folded into the packet size`() {
        // wMaxPacketSize 0x1400 = 1024 bytes with two additional transactions per microframe.
        val raw = buildDescriptors {
            device(vendorId = 0x2B73, productId = 0x0001)
            config(interfaceCount = 1)
            iface(number = 0, alt = 1, endpoints = 1, cls = 0xFF)
            endpoint(address = 0x82, attributes = 0x05, maxPacket = 1024 or (2 shl 11), interval = 1)
        }
        val endpoint = UsbDescriptors.parse(raw).interfaceAt(0, 1)!!.endpoints.single()
        assertEquals("three transactions of 1024", 3072, endpoint.maxPacketSize)
    }

    @Test
    fun `a truncated descriptor block stops cleanly instead of running off the end`() {
        val full = djmA9Descriptors()
        for (cut in 1 until full.size) {
            // Any prefix at all must terminate and never throw; a malfunctioning device producing
            // one of these would otherwise take the UI thread down with it.
            UsbDescriptors.parse(full.copyOf(cut))
        }
    }

    @Test
    fun `a zero length descriptor cannot spin the parser forever`() {
        val raw = byteArrayOf(0x12, 0x01) + ByteArray(16) + byteArrayOf(0x00, 0x04, 0x00)
        val parsed = UsbDescriptors.parse(raw)
        assertTrue(parsed.interfaces.isEmpty())
    }

    // ---- Stream geometry -------------------------------------------------------------------

    @Test
    fun `the A9 resolves from its quirk entry at the requested rate`() {
        val quirk = PioneerQuirks.find(0x2B73, 0x003C)
        assertNotNull(quirk)

        val profile = UsbStreamProfile.resolve(
            UsbDescriptors.parse(djmA9Descriptors()), quirk, preferredRate = 48000
        )
        assertNotNull(profile)
        profile!!

        assertEquals("DJM-A9", profile.modelName)
        assertEquals(0x82, profile.endpointAddress)
        assertEquals(12, profile.channels)
        assertEquals(48000, profile.rate)
        assertEquals(36, profile.bytesPerFrame)
        assertFalse(profile.channelsAreProvisional)

        assertEquals("one microframe apart", 8000, profile.packetsPerSecond)
        assertEquals("12ch * 3B * 6 frames per microframe", 216, profile.nominalPacketBytes)
        assertEquals("slots must fit the endpoint's largest packet", 512, profile.slotBytes)
    }

    @Test
    fun `an unsupported rate falls back to one the device actually offers`() {
        val quirk = PioneerQuirks.find(0x2B73, 0x001B)!! // DJM-750MK2, 48k only
        assertEquals(48000, quirk.defaultRate(preferred = 96000))
        assertNull("fixed-rate devices are sent no rate command", quirk.rateControlIndex)
    }

    @Test
    fun `multi-rate mixers address the rate command to their capture endpoint`() {
        assertEquals(0x0082, PioneerQuirks.find(0x2B73, 0x0034)!!.rateControlIndex) // DJM-V10
        assertEquals(0x0086, PioneerQuirks.find(0x08E4, 0x0163)!!.rateControlIndex) // DJM-850
    }

    @Test
    fun `a device missing from the table still yields a usable stream`() {
        // Shaped like an XDJ: no quirk entry, so channels have to be inferred later.
        val raw = buildDescriptors {
            device(vendorId = 0x2B73, productId = 0x003D)
            config(interfaceCount = 1)
            iface(number = 0, alt = 0, endpoints = 0, cls = 0xFF)
            iface(number = 0, alt = 1, endpoints = 1, cls = 0xFF)
            endpoint(address = 0x82, attributes = 0x25, maxPacket = 512, interval = 1)
        }
        val profile = UsbStreamProfile.resolve(UsbDescriptors.parse(raw), quirk = null, preferredRate = 48000)
        assertNotNull(profile)
        assertEquals(0x82, profile!!.endpointAddress)
        assertTrue("channel count is a guess until packets arrive", profile.channelsAreProvisional)
        assertTrue("but a plausible one", profile.channels in 2..32)
        assertEquals(0, profile.channels % 2)
    }

    @Test
    fun `a table entry that disagrees with the hardware defers to the hardware`() {
        // Claims to be a DJM-A9 but exposes the endpoint somewhere else entirely.
        val raw = buildDescriptors {
            device(vendorId = 0x2B73, productId = 0x003C)
            config(interfaceCount = 1)
            iface(number = 2, alt = 1, endpoints = 1, cls = 0xFF)
            endpoint(address = 0x84, attributes = 0x05, maxPacket = 256, interval = 1)
        }
        val profile = UsbStreamProfile.resolve(
            UsbDescriptors.parse(raw), PioneerQuirks.find(0x2B73, 0x003C), preferredRate = 48000
        )
        assertNotNull(profile)
        assertEquals("must not submit against an endpoint that is not there", 0x84, profile!!.endpointAddress)
        assertEquals(2, profile.interfaceNumber)
    }

    @Test
    fun `every table entry is internally consistent`() {
        PioneerQuirks.ALL.forEach { quirk ->
            assertTrue("${quirk.name} capture endpoint must be an IN address", (quirk.captureEndpoint and 0x80) != 0)
            assertTrue("${quirk.name} channel count", quirk.captureChannels in 2..32)
            assertTrue("${quirk.name} rates", quirk.rates.isNotEmpty())
            assertTrue("${quirk.name} vendor is one the scanner recognises", PioneerQuirks.isKnownVendor(quirk.vendorId))
        }
        assertEquals("no duplicate ids", PioneerQuirks.ALL.size, PioneerQuirks.ALL.distinctBy { it.vendorId to it.productId }.size)
    }

    // ---- Sample conversion -----------------------------------------------------------------

    @Test
    fun `24-bit packed samples decode with the right sign and scale`() {
        val cases = listOf(
            byteArrayOf(0x00, 0x00, 0x00) to 0f,
            byteArrayOf(0x00, 0x00, 0x40) to 0.5f,
            byteArrayOf(0x00, 0x00, 0x80.toByte()) to -1f,
            byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0x7F) to 0.99999988f,
            byteArrayOf(0x00, 0x00, 0xC0.toByte()) to -0.5f,
        )
        cases.forEach { (bytes, expected) ->
            val out = FloatArray(1)
            PcmDecode.decode(bytes, 0, out, 0, frames = 1, channels = 1, encoding = PcmEncoding.S24_3LE)
            assertTrue("expected $expected got ${out[0]}", abs(out[0] - expected) < 1e-6f)
        }
    }

    @Test
    fun `32-bit samples decode with the right sign and scale`() {
        val out = FloatArray(2)
        val src = byteArrayOf(0, 0, 0, 0x40, 0, 0, 0, 0x80.toByte())
        PcmDecode.decode(src, 0, out, 0, frames = 2, channels = 1, encoding = PcmEncoding.S32_LE)
        assertTrue(abs(out[0] - 0.5f) < 1e-6f)
        assertTrue(abs(out[1] - (-1f)) < 1e-6f)
    }

    @Test
    fun `interleaving survives a full multi-channel frame`() {
        val channels = 12
        val src = ByteArray(channels * 3)
        for (ch in 0 until channels) {
            // Distinct positive value per channel, in the top byte.
            src[ch * 3 + 2] = (ch * 4).toByte()
        }
        val out = FloatArray(channels)
        PcmDecode.decode(src, 0, out, 0, frames = 1, channels = channels, encoding = PcmEncoding.S24_3LE)
        for (ch in 0 until channels) {
            val expected = (ch * 4) * 65536 / 8_388_608f
            assertTrue("channel $ch", abs(out[ch] - expected) < 1e-6f)
        }
    }

    @Test
    fun `decoding respects the offsets it is given`() {
        val src = byteArrayOf(0x7F, 0x7F, 0x7F) + byteArrayOf(0x00, 0x00, 0x40)
        val out = FloatArray(3)
        PcmDecode.decode(src, 3, out, 1, frames = 1, channels = 1, encoding = PcmEncoding.S24_3LE)
        assertEquals(0f, out[0], 0f)
        assertTrue(abs(out[1] - 0.5f) < 1e-6f)
        assertEquals(0f, out[2], 0f)
    }

    // ---- Frame size inference --------------------------------------------------------------

    @Test
    fun `alternating packet sizes reveal the frame size`() {
        val detector = FrameSizeDetector()
        // A 12-channel 24-bit stream at 48 kHz: 6 then 7 frames per microframe.
        listOf(216, 216, 252, 216, 252, 216).forEach(detector::observe)

        assertTrue(detector.isConfident)
        assertEquals(36, detector.bytesPerFrame)
        assertEquals(12, detector.channels(PcmEncoding.S24_3LE))
    }

    @Test
    fun `identical packet sizes are not enough to be sure`() {
        val detector = FrameSizeDetector()
        repeat(50) { detector.observe(216) }
        assertFalse("216 could be 6 frames of 36 or 1 frame of 216", detector.isConfident)
        assertNull(detector.channels(PcmEncoding.S24_3LE))
    }

    @Test
    fun `empty packets are ignored rather than collapsing the divisor`() {
        val detector = FrameSizeDetector()
        listOf(0, 216, 0, 252, 0).forEach(detector::observe)
        assertEquals("a zero-length packet would make every gcd 216", 36, detector.bytesPerFrame)
        assertTrue(detector.isConfident)
    }

    @Test
    fun `an eight channel stream is detected just as well`() {
        val detector = FrameSizeDetector()
        // 8ch * 3B = 24 bytes per frame at 48 kHz: 144 and 168.
        listOf(144, 168, 144, 168).forEach(detector::observe)
        assertEquals(24, detector.bytesPerFrame)
        assertEquals(8, detector.channels(PcmEncoding.S24_3LE))
    }

    @Test
    fun `a frame size that does not divide by the sample width is rejected`() {
        val detector = FrameSizeDetector()
        listOf(50, 75).forEach(detector::observe) // gcd 25, not a multiple of 3
        assertTrue(detector.isConfident)
        assertNull(detector.channels(PcmEncoding.S24_3LE))
    }

    // ---- Ring buffer -----------------------------------------------------------------------

    @Test
    fun `bytes come out in the order they went in, across the wrap`() {
        val ring = ByteRingBuffer(64)
        val out = ByteArray(64)
        var written = 0
        var read = 0

        repeat(20) {
            val chunk = ByteArray(10) { i -> (written + i).toByte() }
            ring.write(chunk, 0, chunk.size)
            written += 10

            val n = ring.read(out, 0, out.size, minimum = 1, timeoutMs = 0)
            for (i in 0 until n) {
                assertEquals("byte $read", (read + i).toByte(), out[i])
            }
            read += n
        }
        assertEquals(written, read)
    }

    @Test
    fun `overflow drops the newest bytes and says how many`() {
        val ring = ByteRingBuffer(10)
        assertEquals(10, ring.write(ByteArray(10) { 1 }, 0, 10))
        assertEquals("nothing fits", 0, ring.write(ByteArray(4) { 2 }, 0, 4))
        assertEquals(4, ring.droppedBytes)

        val out = ByteArray(10)
        ring.read(out, 0, 10, minimum = 1, timeoutMs = 0)
        // The bytes kept are the older ones; the discontinuity is at the end, not spliced in.
        assertTrue(out.all { it == 1.toByte() })
    }

    @Test
    fun `a partial write is reported honestly`() {
        val ring = ByteRingBuffer(10)
        ring.write(ByteArray(6), 0, 6)
        assertEquals("only four of the eight fit", 4, ring.write(ByteArray(8), 0, 8))
        assertEquals(4, ring.droppedBytes)
    }

    @Test
    fun `a read that cannot be satisfied times out instead of hanging`() {
        val ring = ByteRingBuffer(64)
        ring.write(ByteArray(4), 0, 4)
        val started = System.nanoTime()
        val n = ring.read(ByteArray(64), 0, 64, minimum = 32, timeoutMs = 40)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertEquals("takes what is there once the wait expires", 4, n)
        assertTrue("waited about 40ms, took ${elapsedMs}ms", elapsedMs in 20..400)
    }

    @Test
    fun `closing wakes a blocked reader and then reports the end of the stream`() {
        val ring = ByteRingBuffer(64)
        ring.write(ByteArray(8), 0, 8)
        ring.close()

        assertEquals("drains what is left first", 8, ring.read(ByteArray(64), 0, 64, 1, 0))
        assertEquals("then reports the stream is done", -1, ring.read(ByteArray(64), 0, 64, 1, 0))
        assertEquals("and never blocks again", 0, ring.write(ByteArray(8), 0, 8))
    }

    @Test
    fun `a producer and a consumer on different threads lose nothing`() {
        val ring = ByteRingBuffer(1024)
        val total = 200_000
        var read = 0L
        var mismatch = -1

        val producer = Thread {
            var written = 0
            val chunk = ByteArray(97)
            while (written < total) {
                for (i in chunk.indices) chunk[i] = ((written + i) % 251).toByte()
                val length = minOf(chunk.size, total - written)
                // Wait for room rather than letting write() drop: a short write is a *loss*, not
                // something to retry, so retrying one would double-count the bytes.
                while (ring.remaining < length) Thread.sleep(0, 200_000)
                ring.write(chunk, 0, length)
                written += length
            }
            ring.close()
        }
        producer.start()

        val out = ByteArray(512)
        while (true) {
            val n = ring.read(out, 0, out.size, minimum = 1, timeoutMs = 200)
            if (n < 0) break
            for (i in 0 until n) {
                if (out[i] != ((read + i) % 251).toByte() && mismatch < 0) mismatch = (read + i).toInt()
            }
            read += n
        }
        producer.join(5_000)

        assertEquals("no reordering or duplication at byte $mismatch", -1, mismatch)
        assertEquals("dropping means the ring was too small for this test", 0, ring.droppedBytes)
        assertEquals(total.toLong(), read)
    }

    // ---- Descriptor blob builder -----------------------------------------------------------

    private fun buildDescriptors(block: DescriptorBuilder.() -> Unit): ByteArray =
        DescriptorBuilder().apply(block).bytes()

    private class DescriptorBuilder {
        private val out = mutableListOf<Byte>()

        fun device(vendorId: Int, productId: Int) {
            out += listOf<Byte>(18, 0x01, 0x00, 0x02, 0xFF.toByte(), 0x00, 0x00, 0x40)
            out += lo(vendorId); out += hi(vendorId)
            out += lo(productId); out += hi(productId)
            out += listOf<Byte>(0x00, 0x01, 0x01, 0x02, 0x03, 0x01)
        }

        fun config(interfaceCount: Int) {
            // wTotalLength is left at zero: the parser walks descriptors by bLength and must not
            // depend on a field a real device is free to get wrong.
            out += listOf<Byte>(9, 0x02, 0x00, 0x00, interfaceCount.toByte(), 0x01, 0x00, 0x80.toByte(), 0x32)
        }

        fun iface(number: Int, alt: Int, endpoints: Int, cls: Int) {
            out += listOf<Byte>(
                9, 0x04, number.toByte(), alt.toByte(), endpoints.toByte(),
                cls.toByte(), 0x00, 0x00, 0x00,
            )
        }

        fun endpoint(address: Int, attributes: Int, maxPacket: Int, interval: Int) {
            out += listOf<Byte>(7, 0x05, address.toByte(), attributes.toByte())
            out += lo(maxPacket); out += hi(maxPacket)
            out += interval.toByte()
        }

        fun bytes(): ByteArray = out.toByteArray()

        private fun lo(v: Int): Byte = (v and 0xFF).toByte()
        private fun hi(v: Int): Byte = ((v shr 8) and 0xFF).toByte()
    }
}
