package com.deckrec.ui

import android.media.AudioDeviceInfo
import com.deckrec.usb.AudioInput
import com.deckrec.usb.UsbAudioCapability
import com.deckrec.usb.UsbDiagnostics
import com.deckrec.usb.UsbHardware
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The advice shown under the input chips, pinned against real hardware situations.
 *
 * Every case here has been observed in the field on a Galaxy S24 Ultra. The branch order is the
 * whole point: two of these conditions can be true at once, and telling a Pioneer owner to try a
 * different cable — for a device whose USB class means no cable will ever help — is the specific
 * failure this test exists to prevent.
 */
class ConnectionAdviceTest {

    private fun hardware(
        name: String,
        vendorId: Int,
        productId: Int = 0x0034,
        audioStreaming: Boolean = false,
        vendorIso: Boolean = false,
        audioControl: Boolean = false,
        midi: Boolean = false,
    ) = UsbHardware(
        deviceName = "/dev/bus/usb/001/002",
        productName = name,
        manufacturerName = "",
        vendorId = vendorId,
        productId = productId,
        hasPermission = true,
        capability = UsbAudioCapability(
            hasAudioStreaming = audioStreaming,
            hasAudioControl = audioControl,
            hasMidiStreaming = midi,
            hasVendorIsochronousInput = vendorIso,
        ),
    )

    /** A DJM-V10 as it really presents: class 1 for control and MIDI, audio on class 0xFF. */
    private fun djmV10(vendorIso: Boolean = true) = hardware(
        name = "DJM-V10",
        vendorId = 0x2B73,
        productId = 0x0034,
        audioStreaming = false,
        vendorIso = vendorIso,
        audioControl = true,
        midi = true,
    )

    private fun mic() = AudioInput(
        id = 1,
        productName = "SM-S928W",
        type = AudioDeviceInfo.TYPE_BUILTIN_MIC,
        address = "",
        sampleRates = listOf(48000),
        channelCounts = listOf(2),
        channelIndexMasks = emptyList(),
        encodings = emptyList(),
    )

    private fun state(
        diagnostics: UsbDiagnostics,
        inputs: List<AudioInput> = listOf(mic()),
    ) = RecordUiState(inputs = inputs, diagnostics = diagnostics, selectedInput = inputs.firstOrNull())

    @Test
    fun `a Pioneer mixer is named as needing direct capture, not blamed on the cable`() {
        val advice = state(
            UsbDiagnostics(hostSupported = true, busDevices = listOf(djmV10()))
        ).connectionAdvice()

        assertTrue(advice!!.title.contains("DJM-V10"))
        assertTrue(advice.title.contains("direct USB capture"))
        assertTrue("the reason must be stated", advice.detail.contains("vendor-specific"))
        assertTrue("we can open it, and should say so", advice.detail.contains("isochronous audio stream"))
        assertTrue(
            "must never suggest a different cable for this hardware",
            !advice.detail.contains("different cable"),
        )
    }

    @Test
    fun `a Pioneer unit with no stream found asks for a report instead of guessing`() {
        val advice = state(
            UsbDiagnostics(hostSupported = true, busDevices = listOf(djmV10(vendorIso = false)))
        ).connectionAdvice()

        assertTrue(advice!!.detail.contains("send the report"))
    }

    @Test
    fun `declaring USB class 1 for MIDI does not make a mixer look routable`() {
        // The trap: hasAudioControl and hasMidiStreaming are both true on this hardware. Keying on
        // the class rather than the subclass sent the user replugging cables forever.
        val advice = state(
            UsbDiagnostics(hostSupported = true, busDevices = listOf(djmV10()))
        ).connectionAdvice()

        assertTrue(advice!!.title.contains("direct USB capture"))
    }

    @Test
    fun `a phone with no host support is told so before anything else`() {
        val advice = state(
            UsbDiagnostics(hostSupported = false, busDevices = listOf(djmV10()))
        ).connectionAdvice()

        assertTrue("no mixer advice can help a phone that cannot host", advice!!.title.contains("cannot host"))
    }

    @Test
    fun `being enumerated by another host is called out as the wrong port`() {
        val advice = state(
            UsbDiagnostics(
                hostSupported = true,
                usbConnected = true,
                usbConfigured = true,
                busDevices = emptyList(),
            )
        ).connectionAdvice()

        assertTrue(advice!!.title.contains("acting as a USB device"))
        // Named by connector shape, not by one mixer's silkscreen: a DJM-V10 has no thumb-drive
        // slot, so telling its owner to leave one sent them hunting for a socket that is not there.
        assertTrue("must name the shape to move to", advice.detail.contains("USB-B"))
        assertTrue("and the shape to move away from", advice.detail.contains("USB-A"))
    }

    @Test
    fun `a plain charger is not reported as a mixer on the wrong port`() {
        // VBUS present, never enumerated: the wall-charger case that produced a false verdict.
        val advice = state(
            UsbDiagnostics(
                hostSupported = true,
                usbConnected = true,
                usbConfigured = false,
                busDevices = emptyList(),
            )
        ).connectionAdvice()

        assertTrue("hedged, not asserted", advice!!.title.contains("Powered over USB"))
        assertTrue(advice.detail.contains("just a charger"))
    }

    @Test
    fun `a hub is never described as a connected mixer`() {
        val hub = hardware(name = "USB2.0 Hub", vendorId = 0x05E3, productId = 0x0608)
        val advice = state(UsbDiagnostics(hostSupported = true, busDevices = listOf(hub))).connectionAdvice()

        assertNull("a hub is not audio-capable and warrants no advice at all", advice)
    }

    @Test
    fun `a class-compliant interface Android has not routed gets the replug suggestion`() {
        val iface = hardware(name = "Scarlett Solo", vendorId = 0x1235, audioStreaming = true)
        val advice = state(UsbDiagnostics(hostSupported = true, busDevices = listOf(iface))).connectionAdvice()

        assertTrue(advice!!.title.contains("Android has not exposed it"))
        assertTrue("here a cable really can be the problem", advice.detail.contains("different cable"))
    }

    @Test
    fun `a routed USB input produces no advice at all`() {
        val iface = hardware(name = "Scarlett Solo", vendorId = 0x1235, audioStreaming = true)
        val usbInput = AudioInput(
            id = 9,
            productName = "Scarlett Solo",
            type = AudioDeviceInfo.TYPE_USB_DEVICE,
            address = "card=1;device=0",
            sampleRates = listOf(48000),
            channelCounts = listOf(2),
            channelIndexMasks = emptyList(),
            encodings = emptyList(),
        )
        val advice = state(
            UsbDiagnostics(hostSupported = true, busDevices = listOf(iface)),
            inputs = listOf(usbInput),
        ).connectionAdvice()

        assertNull("nothing is wrong, so say nothing", advice)
    }

    @Test
    fun `nothing connected at all produces no advice`() {
        assertNull(state(UsbDiagnostics(hostSupported = true)).connectionAdvice())
    }
}
