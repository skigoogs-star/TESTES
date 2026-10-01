package com.deckrec.data

import android.content.Context
import com.deckrec.audio.RecorderConfig
import com.deckrec.usb.ChannelPair
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Everything the user can change that outlives a single recording. */
data class AppSettings(
    val sampleRate: Int = 48000,
    val format: RecordingFormat = RecordingFormat.WAV_24,
    val aacBitrateKbps: Int = 256,
    val inputGainDb: Float = 0f,
    val subBassAmount: Float = 0f,
    val loudnessAmount: Float = 0f,
    val limiterEnabled: Boolean = true,
    val autoMarkersEnabled: Boolean = true,
    val autoMarkerSensitivity: Float = 0.5f,
    val autoMarkerGapSeconds: Float = 45f,
    val autoSplitMinutes: Int = 0,
    val fileNamePrefix: String = "Set",
    /** Stable identity of the chosen input; platform device ids are reassigned across replug. */
    val preferredDeviceKey: String? = null,
    val channelPairLeft: Int = 0,
    val keepScreenOn: Boolean = true,
) {
    val channelPair: ChannelPair get() = ChannelPair(channelPairLeft, channelPairLeft + 1)

    fun toRecorderConfig(
        deviceId: Int?,
        deviceName: String,
        sourceChannelCount: Int,
        usbDeviceName: String? = null,
    ) = RecorderConfig(
        deviceId = deviceId,
        deviceName = deviceName,
        usbDeviceName = usbDeviceName,
        sampleRate = sampleRate,
        sourceChannelCount = sourceChannelCount,
        channelPair = channelPair,
        format = format,
        aacBitrateKbps = aacBitrateKbps,
        inputGainDb = inputGainDb,
        subBassAmount = subBassAmount,
        loudnessAmount = loudnessAmount,
        limiterEnabled = limiterEnabled,
        autoMarkersEnabled = autoMarkersEnabled,
        autoMarkerSensitivity = autoMarkerSensitivity,
        autoMarkerMinimumGapSeconds = autoMarkerGapSeconds,
        autoSplitMinutes = autoSplitMinutes,
        fileNamePrefix = fileNamePrefix,
    )

    companion object {
        val AAC_BITRATES = listOf(64, 96, 128, 192, 256, 320)
        val AUTO_SPLIT_OPTIONS = listOf(0, 30, 60, 90, 120)
    }
}

/** Reads and writes [AppSettings]; changes are visible immediately through [settings]. */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("deckrec.settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    val current: AppSettings get() = _settings.value

    fun update(transform: (AppSettings) -> AppSettings) {
        val updated = transform(_settings.value)
        write(updated)
        _settings.value = updated
    }

    private fun read(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            sampleRate = prefs.getInt(KEY_SAMPLE_RATE, defaults.sampleRate),
            format = runCatching {
                RecordingFormat.valueOf(prefs.getString(KEY_FORMAT, defaults.format.name)!!)
            }.getOrDefault(defaults.format),
            aacBitrateKbps = prefs.getInt(KEY_AAC_BITRATE, defaults.aacBitrateKbps),
            inputGainDb = prefs.getFloat(KEY_GAIN, defaults.inputGainDb),
            subBassAmount = prefs.getFloat(KEY_SUB_BASS, defaults.subBassAmount),
            loudnessAmount = prefs.getFloat(KEY_LOUDNESS, defaults.loudnessAmount),
            limiterEnabled = prefs.getBoolean(KEY_LIMITER, defaults.limiterEnabled),
            autoMarkersEnabled = prefs.getBoolean(KEY_AUTO_MARKERS, defaults.autoMarkersEnabled),
            autoMarkerSensitivity = prefs.getFloat(KEY_MARKER_SENSITIVITY, defaults.autoMarkerSensitivity),
            autoMarkerGapSeconds = prefs.getFloat(KEY_MARKER_GAP, defaults.autoMarkerGapSeconds),
            autoSplitMinutes = prefs.getInt(KEY_AUTO_SPLIT, defaults.autoSplitMinutes),
            fileNamePrefix = prefs.getString(KEY_PREFIX, defaults.fileNamePrefix) ?: defaults.fileNamePrefix,
            preferredDeviceKey = prefs.getString(KEY_DEVICE_KEY, null),
            channelPairLeft = prefs.getInt(KEY_CHANNEL_PAIR, defaults.channelPairLeft),
            keepScreenOn = prefs.getBoolean(KEY_KEEP_SCREEN_ON, defaults.keepScreenOn),
        )
    }

    private fun write(settings: AppSettings) {
        prefs.edit().apply {
            putInt(KEY_SAMPLE_RATE, settings.sampleRate)
            putString(KEY_FORMAT, settings.format.name)
            putInt(KEY_AAC_BITRATE, settings.aacBitrateKbps)
            putFloat(KEY_GAIN, settings.inputGainDb)
            putFloat(KEY_SUB_BASS, settings.subBassAmount)
            putFloat(KEY_LOUDNESS, settings.loudnessAmount)
            putBoolean(KEY_LIMITER, settings.limiterEnabled)
            putBoolean(KEY_AUTO_MARKERS, settings.autoMarkersEnabled)
            putFloat(KEY_MARKER_SENSITIVITY, settings.autoMarkerSensitivity)
            putFloat(KEY_MARKER_GAP, settings.autoMarkerGapSeconds)
            putInt(KEY_AUTO_SPLIT, settings.autoSplitMinutes)
            putString(KEY_PREFIX, settings.fileNamePrefix)
            putString(KEY_DEVICE_KEY, settings.preferredDeviceKey)
            putInt(KEY_CHANNEL_PAIR, settings.channelPairLeft)
            putBoolean(KEY_KEEP_SCREEN_ON, settings.keepScreenOn)
        }.apply()
    }

    private companion object {
        const val KEY_DEVICE_KEY = "deviceKey"
        const val KEY_SAMPLE_RATE = "sampleRate"
        const val KEY_FORMAT = "format"
        const val KEY_AAC_BITRATE = "aacBitrate"
        const val KEY_GAIN = "gainDb"
        const val KEY_SUB_BASS = "subBass"
        const val KEY_LOUDNESS = "loudness"
        const val KEY_LIMITER = "limiter"
        const val KEY_AUTO_MARKERS = "autoMarkers"
        const val KEY_MARKER_SENSITIVITY = "markerSensitivity"
        const val KEY_MARKER_GAP = "markerGap"
        const val KEY_AUTO_SPLIT = "autoSplit"
        const val KEY_PREFIX = "filePrefix"
        const val KEY_CHANNEL_PAIR = "channelPair"
        const val KEY_KEEP_SCREEN_ON = "keepScreenOn"
    }
}
