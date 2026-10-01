package com.deckrec.usb.host

/**
 * A control transfer, as plain numbers.
 *
 * Kept separate from [android.hardware.usb.UsbDeviceConnection.controlTransfer] so the exact bytes
 * put on the wire can be asserted in a unit test. These requests are undocumented by the vendor and
 * were recovered by capturing the Windows driver's traffic; a transposed argument would be
 * invisible in review and would put an arbitrary value into an unknown register on someone's mixer.
 */
data class ControlRequest(
    val requestType: Int,
    val request: Int,
    val value: Int,
    val index: Int,
    val data: ByteArray? = null,
    val length: Int = 0,
) {
    override fun equals(other: Any?): Boolean =
        other is ControlRequest &&
            requestType == other.requestType &&
            request == other.request &&
            value == other.value &&
            index == other.index &&
            length == other.length &&
            (data?.toList() == other.data?.toList())

    override fun hashCode(): Int =
        (((requestType * 31 + request) * 31 + value) * 31 + index) * 31 + length

    override fun toString(): String =
        "ControlRequest(bmRequestType=0x%02X bRequest=0x%02X wValue=0x%04X wIndex=0x%04X wLength=%d)"
            .format(requestType, request, value, index, length)
}

/** What a DJM-850-family mixer can route to a USB output pair. */
enum class DjmSource(val code: Int) {
    TIMECODE_CD_LINE(0x00),
    TIMECODE_LINE(0x01),
    TIMECODE_PHONO(0x03),
    POST_FADER(0x06),
    CROSSFADER_A(0x07),
    CROSSFADER_B(0x08),
    MIC(0x09),

    /** The mixer's record bus — what a recording app wants, and rarely the default. */
    REC_OUT(0x0A),
    NONE(0x0F),
}

/** USB output attenuation, as offered by the Windows setting utility. */
enum class DjmOutputLevel(val code: Int) {
    MINUS_19_DB(0x00),
    MINUS_15_DB(0x01),
    MINUS_10_DB(0x02),
    MINUS_5_DB(0x03),
}

/**
 * Vendor control requests for Pioneer DJ mixers.
 *
 * Two very different levels of confidence live here, and the distinction matters more than the
 * requests themselves:
 *
 * The **sample rate** request is from the Linux kernel (`pioneer_djm_set_format_quirk`), is exercised
 * by every Linux user of this hardware, and is a standard UAC1 SET_CUR aimed at the streaming
 * endpoint. Safe to send to any multi-rate device in the table.
 *
 * The **routing and level** requests were recovered from USB captures of the Windows utility for a
 * single model, the DJM-850. They write to registers whose meaning on other models is unknown. The
 * newer 0x2b73 family already demonstrably differs — its sample-rate request is addressed to a
 * different endpoint than the 0x08e4 one — so firing these at a DJM-V10 or an XDJ would be writing
 * an arbitrary value to an unknown register on hardware someone is about to perform on.
 * [supportsRouting] is the guard, and it is deliberately a whitelist.
 */
object DjmVendorControl {

    /** Vendor request, host to device, recipient device. */
    private const val VENDOR_OUT_DEVICE = 0x40

    /** Vendor request, device to host, recipient device. */
    private const val VENDOR_IN_DEVICE = 0xC0

    /** Class request, host to device, recipient endpoint — the UAC1 rate control. */
    private const val CLASS_OUT_ENDPOINT = 0x22

    private const val REQUEST_SET_REGISTER = 0x03
    private const val REQUEST_READ_STATE = 0x00
    private const val UAC_SET_CUR = 0x01
    private const val UAC_SAMPLING_FREQ_CONTROL = 0x0100

    private const val REGISTER_OUTPUT_ROUTING = 0x8002
    private const val REGISTER_OUTPUT_LEVEL = 0x8003

    /** Models whose register map is actually known, rather than assumed. */
    private val ROUTING_VERIFIED = setOf(
        0x08E4 to 0x0163, // DJM-850 — the model the captures were taken from
        0x08E4 to 0x017F, // DJM-750 — same generation, same utility; probable, not proven
    )

    fun supportsRouting(vendorId: Int, productId: Int): Boolean =
        (vendorId to productId) in ROUTING_VERIFIED

    /**
     * Sets the streaming sample rate.
     *
     * `wIndex` is the capture endpoint's address: 0x0082 across the 0x2b73 family, 0x0086 on the
     * older 0x08e4 DJM-750/850. The rate is three bytes, little-endian.
     */
    fun setSampleRate(rate: Int, captureEndpoint: Int) = ControlRequest(
        requestType = CLASS_OUT_ENDPOINT,
        request = UAC_SET_CUR,
        value = UAC_SAMPLING_FREQ_CONTROL,
        index = captureEndpoint,
        data = byteArrayOf(
            (rate and 0xFF).toByte(),
            ((rate shr 8) and 0xFF).toByte(),
            ((rate shr 16) and 0xFF).toByte(),
        ),
        length = 3,
    )

    /**
     * Routes a mixer source to one of the four USB output pairs. [pair] is 1..4 for USB 1/2 .. 7/8.
     *
     * Only ever send this where [supportsRouting] agrees.
     */
    fun setOutputRouting(pair: Int, source: DjmSource): ControlRequest {
        require(pair in 1..4) { "USB output pair must be 1..4, was $pair" }
        return ControlRequest(
            requestType = VENDOR_OUT_DEVICE,
            request = REQUEST_SET_REGISTER,
            value = (pair shl 8) or source.code,
            index = REGISTER_OUTPUT_ROUTING,
        )
    }

    fun setOutputLevel(level: DjmOutputLevel) = ControlRequest(
        requestType = VENDOR_OUT_DEVICE,
        request = REQUEST_SET_REGISTER,
        value = level.code shl 8,
        index = REGISTER_OUTPUT_LEVEL,
    )

    /**
     * Reads the four channel input selectors.
     *
     * The one request here with no side effects: the Windows utility polls it several times a
     * second purely to refresh its display. Hardware that does not implement it stalls, which
     * surfaces as a negative return and is itself an answer about which registers the device
     * speaks — which is why the inspector sends it to anything.
     */
    fun readInputSelectors() = ControlRequest(
        requestType = VENDOR_IN_DEVICE,
        request = REQUEST_READ_STATE,
        value = 0x0000,
        index = REGISTER_OUTPUT_ROUTING,
        length = 5,
    )

    /** What each channel's input selector is set to, from [readInputSelectors]. */
    enum class InputSelector { CD_LINE, LINE, PHONO, USB, UNKNOWN }

    /**
     * Decodes the five-byte reply: a leading byte of unknown meaning, then one per channel.
     *
     * A channel switched to USB is playing back *from* the computer, so it is a return path rather
     * than a source — recording it captures what was just sent out.
     */
    fun parseInputSelectors(data: ByteArray): List<InputSelector> =
        data.drop(1).map {
            when (it.toInt() and 0xFF) {
                0x00 -> InputSelector.CD_LINE
                0x01 -> InputSelector.LINE
                0x03 -> InputSelector.PHONO
                0x04 -> InputSelector.USB
                else -> InputSelector.UNKNOWN
            }
        }
}
