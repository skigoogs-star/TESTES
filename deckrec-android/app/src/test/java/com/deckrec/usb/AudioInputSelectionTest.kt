package com.deckrec.usb

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the first decision the app makes when a user plugs something in.
 *
 * This is not hypothetical. A build that auto-selected an endpoint no third-party app may open
 * reached a real DJ, who saw three near-identical chips named after their phone, picked the
 * highlighted one, and got "the input refused to start" — while a Pioneer mixer sat plugged in and
 * unmentioned. The rules below are what stops that recurring.
 */
class AudioInputSelectionTest {

    private fun input(
        id: Int,
        name: String,
        type: Int,
        address: String = "",
    ) = AudioInput(
        id = id,
        productName = name,
        type = type,
        address = address,
        sampleRates = listOf(48000),
        channelCounts = listOf(2),
        channelIndexMasks = emptyList(),
        encodings = emptyList(),
    )

    private fun builtInMic(id: Int = 1) = input(id, "SM-S928W", AudioDeviceInfo.TYPE_BUILTIN_MIC)
    private fun usbMixer(id: Int = 2) = input(id, "DJM-A9", AudioDeviceInfo.TYPE_USB_DEVICE, "card=1;device=0")
    private fun telephony(id: Int = 3) = input(id, "SM-S928W", AudioDeviceInfo.TYPE_TELEPHONY)
    private fun echoReference(id: Int = 4) = input(id, "SM-S928W", AudioInput.TYPE_ECHO_REFERENCE)

    @Test
    fun `endpoints an app may never open are not recordable`() {
        assertFalse("telephony downlink", telephony().isRecordable)
        assertFalse("the echo reference has no public constant, hence type 28", echoReference().isRecordable)
        assertFalse(input(5, "x", AudioDeviceInfo.TYPE_REMOTE_SUBMIX).isRecordable)
        assertFalse(input(6, "x", AudioDeviceInfo.TYPE_FM_TUNER).isRecordable)
        assertTrue("the built-in mic always is", builtInMic().isRecordable)
        assertTrue(usbMixer().isRecordable)
    }

    @Test
    fun `selection never lands on an endpoint that cannot be opened`() {
        // The exact list an S24 Ultra reports, in the order that produced the field failure.
        val inputs = listOf(builtInMic(), telephony(), echoReference())
        val selected = AudioInput.select(inputs, preferredKey = null)

        assertEquals("the mic is the only thing here that opens", AudioDeviceInfo.TYPE_BUILTIN_MIC, selected?.type)
    }

    @Test
    fun `an unrecordable endpoint is refused even if it was somehow remembered`() {
        val ghost = echoReference()
        val selected = AudioInput.select(listOf(builtInMic(), ghost), preferredKey = ghost.key())

        assertEquals(
            "a saved preference must not resurrect an endpoint that cannot be opened",
            AudioDeviceInfo.TYPE_BUILTIN_MIC,
            selected?.type,
        )
    }

    @Test
    fun `a USB input wins over the built-in mic when nothing is remembered`() {
        assertEquals("DJM-A9", AudioInput.select(listOf(builtInMic(), usbMixer()), null)?.productName)
    }

    @Test
    fun `the remembered input survives the platform renumbering device ids`() {
        val before = usbMixer(id = 2)
        // Same hardware after a replug: the platform hands out a completely different id.
        val after = usbMixer(id = 77)

        val selected = AudioInput.select(listOf(builtInMic(3), after), preferredKey = before.key())

        assertEquals(77, selected?.id)
        assertEquals("keys must not contain the id", before.key(), after.key())
    }

    @Test
    fun `a remembered key that matches nothing falls back rather than selecting nothing`() {
        val selected = AudioInput.select(listOf(builtInMic()), preferredKey = "9|Some other mixer|card=4")
        assertEquals(AudioDeviceInfo.TYPE_BUILTIN_MIC, selected?.type)
    }

    @Test
    fun `two different endpoints on one phone do not share a key`() {
        // All three are named "SM-S928W"; only the type keeps them apart.
        val keys = listOf(builtInMic(), telephony(), echoReference()).map { it.key() }
        assertEquals("a saved preference must identify exactly one endpoint", 3, keys.toSet().size)
    }

    @Test
    fun `nothing selectable yields nothing rather than a broken selection`() {
        assertNull(AudioInput.select(emptyList(), null))
        assertNull("all unopenable is the same as none", AudioInput.select(listOf(telephony(), echoReference()), null))
    }
}
