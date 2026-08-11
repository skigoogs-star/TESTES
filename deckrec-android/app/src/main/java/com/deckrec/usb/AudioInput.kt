package com.deckrec.usb

import android.media.AudioDeviceInfo
import android.media.AudioFormat

/**
 * A snapshot of an audio capture endpoint the platform is willing to record from.
 *
 * Deliberately holds only plain values (no [AudioDeviceInfo] reference) so it is safe to keep in
 * Compose state; call [UsbAudioScanner.deviceInfoFor] when the live handle is needed.
 */
data class AudioInput(
    val id: Int,
    val productName: String,
    val type: Int,
    val address: String,
    val sampleRates: List<Int>,
    val channelCounts: List<Int>,
    val channelIndexMasks: List<Int>,
    val encodings: List<Int>,
) {
    val isUsb: Boolean
        get() = type == AudioDeviceInfo.TYPE_USB_DEVICE ||
            type == AudioDeviceInfo.TYPE_USB_HEADSET ||
            type == AudioDeviceInfo.TYPE_USB_ACCESSORY

    /**
     * Whether an ordinary app can actually record from this endpoint.
     *
     * `GET_DEVICES_INPUTS` also reports things like the telephony downlink and the echo reference,
     * which no third-party app may open. Offering them as choices produces a phone with one
     * microphone showing three identical-looking "Input" chips, any of which can be auto-selected
     * and then silently fail to deliver a single frame.
     */
    val isRecordable: Boolean
        get() = type !in UNRECORDABLE_TYPES

    /** Stable across replug and reboot, unlike [id], which the platform reassigns. */
    fun key(): String = "$type|$productName|$address"

    fun describe(): String = buildString {
        append("id $id · ${typeLabel()} (type $type)")
        val rates = sampleRates.joinToString(",").ifEmpty { "any" }
        append(" · ${rates}Hz")
        append(" · ${channelCounts.joinToString(",").ifEmpty { "?" }}ch")
        if (!isRecordable) append(" · not recordable")
    }

    /**
     * Highest channel count the endpoint advertises. USB mixers such as the DJM series expose the
     * master bus plus per-channel sends, so this is frequently far more than 2.
     */
    val maxChannelCount: Int
        get() = channelCounts.maxOrNull()
            ?: channelIndexMasks.maxOfOrNull { Integer.bitCount(it) }
            ?: 2

    /** Sample rates worth offering; an empty platform list means "anything reasonable". */
    fun usableSampleRates(): List<Int> =
        if (sampleRates.isEmpty()) DEFAULT_RATES
        else sampleRates.filter { it in DEFAULT_RATES }.ifEmpty { sampleRates }.sorted()

    fun supportsFloat(): Boolean =
        encodings.isEmpty() || encodings.contains(AudioFormat.ENCODING_PCM_FLOAT)

    fun typeLabel(): String = when (type) {
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB audio"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB accessory"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in mic"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
        else -> "Input"
    }

    companion object {
        val DEFAULT_RATES = listOf(44100, 48000, 88200, 96000)

        /**
         * Endpoints the platform reports but will not let a normal app open.
         *
         * The echo reference (type 28) has no public constant — it is a system API — so it is
         * matched by value rather than by name.
         */
        const val TYPE_ECHO_REFERENCE = 28

        val UNRECORDABLE_TYPES = setOf(
            AudioDeviceInfo.TYPE_TELEPHONY,
            AudioDeviceInfo.TYPE_REMOTE_SUBMIX,
            AudioDeviceInfo.TYPE_FM_TUNER,
            AudioDeviceInfo.TYPE_TV_TUNER,
            TYPE_ECHO_REFERENCE,
        )
    }
}

/** How the capture stream picks channels out of a multi-channel USB endpoint. */
data class ChannelPair(val left: Int, val right: Int) {
    val indexMask: Int get() = (1 shl left) or (1 shl right)
    fun label(): String = "Ch ${left + 1}/${right + 1}"

    companion object {
        val FIRST = ChannelPair(0, 1)

        /** All adjacent stereo pairs available on an endpoint with [channels] channels. */
        fun pairsFor(channels: Int): List<ChannelPair> =
            (0 until channels - 1 step 2).map { ChannelPair(it, it + 1) }.ifEmpty { listOf(FIRST) }
    }
}
