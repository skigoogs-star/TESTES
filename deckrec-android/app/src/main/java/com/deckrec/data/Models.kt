package com.deckrec.data

import kotlinx.serialization.Serializable
import java.util.Locale

/** A cue point inside a recording. */
@Serializable
data class Marker(
    val id: String,
    val positionMs: Long,
    val label: String = "",
    val automatic: Boolean = false,
) {
    fun displayLabel(index: Int): String =
        label.ifBlank { "Track ${index + 1}" }
}

/** On-disk container and bit depth for a recording. */
@Serializable
enum class RecordingFormat(
    val displayName: String,
    val extension: String,
    val bitsPerSample: Int,
) {
    WAV_24("WAV 24-bit", "wav", 24),
    WAV_16("WAV 16-bit", "wav", 16),
    AAC("AAC", "m4a", 16);

    val isWav: Boolean get() = this == WAV_24 || this == WAV_16

    /** Bytes per second of audio, used for the remaining-time estimate. */
    fun bytesPerSecond(sampleRate: Int, channels: Int, aacBitrateKbps: Int): Long = when (this) {
        AAC -> aacBitrateKbps * 1000L / 8L
        else -> sampleRate.toLong() * channels * (bitsPerSample / 8)
    }
}

/** Everything the app knows about one finished (or in-progress) recording. */
@Serializable
data class RecordingMeta(
    val id: String,
    val fileName: String,
    val title: String = "",
    val artist: String = "",
    val genre: String = "",
    val tags: List<String> = emptyList(),
    val notes: String = "",
    val createdAtEpochMs: Long = 0L,
    val durationMs: Long = 0L,
    val sampleRate: Int = 48000,
    val channels: Int = 2,
    val format: RecordingFormat = RecordingFormat.WAV_24,
    val aacBitrateKbps: Int = 256,
    val sourceDeviceName: String = "",
    val markers: List<Marker> = emptyList(),
    val artworkFileName: String? = null,
    val peaksFileName: String? = null,
    val sizeBytes: Long = 0L,
    val partIndex: Int = 0,
) {
    fun displayTitle(): String = title.ifBlank { fileName.substringBeforeLast('.') }

    fun formatSummary(): String = buildString {
        append(format.displayName)
        if (format == RecordingFormat.AAC) append(" ${aacBitrateKbps}k")
        append(" · ")
        append(String.format(Locale.US, "%.1f kHz", sampleRate / 1000f))
    }
}

/** Snapshot of the running recorder, published to the UI. */
data class RecordingProgress(
    val elapsedMs: Long = 0L,
    val sizeBytes: Long = 0L,
    val markerCount: Int = 0,
    val partIndex: Int = 0,
)

fun formatDuration(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }
    return String.format(Locale.US, "%.1f %s", value, units[unitIndex])
}
