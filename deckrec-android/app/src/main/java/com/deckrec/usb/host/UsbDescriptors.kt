package com.deckrec.usb.host

/**
 * A minimal USB descriptor reader, working on the raw bytes rather than the framework's parse.
 *
 * `UsbDeviceConnection.getRawDescriptors()` hands back the device descriptor followed by the
 * configuration descriptor and everything nested under it, in wire order and little-endian. The
 * framework does expose the same information through [android.hardware.usb.UsbInterface] and
 * [android.hardware.usb.UsbEndpoint], and this app uses those objects for claiming — but the
 * numbers that decide the isochronous transfer geometry are read from here instead, for two
 * reasons: the raw bytes are the same on every phone, whereas the framework parser's treatment of
 * unusual vendor descriptors is not, and a `ByteArray` in, data class out is testable on the JVM
 * with no device attached.
 */
object UsbDescriptors {

    const val TYPE_DEVICE = 0x01
    const val TYPE_CONFIG = 0x02
    const val TYPE_INTERFACE = 0x04
    const val TYPE_ENDPOINT = 0x05

    const val XFER_ISOCHRONOUS = 1

    /** Bits 11-12 of wMaxPacketSize: additional transactions per microframe, high-speed only. */
    private const val PACKET_SIZE_MASK = 0x7FF

    fun parse(raw: ByteArray): UsbDeviceDescriptors {
        var device: DeviceDescriptor? = null
        val interfaces = mutableListOf<InterfaceDescriptor>()
        var current: MutableInterface? = null

        fun flush() {
            current?.let { interfaces += it.build() }
            current = null
        }

        var o = 0
        while (o + 2 <= raw.size) {
            val length = raw.u8(o)
            // A zero bLength cannot be advanced past; anything else self-describes its size. Both
            // guards matter: this is attacker-adjacent data in the sense that a malfunctioning
            // device can produce it, and an infinite loop here would hang the UI thread.
            if (length < 2 || o + length > raw.size) break
            when (raw.u8(o + 1)) {
                TYPE_DEVICE -> if (length >= 18) {
                    device = DeviceDescriptor(
                        usbVersion = raw.u16(o + 2),
                        deviceClass = raw.u8(o + 4),
                        vendorId = raw.u16(o + 8),
                        productId = raw.u16(o + 10),
                    )
                }

                TYPE_INTERFACE -> if (length >= 9) {
                    flush()
                    current = MutableInterface(
                        number = raw.u8(o + 2),
                        alternateSetting = raw.u8(o + 3),
                        endpointCount = raw.u8(o + 4),
                        interfaceClass = raw.u8(o + 5),
                        // Subclass, not class, is what separates an AudioStreaming interface from
                        // the AudioControl and MIDI ones a Pioneer mixer also declares.
                        interfaceSubclass = raw.u8(o + 6),
                    )
                }

                TYPE_ENDPOINT -> if (length >= 7) {
                    val packetField = raw.u16(o + 4)
                    current?.endpoints?.add(
                        EndpointDescriptor(
                            address = raw.u8(o + 2),
                            attributes = raw.u8(o + 3),
                            maxPacketSizeField = packetField,
                            interval = raw.u8(o + 6),
                        )
                    )
                }
                // Class-specific (0x24/0x25) and vendor descriptors are skipped by bLength; the
                // point of reading raw bytes is not to trip over the ones we do not understand.
            }
            o += length
        }
        flush()
        return UsbDeviceDescriptors(device, interfaces)
    }

    private fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xFF
    private fun ByteArray.u16(index: Int): Int = u8(index) or (u8(index + 1) shl 8)

    private class MutableInterface(
        val number: Int,
        val alternateSetting: Int,
        val endpointCount: Int,
        val interfaceClass: Int,
        val interfaceSubclass: Int,
    ) {
        val endpoints = mutableListOf<EndpointDescriptor>()
        fun build() = InterfaceDescriptor(
            number, alternateSetting, endpointCount, interfaceClass, interfaceSubclass,
            endpoints.toList(),
        )
    }

    data class DeviceDescriptor(
        val usbVersion: Int,
        val deviceClass: Int,
        val vendorId: Int,
        val productId: Int,
    )

    data class InterfaceDescriptor(
        val number: Int,
        val alternateSetting: Int,
        val endpointCount: Int,
        val interfaceClass: Int,
        val interfaceSubclass: Int,
        val endpoints: List<EndpointDescriptor>,
    ) {
        val isVendorSpecific: Boolean get() = interfaceClass == 0xFF
    }

    data class EndpointDescriptor(
        val address: Int,
        val attributes: Int,
        /** Raw wMaxPacketSize, mult bits included — use [maxPacketSize] for the byte count. */
        val maxPacketSizeField: Int,
        val interval: Int,
    ) {
        val isInput: Boolean get() = (address and 0x80) != 0
        val transferType: Int get() = attributes and 0x03

        /** 0 async, 1 adaptive, 2 sync... as encoded in bmAttributes bits 2-3. */
        val syncType: Int get() = (attributes shr 2) and 0x03

        /** 2 means the endpoint doubles as the implicit feedback source for the other direction. */
        val usageType: Int get() = (attributes shr 4) and 0x03

        val isIsochronous: Boolean get() = transferType == XFER_ISOCHRONOUS

        /**
         * Bytes the endpoint can deliver per service interval.
         *
         * High-speed endpoints may ask for up to three transactions per microframe; the extra count
         * lives in bits 11-12 of the same field and has to be multiplied back in. None of the known
         * Pioneer hardware uses it — 12 channels of 24-bit at 96 kHz is 432 bytes, comfortably
         * inside one 512-byte transaction — but a device that did and was parsed without this would
         * silently truncate two thirds of every packet.
         */
        val maxPacketSize: Int
            get() = (maxPacketSizeField and PACKET_SIZE_MASK) *
                (1 + ((maxPacketSizeField shr 11) and 0x03))
    }

    data class UsbDeviceDescriptors(
        val device: DeviceDescriptor?,
        val interfaces: List<InterfaceDescriptor>,
    ) {
        fun interfaceAt(number: Int, alternateSetting: Int): InterfaceDescriptor? =
            interfaces.firstOrNull { it.number == number && it.alternateSetting == alternateSetting }

        /**
         * Alternate settings that carry an isochronous IN endpoint, most endpoints first.
         *
         * Used when the device is not in the quirk table: the streaming alternate setting is the
         * one with the isochronous endpoints, and alternate setting 0 is required by the spec to
         * have none, so this reliably skips it without special-casing.
         */
        fun isochronousInputCandidates(): List<Pair<InterfaceDescriptor, EndpointDescriptor>> =
            interfaces
                .flatMap { iface ->
                    iface.endpoints
                        .filter { it.isInput && it.isIsochronous }
                        .map { iface to it }
                }
                .sortedByDescending { (_, endpoint) -> endpoint.maxPacketSize }
    }
}
