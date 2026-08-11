package com.deckrec.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deckrec.audio.write.PeakBucket
import com.deckrec.audio.write.PeakFileReader
import com.deckrec.data.Marker
import com.deckrec.data.RecordingMeta
import com.deckrec.data.formatBytes
import com.deckrec.data.formatDuration
import com.deckrec.ui.components.InfoRow
import com.deckrec.ui.components.SectionCard
import com.deckrec.ui.components.SelectableChip
import com.deckrec.ui.components.WaveformView
import com.deckrec.ui.theme.DeckColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val WAVEFORM_COLUMNS = 480

@Composable
fun DetailScreen(
    viewModel: DeckRecViewModel,
    recordingId: String,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
) {
    val recordings by viewModel.recordings.collectAsStateWithLifecycle()
    val meta = recordings.firstOrNull { it.id == recordingId }

    if (meta == null) {
        // The recording was deleted (possibly from this very screen); fall back to the library.
        LaunchedEffect(recordingId) { onBack() }
        return
    }

    val context = LocalContext.current
    val store = remember { com.deckrec.DeckRecApp.from(context).recordingStore }
    val audioFile = remember(meta.fileName) { store.audioFile(meta) }
    val peaksFile = remember(meta.id, meta.peaksFileName) { store.peaksFile(meta) }

    val player = rememberPlayer(audioFile)
    var buckets by remember(meta.id) { mutableStateOf<List<PeakBucket>>(emptyList()) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var editingMarker by remember { mutableStateOf<Marker?>(null) }

    LaunchedEffect(peaksFile?.path) {
        buckets = withContext(Dispatchers.IO) {
            peaksFile?.let { PeakFileReader.read(it, WAVEFORM_COLUMNS) } ?: emptyList()
        }
    }

    val durationMs = if (meta.durationMs > 0) meta.durationMs else player.durationMs
    val normalisation = remember(buckets) { PeakFileReader.normalisationFactor(buckets) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = DeckColors.TextPrimary,
                )
            }
            Text(
                text = meta.displayTitle(),
                color = DeckColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { showDeleteDialog = true }) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = DeckColors.MeterClip)
            }
        }

        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            SectionCard(title = "WAVEFORM") {
                WaveformView(
                    buckets = buckets,
                    normalisation = normalisation,
                    positionFraction = if (durationMs > 0) player.positionMs.toFloat() / durationMs else 0f,
                    markerFractions = if (durationMs > 0) {
                        meta.markers.map { it.positionMs.toFloat() / durationMs }
                    } else {
                        emptyList()
                    },
                    onSeek = { fraction -> player.seekTo((fraction * durationMs).toLong()) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(96.dp),
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = formatDuration(player.positionMs),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = DeckColors.TextPrimary,
                    )
                    PlayButton(playing = player.isPlaying, onClick = player::togglePlay)
                    Text(
                        text = formatDuration(durationMs),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = DeckColors.TextSecondary,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SelectableChip(
                        text = "Add marker here",
                        selected = false,
                        onClick = { viewModel.addMarkerTo(meta, player.positionMs) },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            SectionCard(
                title = "TRACK LIST",
                trailing = {
                    Text(
                        text = "${meta.markers.size}",
                        color = DeckColors.Accent,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                    )
                },
            ) {
                if (meta.markers.isEmpty()) {
                    Text(
                        text = "No markers. Play back and tap \"Add marker here\", or turn on " +
                            "transition detection before your next set.",
                        color = DeckColors.TextSecondary,
                        fontSize = 12.sp,
                    )
                } else {
                    meta.markers.sortedBy { it.positionMs }.forEachIndexed { index, marker ->
                        MarkerRow(
                            index = index,
                            marker = marker,
                            onSeek = { player.seekTo(marker.positionMs) },
                            onEdit = { editingMarker = marker },
                            onDelete = { viewModel.deleteMarker(meta, marker) },
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    SelectableChip(
                        text = "Copy track list",
                        selected = false,
                        onClick = {
                            copyToClipboard(context, viewModel.trackListText(meta))
                            viewModel.showMessage("Track list copied")
                        },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            MetadataCard(meta = meta, viewModel = viewModel)

            Spacer(Modifier.height(12.dp))

            SectionCard(title = "FILE") {
                InfoRow("Format", meta.formatSummary())
                InfoRow("Duration", formatDuration(meta.durationMs))
                InfoRow("Size", formatBytes(meta.sizeBytes))
                InfoRow("Recorded from", meta.sourceDeviceName.ifBlank { "Unknown input" })
                InfoRow("File name", meta.fileName)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SelectableChip(
                        text = "Share",
                        selected = false,
                        onClick = { context.startActivity(viewModel.shareIntent(meta)) },
                    )
                    SelectableChip(
                        text = "Save to Music",
                        selected = false,
                        onClick = { viewModel.exportToMusic(meta) },
                    )
                }
            }

            Spacer(Modifier.height(28.dp))
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete recording?") },
            text = { Text("\"${meta.displayTitle()}\" and its markers will be removed permanently.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    player.release()
                    viewModel.deleteRecording(meta)
                    onBack()
                }) {
                    Text("Delete", color = DeckColors.MeterClip)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            },
            containerColor = DeckColors.Surface,
        )
    }

    editingMarker?.let { marker ->
        MarkerEditDialog(
            marker = marker,
            onDismiss = { editingMarker = null },
            onSave = { updated ->
                viewModel.updateMarker(meta, updated)
                editingMarker = null
            },
        )
    }
}

@Composable
private fun MarkerRow(
    index: Int,
    marker: Marker,
    onSeek: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSeek)
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = formatDuration(marker.positionMs),
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            color = DeckColors.Accent,
        )
        Spacer(Modifier.size(12.dp))
        Text(
            text = marker.displayLabel(index),
            color = DeckColors.TextPrimary,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "Edit",
            color = DeckColors.TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier
                .clickable(onClick = onEdit)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
        Text(
            text = "Remove",
            color = DeckColors.MeterClip,
            fontSize = 12.sp,
            modifier = Modifier
                .clickable(onClick = onDelete)
                .padding(horizontal = 4.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun MetadataCard(meta: RecordingMeta, viewModel: DeckRecViewModel) {
    var title by remember(meta.id) { mutableStateOf(meta.title.ifBlank { meta.displayTitle() }) }
    var artist by remember(meta.id) { mutableStateOf(meta.artist) }
    var genre by remember(meta.id) { mutableStateOf(meta.genre) }
    var tags by remember(meta.id) { mutableStateOf(meta.tags.joinToString(", ")) }
    var notes by remember(meta.id) { mutableStateOf(meta.notes) }

    SectionCard(title = "DETAILS") {
        DeckTextField(value = title, onValueChange = { title = it }, label = "Title")
        DeckTextField(value = artist, onValueChange = { artist = it }, label = "Artist")
        DeckTextField(value = genre, onValueChange = { genre = it }, label = "Genre")
        DeckTextField(value = tags, onValueChange = { tags = it }, label = "Tags (comma separated)")
        DeckTextField(value = notes, onValueChange = { notes = it }, label = "Notes", singleLine = false)
        Spacer(Modifier.height(10.dp))
        SelectableChip(
            text = "Save details",
            selected = true,
            onClick = { viewModel.saveDetails(meta, title, artist, genre, tags, notes) },
        )
    }
}

@Composable
private fun DeckTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    singleLine: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 12.sp) },
        singleLine = singleLine,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = DeckColors.SurfaceRaised,
            unfocusedContainerColor = DeckColors.SurfaceRaised,
            focusedIndicatorColor = DeckColors.Accent,
            unfocusedIndicatorColor = DeckColors.Outline,
            focusedTextColor = DeckColors.TextPrimary,
            unfocusedTextColor = DeckColors.TextPrimary,
            focusedLabelColor = DeckColors.Accent,
            unfocusedLabelColor = DeckColors.TextSecondary,
        ),
    )
}

@Composable
private fun MarkerEditDialog(
    marker: Marker,
    onDismiss: () -> Unit,
    onSave: (Marker) -> Unit,
) {
    var label by remember(marker.id) { mutableStateOf(marker.label) }
    var positionText by remember(marker.id) { mutableStateOf(formatDuration(marker.positionMs)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit marker") },
        text = {
            Column {
                DeckTextField(value = label, onValueChange = { label = it }, label = "Track name")
                DeckTextField(
                    value = positionText,
                    onValueChange = { positionText = it },
                    label = "Position (mm:ss or h:mm:ss)",
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val position = parseDuration(positionText) ?: marker.positionMs
                onSave(marker.copy(label = label, positionMs = position, automatic = false))
            }) {
                Text("Save", color = DeckColors.Accent)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = DeckColors.Surface,
    )
}

@Composable
private fun PlayButton(playing: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(DeckColors.Accent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (playing) "❚❚" else "▶",
            color = androidx.compose.ui.graphics.Color.Black,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** Minimal transport around [MediaPlayer], scoped to the composition that created it. */
private class PlayerController {
    var mediaPlayer: MediaPlayer? = null
    var isPlaying by mutableStateOf(false)
    var positionMs by mutableStateOf(0L)
    var durationMs by mutableStateOf(0L)

    fun togglePlay() {
        val player = mediaPlayer ?: return
        if (player.isPlaying) {
            player.pause()
            isPlaying = false
        } else {
            player.start()
            isPlaying = true
        }
    }

    fun seekTo(millis: Long) {
        val player = mediaPlayer ?: return
        val target = millis.coerceIn(0L, durationMs)
        player.seekTo(target.toInt())
        positionMs = target
    }

    fun release() {
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
        isPlaying = false
    }
}

@Composable
private fun rememberPlayer(file: File): PlayerController {
    val controller = remember(file.path) { PlayerController() }

    DisposableEffect(file.path) {
        val player = runCatching {
            MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
            }
        }.getOrNull()
        controller.mediaPlayer = player
        controller.durationMs = player?.duration?.toLong() ?: 0L
        player?.setOnCompletionListener {
            controller.isPlaying = false
            controller.positionMs = controller.durationMs
        }
        onDispose { controller.release() }
    }

    LaunchedEffect(controller) {
        while (true) {
            val player = controller.mediaPlayer
            if (player != null && controller.isPlaying) {
                controller.positionMs = runCatching { player.currentPosition.toLong() }.getOrDefault(0L)
            }
            delay(120)
        }
    }

    return controller
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Track list", text))
}

/** Parses "mm:ss" or "h:mm:ss" back into milliseconds; null when the text is not a time. */
private fun parseDuration(text: String): Long? {
    val parts = text.trim().split(':')
    if (parts.isEmpty() || parts.size > 3) return null
    val numbers = parts.map { it.toIntOrNull() ?: return null }
    val seconds = when (numbers.size) {
        1 -> numbers[0]
        2 -> numbers[0] * 60 + numbers[1]
        else -> numbers[0] * 3600 + numbers[1] * 60 + numbers[2]
    }
    return seconds * 1000L
}
