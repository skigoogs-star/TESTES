package com.deckrec.usb.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The exact bytes of the vendor control transfers.
 *
 * These are undocumented requests recovered from USB captures, so there is nothing to compare them
 * against at runtime and a transposed argument would look perfectly reasonable in review. The one
 * place the values can be checked is here, against the captures they came from.
 */
class DjmVendorControlTest {

    @Test
    fun `the sample rate request matches what the kernel sends`() {
        // pioneer_djm_set_format_quirk: SET_CUR / SAMPLING_FREQ_CONTROL to the streaming endpoint,
        // three bytes little-endian. 48000 = 0xBB80.
        val request = DjmVendorControl.setSampleRate(rate = 48000, captureEndpoint = 0x0082)

        assertEquals(0x22, request.requestType)
        assertEquals(0x01, request.request)
        assertEquals(0x0100, request.value)
        assertEquals(0x0082, request.index)
        assertEquals(3, request.length)
        assertEquals(listOf<Byte>(0x80.toByte(), 0xBB.toByte(), 0x00), request.data!!.toList())
    }

    @Test
    fun `high sample rates still fit three little-endian bytes`() {
        // 96000 = 0x017700, which needs all three bytes; a two-byte encoding would send 0x7700.
        assertEquals(
            listOf<Byte>(0x00, 0x77, 0x01),
            DjmVendorControl.setSampleRate(96000, 0x0082).data!!.toList(),
        )
        assertEquals(
            listOf<Byte>(0x44, 0xAC.toByte(), 0x00),
            DjmVendorControl.setSampleRate(44100, 0x0082).data!!.toList(),
        )
    }

    @Test
    fun `the older family is addressed to its own endpoint`() {
        // The one proven cross-family difference in this register space, and the reason the routing
        // map below is not assumed to carry over either.
        assertEquals(0x0086, DjmVendorControl.setSampleRate(48000, 0x0086).index)
    }

    @Test
    fun `routing USB 1-2 to the record bus is the captured request`() {
        val request = DjmVendorControl.setOutputRouting(pair = 1, source = DjmSource.REC_OUT)

        assertEquals(0x40, request.requestType)
        assertEquals(0x03, request.request)
        assertEquals("pair in the high byte, source in the low", 0x010A, request.value)
        assertEquals(0x8002, request.index)
        assertEquals("a write with no data stage", 0, request.length)
    }

    @Test
    fun `each output pair addresses itself`() {
        assertEquals(0x010A, DjmVendorControl.setOutputRouting(1, DjmSource.REC_OUT).value)
        assertEquals(0x020A, DjmVendorControl.setOutputRouting(2, DjmSource.REC_OUT).value)
        assertEquals(0x030A, DjmVendorControl.setOutputRouting(3, DjmSource.REC_OUT).value)
        assertEquals(0x040F, DjmVendorControl.setOutputRouting(4, DjmSource.NONE).value)
    }

    @Test
    fun `a pair outside the hardware's range is refused rather than sent`() {
        // Writing pair 5 would put 0x050A into a register that has four pairs; better to fail here.
        assertThrows(IllegalArgumentException::class.java) {
            DjmVendorControl.setOutputRouting(5, DjmSource.REC_OUT)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DjmVendorControl.setOutputRouting(0, DjmSource.REC_OUT)
        }
    }

    @Test
    fun `the output level request is the captured one`() {
        val request = DjmVendorControl.setOutputLevel(DjmOutputLevel.MINUS_5_DB)
        assertEquals(0x40, request.requestType)
        assertEquals(0x03, request.request)
        assertEquals(0x0300, request.value)
        assertEquals(0x8003, request.index)
        assertEquals(0x0000, DjmVendorControl.setOutputLevel(DjmOutputLevel.MINUS_19_DB).value)
    }

    @Test
    fun `the input selector read is a read`() {
        val request = DjmVendorControl.readInputSelectors()
        assertEquals("device to host, or it would be a write", 0xC0, request.requestType)
        assertEquals(0x00, request.request)
        assertEquals(0x8002, request.index)
        assertEquals(5, request.length)
    }

    @Test
    fun `input selectors decode, and a USB channel is recognisable`() {
        val selectors = DjmVendorControl.parseInputSelectors(byteArrayOf(0x00, 0x00, 0x01, 0x03, 0x04))

        assertEquals(4, selectors.size)
        assertEquals(DjmVendorControl.InputSelector.CD_LINE, selectors[0])
        assertEquals(DjmVendorControl.InputSelector.LINE, selectors[1])
        assertEquals(DjmVendorControl.InputSelector.PHONO, selectors[2])
        // Channel 4 is playing back from the computer; recording it returns what was just sent.
        assertEquals(DjmVendorControl.InputSelector.USB, selectors[3])
    }

    @Test
    fun `an unexpected selector value is not guessed at`() {
        val selectors = DjmVendorControl.parseInputSelectors(byteArrayOf(0x00, 0x7F, 0x02, 0x00, 0x00))
        assertEquals(DjmVendorControl.InputSelector.UNKNOWN, selectors[0])
        assertEquals(DjmVendorControl.InputSelector.UNKNOWN, selectors[1])
    }

    @Test
    fun `a short reply does not throw`() {
        assertEquals(0, DjmVendorControl.parseInputSelectors(byteArrayOf(0x00)).size)
        assertEquals(0, DjmVendorControl.parseInputSelectors(ByteArray(0)).size)
    }

    // ---- The guard that matters most --------------------------------------------------------

    @Test
    fun `routing is only ever sent to the model it was captured from`() {
        assertTrue("DJM-850, the captured model", DjmVendorControl.supportsRouting(0x08E4, 0x0163))
        assertTrue("DJM-750, same generation and utility", DjmVendorControl.supportsRouting(0x08E4, 0x017F))
    }

    @Test
    fun `the newer family is never sent routing writes`() {
        // These registers are unverified on 0x2b73 hardware. The family already differs in the one
        // register we can compare, so a write here is an arbitrary value into an unknown register
        // on a mixer someone is about to perform on.
        assertFalse("DJM-V10", DjmVendorControl.supportsRouting(0x2B73, 0x0034))
        assertFalse("DJM-A9", DjmVendorControl.supportsRouting(0x2B73, 0x003C))
        assertFalse("DJM-900NXS2", DjmVendorControl.supportsRouting(0x2B73, 0x000A))
        assertFalse("XDJ-RX3", DjmVendorControl.supportsRouting(0x2B73, 0x003D))
        assertFalse("anything unknown", DjmVendorControl.supportsRouting(0x1234, 0x5678))
    }

    @Test
    fun `every model that allows routing is one we have endpoint data for`() {
        PioneerQuirks.ALL
            .filter { DjmVendorControl.supportsRouting(it.vendorId, it.productId) }
            .forEach { quirk ->
                assertEquals(
                    "${quirk.name} routing is only claimed for the 0x08e4 generation",
                    0x08E4,
                    quirk.vendorId,
                )
            }
    }
}
