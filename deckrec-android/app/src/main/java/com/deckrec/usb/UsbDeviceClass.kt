package com.deckrec.usb

/**
 * One USB interface, reduced to the few facts that decide whether we can record from it.
 *
 * Deliberately plain integers rather than [android.hardware.usb.UsbInterface], so the
 * classification below is a pure function that can be tested against a real device's descriptor
 * layout with no phone attached.
 */
data class UsbInterfaceSummary(
    val interfaceClass: Int,
    val interfaceSubclass: Int,
    /** Isochronous IN endpoints on this interface — the shape an audio capture stream takes. */
    val isochronousInputs: Int = 0,
)

/** What a device is capable of, as far as recording is concerned. */
data class UsbAudioCapability(
    /** A standard AudioStreaming interface: the only thing Android's audio HAL will bind. */
    val hasAudioStreaming: Boolean,
    /** An AudioControl interface. Present on Pioneer mixers, and on its own means nothing to us. */
    val hasAudioControl: Boolean,
    /** A MIDI interface. Every DJ mixer and every MIDI keyboard has one. */
    val hasMidiStreaming: Boolean,
    /** A vendor-specific interface carrying an isochronous input — capturable, but only by us. */
    val hasVendorIsochronousInput: Boolean,
) {
    /**
     * True when the device declares USB class 1 anywhere, which is what a naive check tests for.
     *
     * Kept only so the distinction can be shown in diagnostics; never use it to decide anything.
     */
    val declaresAudioClass: Boolean get() = hasAudioStreaming || hasAudioControl || hasMidiStreaming

    /** Android will offer this device as a recording input. */
    val isRoutableByAndroid: Boolean get() = hasAudioStreaming

    /** Android will not, but the app's own USB capture path can. */
    val needsDirectCapture: Boolean get() = !hasAudioStreaming && hasVendorIsochronousInput
}

/**
 * Decides what a USB device can do for us from its interface descriptors.
 *
 * The subclass check is the whole point. A Pioneer DJM exposes an AudioControl interface (class 1,
 * subclass 1) and a MIDI interface (class 1, subclass 3), and puts its actual PCM on a
 * vendor-specific interface (class 0xFF) that Android's audio HAL ignores. Asking only "is there an
 * interface of class 1" therefore answers *yes* for exactly the hardware that cannot be recorded
 * through the platform — which inverts the diagnosis and sends the user replugging cables to fix a
 * condition that is by design. Only an AudioStreaming interface (subclass 2) means Android will
 * ever create a capture endpoint.
 */
object UsbDeviceClass {

    const val CLASS_AUDIO = 0x01
    const val CLASS_VENDOR_SPECIFIC = 0xFF

    const val SUBCLASS_AUDIO_CONTROL = 0x01
    const val SUBCLASS_AUDIO_STREAMING = 0x02
    const val SUBCLASS_MIDI_STREAMING = 0x03

    fun classify(interfaces: List<UsbInterfaceSummary>): UsbAudioCapability = UsbAudioCapability(
        hasAudioStreaming = interfaces.any {
            it.interfaceClass == CLASS_AUDIO && it.interfaceSubclass == SUBCLASS_AUDIO_STREAMING
        },
        hasAudioControl = interfaces.any {
            it.interfaceClass == CLASS_AUDIO && it.interfaceSubclass == SUBCLASS_AUDIO_CONTROL
        },
        hasMidiStreaming = interfaces.any {
            it.interfaceClass == CLASS_AUDIO && it.interfaceSubclass == SUBCLASS_MIDI_STREAMING
        },
        hasVendorIsochronousInput = interfaces.any {
            it.interfaceClass == CLASS_VENDOR_SPECIFIC && it.isochronousInputs > 0
        },
    )
}
