package com.deckrec.usb.host

import android.util.Log

/** A snapshot of what the isochronous stream has actually been doing. */
data class UsbIsoStats(
    val bytesReceived: Long,
    val dataPackets: Long,
    val emptyPackets: Long,
    val errorPackets: Long,
    val overflowPackets: Long,
    val droppedBytes: Long,
    /** Greatest common divisor of the delivered packet sizes: the frame size, once it converges. */
    val packetSizeGcd: Int,
    val distinctPacketSizes: Int,
    val disconnected: Boolean,
    val lastErrno: Int,
) {
    /**
     * Packets arrived, but every one was empty.
     *
     * The fingerprint of hardware that accepts the stream and then never sends anything — which
     * would mean capture is gated on something the descriptors do not mention. Worth telling apart
     * from "no packets at all", which is a transfer failure, and from errors, which are a bus
     * problem.
     */
    val streamingButSilent: Boolean
        get() = dataPackets == 0L && emptyPackets > 0L && errorPackets == 0L

    fun measuredRate(bytesPerFrame: Int, elapsedMillis: Long): Int {
        if (bytesPerFrame <= 0 || elapsedMillis <= 0) return 0
        return ((bytesReceived / bytesPerFrame) * 1000 / elapsedMillis).toInt()
    }

    fun describe(): String =
        "$bytesReceived B, packets $dataPackets/$emptyPackets/$errorPackets " +
            "(data/empty/error), dropped $droppedBytes B, gcd $packetSizeGcd"
}

/**
 * The JNI boundary to the isochronous capture code.
 *
 * Everything here is deliberately mechanical. The interesting parts of the capture path — parsing
 * descriptors, deciding the stream geometry, decoding samples, selecting channels — are Kotlin,
 * because they can be proven correct on a JVM. What is left in C is only what Java cannot express
 * at all, and the less of it there is, the less of this app is untestable.
 */
object UsbIsoNative {

    private const val TAG = "DeckRec/UsbIso"

    /**
     * Whether the native library loaded and agrees with us about the kernel's structures.
     *
     * False is not fatal: the app still records from every input Android routes normally. It only
     * means direct capture from vendor-specific hardware is unavailable, which the UI can say
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
     * kernel reads would be at the wrong offset, and the symptom would be corrupted audio rather
     * than anything that pointed at the cause.
     */
    private fun verifyAbi() {
        val urbSize = urbStructSize()
        val packetSize = isoPacketStructSize()

        require(urbSize in 40..80) { "unexpected usbdevfs_urb size: $urbSize" }
        require(packetSize == 12) { "unexpected usbdevfs_iso_packet_desc size: $packetSize" }
        require(isoUrbType() == 0) { "USBDEVFS_URB_TYPE_ISO is not 0" }
        require(selfTest() > 0) { "native self test failed" }

        Log.i(TAG, "Native USB capture ready (urb=$urbSize packet=$packetSize)")
    }

    fun statsFor(handle: Long): UsbIsoStats {
        val values = LongArray(10)
        nativeStats(handle, values)
        return UsbIsoStats(
            bytesReceived = values[0],
            dataPackets = values[1],
            emptyPackets = values[2],
            errorPackets = values[3],
            overflowPackets = values[4],
            droppedBytes = values[5],
            packetSizeGcd = values[6].toInt(),
            distinctPacketSizes = values[7].toInt(),
            disconnected = values[8] != 0L,
            lastErrno = values[9].toInt(),
        )
    }

    /**
     * Claims the endpoint and starts streaming. Returns an opaque handle, or 0 on failure.
     *
     * The interface must already be claimed and switched to its streaming alternate setting; this
     * only submits transfers. [frameBytes] of 1 means the frame size is not yet known, in which
     * case reads are not aligned to frame boundaries.
     */
    @JvmStatic external fun nativeStart(
        fileDescriptor: Int,
        endpointAddress: Int,
        slotBytes: Int,
        packetsPerUrb: Int,
        urbCount: Int,
        frameBytes: Int,
        ringCapacityBytes: Int,
    ): Long

    /** Bytes copied (always whole frames), 0 on timeout, -1 once disconnected and drained. */
    @JvmStatic external fun nativeRead(
        handle: Long,
        dest: ByteArray,
        offset: Int,
        maxBytes: Int,
        minBytes: Int,
        timeoutMs: Int,
    ): Int

    @JvmStatic external fun nativeStats(handle: Long, out: LongArray)

    /** Most URBs reaped in one wakeup; approaching the queue depth means the pump nearly starved. */
    @JvmStatic external fun nativeReapHighWater(handle: Long): Int

    @JvmStatic external fun nativeStop(handle: Long)

    @JvmStatic external fun urbStructSize(): Int
    @JvmStatic external fun isoPacketStructSize(): Int
    @JvmStatic external fun isoUrbType(): Int
    @JvmStatic external fun selfTest(): Int
}
