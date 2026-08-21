package com.deckrec.usb.host

import android.util.Log

/**
 * The thin JNI boundary to the isochronous capture code.
 *
 * Everything here is deliberately mechanical. The interesting parts of the capture path — parsing
 * descriptors, deciding the stream geometry, decoding samples, buffering between threads — are
 * Kotlin, because they can be proven correct on a JVM. What is left in C is only what Java cannot
 * express at all, and the less of it there is, the less of this app is untestable.
 */
object UsbIsoNative {

    private const val TAG = "DeckRec/UsbIso"

    /**
     * Whether the native library loaded and agrees with us about the kernel's structures.
     *
     * False is not fatal: the app still records from every input Android routes normally. It only
     * means direct capture from vendor-specific hardware is unavailable, which the UI can then say
     * rather than failing mysteriously when someone selects a mixer.
     */
    val isAvailable: Boolean by lazy { load() }

    private fun load(): Boolean {
        val loaded = runCatching { System.loadLibrary("deckrecusb") }
            .onFailure { Log.w(TAG, "Native USB capture unavailable: ${it.message}") }
            .isSuccess
        if (!loaded) return false

        return runCatching { verifyAbi() }
            .onFailure { Log.e(TAG, "Native USB capture rejected: ${it.message}") }
            .isSuccess
    }

    /**
     * Checks that the C structures are laid out as the transfer code assumes.
     *
     * `struct usbdevfs_urb` is handed to the kernel by pointer with a trailing array of packet
     * descriptors appended to it. If the sizes ever disagreed with expectation, every field the
     * kernel reads would be at the wrong offset, and the symptom would be corrupted audio or a
     * wedged stream rather than anything that pointed at the cause.
     */
    private fun verifyAbi() {
        val urbSize = urbStructSize()
        val packetSize = isoPacketStructSize()

        // 64-bit: 4 pointers/longs and several ints; 12 bytes for three unsigned ints per packet.
        require(urbSize in 40..80) { "unexpected usbdevfs_urb size: $urbSize" }
        require(packetSize == 12) { "unexpected usbdevfs_iso_packet_desc size: $packetSize" }
        require(isoUrbType() == 0) { "USBDEVFS_URB_TYPE_ISO is not 0" }
        require(selfTest() > 0) { "native self test failed" }

        Log.i(TAG, "Native USB capture ready (urb=$urbSize packet=$packetSize)")
    }

    // ---- Raw values from the kernel headers, for logging and for the ABI check ----------------

    @JvmStatic external fun urbStructSize(): Int
    @JvmStatic external fun isoPacketStructSize(): Int
    @JvmStatic external fun submitUrbCommand(): Long
    @JvmStatic external fun reapUrbNonBlockingCommand(): Long
    @JvmStatic external fun discardUrbCommand(): Long
    @JvmStatic external fun isoUrbType(): Int
    @JvmStatic external fun selfTest(): Int
}
