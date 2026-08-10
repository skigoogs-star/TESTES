package com.deckrec.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.deckrec.audio.RecorderConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Owns the recordings directory and the JSON sidecar next to each file.
 *
 * Sidecars rather than a database: a set is a single self-contained file that a DJ will want to
 * copy off the phone, and keeping its markers and metadata in a plain file beside it means nothing
 * is lost when they do. It also means a crash mid-set leaves a readable library.
 */
class RecordingStore(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    val recordingsDir: File by lazy {
        (context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: File(context.filesDir, "Music"))
            .apply { mkdirs() }
    }

    private val metaDir: File by lazy { File(recordingsDir, "meta").apply { mkdirs() } }

    val artworkDir: File by lazy {
        File(context.getExternalFilesDir(null), "artwork").apply { mkdirs() }
    }

    private val _recordings = MutableStateFlow<List<RecordingMeta>>(emptyList())
    val recordings: StateFlow<List<RecordingMeta>> = _recordings.asStateFlow()

    data class RecordingTarget(val id: String, val audioFile: File, val peaksFile: File)

    /** Allocates the files for a new recording (or the next part of one). */
    fun newRecordingTarget(config: RecorderConfig, partIndex: Int): RecordingTarget {
        val id = UUID.randomUUID().toString()
        val stamp = SimpleDateFormat("yyyy-MM-dd HH-mm", Locale.US).format(Date())
        val prefix = sanitise(config.fileNamePrefix.ifBlank { "Set" })
        val suffix = if (partIndex > 0) " (part ${partIndex + 1})" else ""
        var base = "$prefix $stamp$suffix"
        var candidate = File(recordingsDir, "$base.${config.format.extension}")
        var counter = 2
        while (candidate.exists()) {
            base = "$prefix $stamp$suffix ($counter)"
            candidate = File(recordingsDir, "$base.${config.format.extension}")
            counter++
        }
        return RecordingTarget(
            id = id,
            audioFile = candidate,
            peaksFile = File(metaDir, "$id.peaks"),
        )
    }

    fun save(meta: RecordingMeta) {
        runCatching {
            File(metaDir, "${meta.id}.json").writeText(json.encodeToString(RecordingMeta.serializer(), meta))
        }
        refresh()
    }

    fun delete(meta: RecordingMeta) {
        runCatching { audioFile(meta).delete() }
        runCatching { meta.peaksFileName?.let { File(metaDir, it).delete() } }
        runCatching { meta.artworkFileName?.let { File(artworkDir, it).delete() } }
        runCatching { File(metaDir, "${meta.id}.json").delete() }
        refresh()
    }

    fun refresh() {
        val metas = metaDir.listFiles { file -> file.extension == "json" }
            ?.mapNotNull { file ->
                runCatching {
                    json.decodeFromString(RecordingMeta.serializer(), file.readText())
                }.getOrNull()
            }
            ?.filter { audioFile(it).isFile }
            ?.map { it.copy(sizeBytes = audioFile(it).length()) }
            ?.sortedByDescending { it.createdAtEpochMs }
            ?: emptyList()
        _recordings.value = metas
    }

    fun find(id: String): RecordingMeta? = _recordings.value.firstOrNull { it.id == id }

    fun audioFile(meta: RecordingMeta): File = File(recordingsDir, meta.fileName)

    fun peaksFile(meta: RecordingMeta): File? =
        meta.peaksFileName?.let { File(metaDir, it) }?.takeIf { it.isFile }

    fun artworkFile(meta: RecordingMeta): File? =
        meta.artworkFileName?.let { File(artworkDir, it) }?.takeIf { it.isFile }

    /** Renames the underlying audio file to match a new title, keeping the sidecar in step. */
    fun rename(meta: RecordingMeta, newTitle: String): RecordingMeta {
        val cleaned = sanitise(newTitle).ifBlank { return meta }
        val extension = meta.fileName.substringAfterLast('.', meta.format.extension)
        var candidate = File(recordingsDir, "$cleaned.$extension")
        var counter = 2
        while (candidate.exists() && candidate != audioFile(meta)) {
            candidate = File(recordingsDir, "$cleaned ($counter).$extension")
            counter++
        }
        val renamed = if (audioFile(meta).renameTo(candidate)) {
            meta.copy(title = newTitle, fileName = candidate.name)
        } else {
            meta.copy(title = newTitle)
        }
        save(renamed)
        return renamed
    }

    fun availableBytes(): Long = runCatching {
        val stat = StatFs(recordingsDir.absolutePath)
        stat.availableBlocksLong * stat.blockSizeLong
    }.getOrDefault(0L)

    /** Seconds of recording the free space will hold at the given settings. */
    fun remainingSeconds(format: RecordingFormat, sampleRate: Int, aacBitrateKbps: Int): Long {
        val perSecond = format.bytesPerSecond(sampleRate, 2, aacBitrateKbps)
        if (perSecond <= 0) return 0
        return availableBytes() / perSecond
    }

    fun shareUri(meta: RecordingMeta): Uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        audioFile(meta),
    )

    fun shareIntent(meta: RecordingMeta): Intent {
        val uri = shareUri(meta)
        return Intent(Intent.ACTION_SEND).apply {
            type = if (meta.format == RecordingFormat.AAC) "audio/mp4" else "audio/wav"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, meta.displayTitle())
            putExtra(Intent.EXTRA_TITLE, meta.displayTitle())
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Copies a recording into the phone's shared Music library so other apps and a plugged-in
     * computer can see it. App-private storage is where recording happens, because it needs no
     * permission and never fails mid-set; this is the deliberate hand-off out of it.
     */
    fun exportToMusicLibrary(meta: RecordingMeta): Result<Uri> = runCatching {
        val source = audioFile(meta)
        require(source.isFile) { "The recording file is missing" }
        val displayName = "${sanitise(meta.displayTitle())}.${meta.fileName.substringAfterLast('.')}"
        val mimeType = if (meta.format == RecordingFormat.AAC) "audio/mp4" else "audio/wav"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
                put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/DeckRec")
                if (meta.artist.isNotBlank()) put(MediaStore.Audio.Media.ARTIST, meta.artist)
                if (meta.title.isNotBlank()) put(MediaStore.Audio.Media.TITLE, meta.title)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("The system would not create a file in the Music library")
            resolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output, COPY_BUFFER) }
            } ?: error("Could not open the destination file")
            resolver.update(uri, ContentValues().apply {
                put(MediaStore.Audio.Media.IS_PENDING, 0)
            }, null, null)
            uri
        } else {
            @Suppress("DEPRECATION")
            val musicDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                "DeckRec",
            ).apply { mkdirs() }
            val destination = File(musicDir, displayName)
            source.inputStream().use { input ->
                destination.outputStream().use { output -> input.copyTo(output, COPY_BUFFER) }
            }
            Uri.fromFile(destination)
        }
    }

    private fun sanitise(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(120)

    private companion object {
        const val COPY_BUFFER = 256 * 1024
    }
}
