package com.deckrec.audio

import com.deckrec.data.RecordingFormat
import com.deckrec.usb.ChannelPair

/** Everything the engine needs to open a stream and start writing. */
data class RecorderConfig(
    val deviceId: Int? = null,
    val deviceName: String = "",
    val sampleRate: Int = 48000,
    val sourceChannelCount: Int = 2,
    val channelPair: ChannelPair = ChannelPair.FIRST,
    val format: RecordingFormat = RecordingFormat.WAV_24,
    val aacBitrateKbps: Int = 256,
    val inputGainDb: Float = 0f,
    val subBassAmount: Float = 0f,
    val loudnessAmount: Float = 0f,
    val limiterEnabled: Boolean = true,
    val autoMarkersEnabled: Boolean = true,
    val autoMarkerSensitivity: Float = 0.5f,
    val autoMarkerMinimumGapSeconds: Float = 45f,
    val autoSplitMinutes: Int = 0,
    val fileNamePrefix: String = "Set",
) {
    val autoSplitEnabled: Boolean get() = autoSplitMinutes > 0
}

/** Lifecycle of the recorder, surfaced to the UI. */
sealed interface RecorderState {
    data object Idle : RecorderState
    data object Starting : RecorderState
    data class Recording(val paused: Boolean) : RecorderState
    data object Stopping : RecorderState
    data class Failed(val message: String) : RecorderState
}
