package com.deckrec.usb.host

/**
 * Sample encodings the Pioneer/AlphaTheta hardware uses on the wire.
 *
 * Both are little-endian and interleaved; they differ only in how many bytes carry one sample.
 */
enum class PcmEncoding(val bytesPerSample: Int) {
    /** 24-bit packed into three bytes. Everything except the DDJ-SX3. */
    S24_3LE(3),

    /** 32-bit, with the sample left-justified in the top 24 bits. DDJ-SX3. */
    S32_LE(4),
}

/**
 * A known vendor-specific USB audio device and how to stream from it.
 *
 * These units do not present a USB Audio Class interface — their PCM lives on interfaces declaring
 * class 0xFF — so nothing about the stream can be read out of standard descriptors. The values here
 * are transcribed from the hand-written entries in the Linux kernel's `sound/usb/quirks-table.h`,
 * which exist for exactly this reason and are the only published description of these formats.
 *
 * The table is a fast path, not a requirement: [UsbStreamProfile.probe] can derive the same shape
 * at runtime from the descriptors plus the packet sizes the device actually delivers, which is how
 * hardware missing from this list is handled.
 */
data class PioneerQuirk(
    val vendorId: Int,
    val productId: Int,
    val name: String,
    val captureEndpoint: Int,
    val captureChannels: Int,
    val encoding: PcmEncoding,
    val rates: List<Int>,
    val interfaceNumber: Int = 0,
    val alternateSetting: Int = 1,
) {
    val bytesPerFrame: Int get() = captureChannels * encoding.bytesPerSample

    /** Devices with a single fixed rate ignore any rate request; do not send one. */
    val rateIsFixed: Boolean get() = rates.size == 1

    /**
     * `wIndex` for the vendor sample-rate control transfer, or null if this device does not take
     * one.
     *
     * The kernel sends this only for a handful of multi-rate models
     * (`pioneer_djm_set_format_quirk` in `sound/usb/quirks.c`), keyed by the capture endpoint:
     * 0x0082 across the 0x2b73 family and 0x0086 for the older 0x08e4 DJM-750/850. Fixed-rate
     * devices are not in that switch and are not sent anything.
     *
     * Note the DJM-A9 (0x2b73:0x003c) advertises three rates yet is absent from the kernel's
     * switch. Rather than assume that is deliberate, the same endpoint-keyed command is offered for
     * any multi-rate device here and its failure is tolerated — see `UsbIsoSession`.
     */
    val rateControlIndex: Int? get() = if (rateIsFixed) null else captureEndpoint

    fun defaultRate(preferred: Int): Int = if (preferred in rates) preferred else rates.first()
}

/**
 * Every Pioneer DJ / AlphaTheta entry in the kernel quirk table, capture side only.
 *
 * Playback endpoints are recorded in the comments because the capture stream on several of these
 * units is the implicit feedback clock for playback, which matters if capture ever turns out to
 * need the output stream running to produce data.
 */
object PioneerQuirks {

    /** Pioneer DJ, Pioneer Corp and AlphaTheta. Shared with the scanner's bus-side detection. */
    val VENDOR_IDS = setOf(0x2B73, 0x08E4, 0x08E6, 0x29BA)

    private val R44 = listOf(44100)
    private val R48 = listOf(48000)
    private val R44_48_96 = listOf(44100, 48000, 96000)

    val ALL = listOf(
        // --- Mixers -------------------------------------------------------------------------
        // playback ep 0x01, 10ch
        PioneerQuirk(0x2B73, 0x000A, "DJM-900NXS2", 0x82, 12, PcmEncoding.S24_3LE, R44_48_96),
        // playback ep 0x01, 8ch
        PioneerQuirk(0x2B73, 0x0013, "DJM-450", 0x82, 8, PcmEncoding.S24_3LE, R48),
        // playback ep 0x01, 8ch
        PioneerQuirk(0x2B73, 0x0017, "DJM-250MK2", 0x82, 8, PcmEncoding.S24_3LE, R48),
        // playback ep 0x01, 10ch
        PioneerQuirk(0x2B73, 0x001B, "DJM-750MK2", 0x82, 12, PcmEncoding.S24_3LE, R48),
        // playback ep 0x01, 12ch
        PioneerQuirk(0x2B73, 0x0034, "DJM-V10", 0x82, 12, PcmEncoding.S24_3LE, R44_48_96),
        // playback ep 0x01, 10ch
        PioneerQuirk(0x2B73, 0x003C, "DJM-A9", 0x82, 12, PcmEncoding.S24_3LE, R44_48_96),
        // playback ep 0x05, 8ch
        PioneerQuirk(0x08E4, 0x017F, "DJM-750", 0x86, 8, PcmEncoding.S24_3LE, R44_48_96),
        // playback ep 0x05, 8ch
        PioneerQuirk(0x08E4, 0x0163, "DJM-850", 0x86, 8, PcmEncoding.S24_3LE, R44_48_96),

        // --- Controllers --------------------------------------------------------------------
        // playback ep 0x05, 12ch. The only S32_LE device in the table.
        PioneerQuirk(0x2B73, 0x0023, "DDJ-SX3", 0x86, 10, PcmEncoding.S32_LE, R44),
        // playback ep 0x01, 4ch. Its two capture channels are a dummy feedback stream, not audio.
        PioneerQuirk(0x2B73, 0x000E, "DDJ-RB", 0x82, 2, PcmEncoding.S24_3LE, R44),
        // playback ep 0x01, 6ch
        PioneerQuirk(0x2B73, 0x000D, "DDJ-RR", 0x82, 4, PcmEncoding.S24_3LE, R44),
        // playback ep 0x01, 4ch
        PioneerQuirk(0x2B73, 0x001E, "DDJ-SR2", 0x82, 6, PcmEncoding.S24_3LE, R44),
        // playback ep 0x01, 6ch
        PioneerQuirk(0x2B73, 0x0029, "DDJ-800", 0x82, 6, PcmEncoding.S24_3LE, R44),
    )

    private val byId = ALL.associateBy { it.vendorId to it.productId }

    fun find(vendorId: Int, productId: Int): PioneerQuirk? = byId[vendorId to productId]

    fun isKnownVendor(vendorId: Int): Boolean = vendorId in VENDOR_IDS
}
