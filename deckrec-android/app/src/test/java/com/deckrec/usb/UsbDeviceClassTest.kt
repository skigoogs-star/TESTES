package com.deckrec.usb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the distinction that the whole USB diagnosis turns on.
 *
 * The layouts below are not invented: the mixer case is transcribed from a published `lsusb -v`
 * dump of a Pioneer DJM-850, which is the shape the DJM and XDJ families share.
 */
class UsbDeviceClassTest {

    /**
     * Pioneer DJM-850, as it really enumerates.
     *
     * Interface 0 alt 0 and alt 1 are vendor-specific — alt 1 carrying the isochronous audio —
     * while interfaces 1 and 2 declare USB class 1 for AudioControl and MIDI. That combination is
     * the trap: the device advertises the audio class and yet has no stream Android can bind.
     */
    private fun pioneerMixer() = listOf(
        UsbInterfaceSummary(interfaceClass = 0xFF, interfaceSubclass = 0x00, isochronousInputs = 0),
        UsbInterfaceSummary(interfaceClass = 0xFF, interfaceSubclass = 0x00, isochronousInputs = 1),
        UsbInterfaceSummary(interfaceClass = 0x01, interfaceSubclass = 0x01, isochronousInputs = 0),
        UsbInterfaceSummary(interfaceClass = 0x01, interfaceSubclass = 0x03, isochronousInputs = 0),
    )

    /** An ordinary class-compliant USB interface: control, plus a streaming interface with audio. */
    private fun classCompliantInterface() = listOf(
        UsbInterfaceSummary(interfaceClass = 0x01, interfaceSubclass = 0x01),
        UsbInterfaceSummary(interfaceClass = 0x01, interfaceSubclass = 0x02, isochronousInputs = 1),
    )

    private fun midiKeyboard() = listOf(
        UsbInterfaceSummary(interfaceClass = 0x01, interfaceSubclass = 0x01),
        UsbInterfaceSummary(interfaceClass = 0x01, interfaceSubclass = 0x03),
    )

    private fun usbHub() = listOf(UsbInterfaceSummary(interfaceClass = 0x09, interfaceSubclass = 0x00))

    @Test
    fun `a Pioneer mixer is not routable by Android however much class 1 it declares`() {
        val capability = UsbDeviceClass.classify(pioneerMixer())

        assertTrue("it does declare the audio class", capability.declaresAudioClass)
        assertTrue(capability.hasAudioControl)
        assertTrue(capability.hasMidiStreaming)
        assertFalse("but has no AudioStreaming interface", capability.hasAudioStreaming)
        assertFalse(
            "so Android will never create a capture endpoint for it",
            capability.isRoutableByAndroid,
        )
        assertTrue("while we can open it ourselves", capability.needsDirectCapture)
    }

    @Test
    fun `a class-compliant interface is left to the platform`() {
        val capability = UsbDeviceClass.classify(classCompliantInterface())
        assertTrue(capability.isRoutableByAndroid)
        assertFalse("no reason to bypass the platform for one of these", capability.needsDirectCapture)
    }

    @Test
    fun `a MIDI keyboard is neither`() {
        val capability = UsbDeviceClass.classify(midiKeyboard())
        assertTrue("it declares class 1, which is the trap", capability.declaresAudioClass)
        assertFalse(capability.isRoutableByAndroid)
        assertFalse("and offers nothing to capture", capability.needsDirectCapture)
    }

    @Test
    fun `a hub is not mistaken for a mixer`() {
        val capability = UsbDeviceClass.classify(usbHub())
        assertFalse(capability.declaresAudioClass)
        assertFalse(capability.isRoutableByAndroid)
        assertFalse(capability.needsDirectCapture)
    }

    @Test
    fun `a vendor-specific interface with no isochronous input is not capturable`() {
        // A DJ controller whose vendor interface is control-only, or a mixer in a mode that
        // exposes no stream. Claiming we can record it would be a lie.
        val capability = UsbDeviceClass.classify(
            listOf(UsbInterfaceSummary(interfaceClass = 0xFF, interfaceSubclass = 0x00))
        )
        assertFalse(capability.needsDirectCapture)
    }

    @Test
    fun `a device with nothing on it classifies as nothing`() {
        val capability = UsbDeviceClass.classify(emptyList())
        assertFalse(capability.declaresAudioClass)
        assertFalse(capability.isRoutableByAndroid)
        assertFalse(capability.needsDirectCapture)
    }
}
