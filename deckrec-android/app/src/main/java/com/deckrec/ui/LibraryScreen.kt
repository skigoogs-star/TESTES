package com.deckrec.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deckrec.data.RecordingMeta
import com.deckrec.data.formatBytes
import com.deckrec.data.formatDuration
import com.deckrec.ui.theme.DeckColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LibraryScreen(
    viewModel: DeckRecViewModel,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onOpenRecording: (String) -> Unit,
) {
    val recordings by viewModel.recordings.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
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
                text = "Recordings",
                color = DeckColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
            )
        }

        if (recordings.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Nothing recorded yet",
                    color = DeckColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Sets you record will appear here with their track markers.",
                    color = DeckColors.TextSecondary,
                    fontSize = 13.sp,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(recordings, key = { it.id }) { meta ->
                    RecordingRow(meta = meta, onClick = { onOpenRecording(meta.id) })
                }
            }
        }
    }
}

@Composable
private fun RecordingRow(meta: RecordingMeta, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = DeckColors.Surface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, DeckColors.Outline),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = meta.displayTitle(),
                color = DeckColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = DATE_FORMAT.format(Date(meta.createdAtEpochMs)),
                color = DeckColors.TextSecondary,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = formatDuration(meta.durationMs),
                    color = DeckColors.Accent,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                )
                Text(
                    text = meta.formatSummary(),
                    color = DeckColors.TextSecondary,
                    fontSize = 12.sp,
                )
                Text(
                    text = formatBytes(meta.sizeBytes),
                    color = DeckColors.TextSecondary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
            }
            if (meta.markers.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "${meta.markers.size} track markers",
                    color = DeckColors.MeterLow,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

private val DATE_FORMAT = SimpleDateFormat("EEE d MMM yyyy · HH:mm", Locale.getDefault())
