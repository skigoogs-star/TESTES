package com.deckrec.usb.host

/**
 * Everything needed to set up an isochronous capture stream, resolved from a device.
 *
 * Built either from a [PioneerQuirk] (the known-model fast path) or from the descriptors alone,
 * which is how hardware missing from the table is handled — see [probe].
 */
data class UsbStreamProfile(
    val interfaceNumber: Int,
    val alternateSetting: Int,
    val endpointAddress: Int,
    val maxPacketSize: Int,
    val interval: Int,
    val channels: Int,
    val encoding: PcmEncoding,
    val rate: Int,
    val rateControlIndex: Int?,
    val modelName: String?,
    /** False when the channel count and encoding are assumptions rather than table entries. */
    val channelsAreProvisional: Boolean,
) {
    val bytesPerFrame: Int get() = channels * encoding.bytesPerSample

    /**
     * Isochronous service intervals per second.
     *
     * A high-speed endpoint is serviced once per microframe, so `bInterval` of 1 gives 8000 slots a
     * second and each increment halves that. The Pioneer hardware is USB 2.0 high speed throughout;
     * a full-speed device would be serviced 1000 times a second instead, which only changes how
     * many bytes we expect per packet, not the mechanics.
     */
    val packetsPerSecond: Int
        get() = 8000 shr ((interval.coerceIn(1, 4)) - 1)

    /** Bytes the device should average per packet. Real packets vary either side of this. */
    val nominalPacketBytes: Int
        get() = bytesPerFrame * rate / packetsPerSecond

    /**
     * Bytes to reserve per packet slot.
     *
     * An asynchronous source decides for itself how many frames each packet carries — at 48 kHz and
     * 8000 packets a second it alternates 6 and 7 frames — so every slot has to be big enough for
     * the largest packet the endpoint may send, and the actual length is read back afterwards.
     */
    val slotBytes: Int get() = maxPacketSize.coerceAtLeast(nominalPacketBytes)

    companion object {
        /** 2 ms of audio per URB: short enough to keep reap latency low, long enough to be cheap. */
        const val PACKETS_PER_URB = 16

        /**
         * 64 URBs — about 128 ms of kernel-side buffering.
         *
         * The engine's writer thread can block a capture read for up to half a second under disk
         * pressure, so this alone is not the whole safety margin; [ByteRingBuffer] carries the rest.
         * What this depth buys is immunity to ordinary scheduler jitter on the pump thread.
         */
        const val URBS_IN_FLIGHT = 64

        /**
         * Resolve a stream from what the device says about itself.
         *
         * [quirk] short-circuits everything except the endpoint's packet geometry, which always
         * comes from the descriptors: the kernel table records what the stream *is*, not how the
         * bus is configured on this particular phone.
         */
        fun resolve(
            descriptors: UsbDescriptors.UsbDeviceDescriptors,
            quirk: PioneerQuirk?,
            preferredRate: Int,
        ): UsbStreamProfile? {
            if (quirk != null) {
                val iface = descriptors.interfaceAt(quirk.interfaceNumber, quirk.alternateSetting)
                val endpoint = iface?.endpoints?.firstOrNull { it.address == quirk.captureEndpoint }
                if (endpoint != null) {
                    return UsbStreamProfile(
                        interfaceNumber = quirk.interfaceNumber,
                        alternateSetting = quirk.alternateSetting,
                        endpointAddress = endpoint.address,
                        maxPacketSize = endpoint.maxPacketSize,
                        interval = endpoint.interval,
                        channels = quirk.captureChannels,
                        encoding = quirk.encoding,
                        rate = quirk.defaultRate(preferredRate),
                        rateControlIndex = quirk.rateControlIndex,
                        modelName = quirk.name,
                        channelsAreProvisional = false,
                    )
                }
                // The device is in the table but not shaped like the table says. Trusting the table
                // over the hardware here would submit URBs against an endpoint that may not exist,
                // so fall through and probe instead.
            }
            return probe(descriptors, preferredRate)
        }

        /**
         * Best guess for a device with no table entry, such as an XDJ the kernel has never seen.
         *
         * The descriptors give the endpoint, its packet size and its interval; they say nothing
         * about channel count or sample encoding, because a vendor-specific interface carries no
         * format descriptors — that is the whole reason the kernel needs hand-written entries. So
         * the channel count here is a placeholder derived from the packet size, flagged
         * [channelsAreProvisional], and expected to be replaced by [FrameSizeDetector] once real
         * packets have arrived.
         */
        fun probe(
            descriptors: UsbDescriptors.UsbDeviceDescriptors,
            preferredRate: Int,
        ): UsbStreamProfile? {
            val (iface, endpoint) = descriptors.isochronousInputCandidates().firstOrNull()
                ?: return null
            val encoding = PcmEncoding.S24_3LE
            val packetsPerSecond = 8000 shr ((endpoint.interval.coerceIn(1, 4)) - 1)
            // Upper bound on channels that could fit in one packet at this rate, rounded to an even
            // number because every known device is an even multiple of stereo pairs.
            val ceiling = endpoint.maxPacketSize * packetsPerSecond /
                (preferredRate.coerceAtLeast(1) * encoding.bytesPerSample)
            val channels = (ceiling - (ceiling % 2)).coerceIn(2, 32)
            return UsbStreamProfile(
                interfaceNumber = iface.number,
                alternateSetting = iface.alternateSetting,
                endpointAddress = endpoint.address,
                maxPacketSize = endpoint.maxPacketSize,
                interval = endpoint.interval,
                channels = channels,
                encoding = encoding,
                rate = preferredRate,
                rateControlIndex = null,
                modelName = null,
                channelsAreProvisional = true,
            )
        }
    }
}
