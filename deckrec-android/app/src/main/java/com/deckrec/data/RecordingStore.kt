package com.deckrec.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.deckrec.audio.RecorderConfig
import com.deckrec.audio.write.WavSink
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
        // getExternalFilesDir can return null; File(null, "artwork") silently resolves to /artwork.
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        File(base, "artwork").apply { mkdirs() }
    }

    private val _recordings = MutableStateFlow<List<RecordingMeta>>(emptyList())
    val recordings: StateFlow<List<RecordingMeta>> = _recordings.asStateFlow()

    /**
     * False until the first scan completes. Screens need to tell "the library is still loading"
     * apart from "this recording does not exist" — the library now loads off the main thread, so
     * an empty list on first composition is the normal case rather than an error.
     */
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

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

    /**
     * Writes the sidecar for [meta].
     *
     * [refreshLibrary] rescans and re-parses every recording in the directory, which is far too
     * heavy for callers on a time-critical thread — the recorder passes false and lets the UI
     * refresh on its own schedule.
     */
    fun save(meta: RecordingMeta, refreshLibrary: Boolean = true) {
        runCatching {
            File(metaDir, "${meta.id}.json").writeText(json.encodeToString(RecordingMeta.serializer(), meta))
        }
        if (refreshLibrary) refresh()
    }

    fun delete(meta: RecordingMeta) {
        runCatching { audioFile(meta).delete() }
        runCatching { meta.peaksFileName?.let { File(metaDir, it).delete() } }
        runCatching { meta.artworkFileName?.let { File(artworkDir, it).delete() } }
        runCatching { File(metaDir, "${meta.id}.json").delete() }
        refresh()
    }

    /** Synchronized: startup, library edits and part completion all scan concurrently, and two
     * overlapping scans can otherwise publish their results in the wrong order. */
    @Synchronized
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
        _loaded.value = true
    }

    /**
     * Adopts audio files that have no sidecar and repairs their headers.
     *
     * A phone that dies mid-set leaves a WAV whose size fields were never patched — playable by
     * nothing until the header is fixed. Running this at launch means the worst case for a crash
     * is a set with no markers rather than a set that is gone.
     *
     * @return how many files were recovered.
     */
    fun recoverOrphans(inUse: () -> Set<String> = { emptySet() }): Int {
        val known = metaDir.listFiles { file -> file.extension == "json" }
            ?.mapNotNull {
                runCatching { json.decodeFromString(RecordingMeta.serializer(), it.readText()) }.getOrNull()
            }
            ?.map { it.fileName }
            ?.toSet()
            ?: emptySet()

        val orphans = recordingsDir.listFiles { file ->
            file.isFile &&
                file.extension.lowercase() in AUDIO_EXTENSIONS &&
                file.name !in known
        } ?: return 0

        var recovered = 0
        orphans.forEach { file ->
            // Re-checked here rather than once up front: building `known` parses every sidecar in
            // the library, which takes long enough for the user to have started a recording. A
            // file the engine has open has no sidecar yet and looks exactly like a crash orphan —
            // adopting it would patch the header of a file mid-write and leave two library entries
            // sharing one audio file, where deleting either destroys the other.
            if (file.name in inUse()) return@forEach
            val meta = runCatching { adopt(file) }.getOrNull()
            if (meta != null) {
                runCatching {
                    File(metaDir, "${meta.id}.json")
                        .writeText(json.encodeToString(RecordingMeta.serializer(), meta))
                }
                recovered++
            }
        }
        if (recovered > 0) refresh()
        return recovered
    }

    private fun adopt(file: File): RecordingMeta? {
        val isWav = file.extension.equals("wav", ignoreCase = true)
        if (isWav) WavSink.repairTruncated(file)

        val info = if (isWav) WavSink.readHeader(file) else null
        val format = when {
            !isWav -> RecordingFormat.AAC
            info?.bitsPerSample == 16 -> RecordingFormat.WAV_16
            else -> RecordingFormat.WAV_24
        }
        val durationMs = info?.durationMs ?: durationFromMediaMetadata(file)

        return RecordingMeta(
            id = UUID.randomUUID().toString(),
            fileName = file.name,
            title = file.nameWithoutExtension,
            createdAtEpochMs = file.lastModified(),
            durationMs = durationMs,
            sampleRate = info?.sampleRate ?: 48000,
            channels = info?.channels ?: 2,
            format = format,
            sourceDeviceName = "",
            sizeBytes = file.length(),
            notes = "Recovered after the app stopped unexpectedly.",
        )
    }

    private fun durationFromMediaMetadata(file: File): Long {
        // Not `use {}`: MediaMetadataRetriever only became AutoCloseable in API 29, and this app
        // supports API 26, where calling close() would blow up at runtime.
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
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

    /** True when external storage was unavailable and recordings live in internal storage. */
    val usingInternalFallback: Boolean
        get() = recordingsDir.absolutePath.startsWith(context.filesDir.absolutePath)

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
        val AUDIO_EXTENSIONS = setOf("wav", "m4a")
    }
}
