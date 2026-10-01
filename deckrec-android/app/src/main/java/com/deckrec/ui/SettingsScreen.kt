package com.deckrec.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deckrec.data.AppSettings
import com.deckrec.data.RecordingFormat
import com.deckrec.data.formatBytes
import com.deckrec.data.formatDuration
import com.deckrec.ui.components.ChipRow
import com.deckrec.ui.components.InfoRow
import com.deckrec.ui.components.LabeledSlider
import com.deckrec.ui.components.SectionCard
import com.deckrec.ui.theme.DeckColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    viewModel: DeckRecViewModel,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val recordings by viewModel.recordings.collectAsStateWithLifecycle()
    val settings = state.settings
    val locked = state.isRecording

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
                text = "Settings",
                color = DeckColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
            )
        }

        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            if (locked) {
                Text(
                    text = "Recording settings are locked while a set is running.",
                    color = DeckColors.MeterMid,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }

            SectionCard(title = "RECORDING FORMAT") {
                ChipRow(
                    options = RecordingFormat.entries.toList(),
                    selected = settings.format,
                    label = { it.displayName },
                    onSelect = viewModel::setFormat,
                    enabled = !locked,
                )
                if (settings.format == RecordingFormat.AAC) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "BITRATE",
                        style = MaterialTheme.typography.labelSmall,
                        color = DeckColors.TextSecondary,
                    )
                    Spacer(Modifier.height(6.dp))
                    ChipRow(
                        options = AppSettings.AAC_BITRATES,
                        selected = settings.aacBitrateKbps,
                        label = { "$it kbps" },
                        onSelect = viewModel::setAacBitrate,
                        enabled = !locked,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "SAMPLE RATE",
                    style = MaterialTheme.typography.labelSmall,
                    color = DeckColors.TextSecondary,
                )
                Spacer(Modifier.height(6.dp))
                ChipRow(
                    options = state.selectedInput?.usableSampleRates() ?: listOf(44100, 48000),
                    selected = settings.sampleRate,
                    label = { "%.1f kHz".format(it / 1000f) },
                    onSelect = viewModel::setSampleRate,
                    enabled = !locked,
                )
            }

            Spacer(Modifier.height(12.dp))

            SectionCard(title = "TRACK MARKERS") {
                LabeledSlider(
                    label = "DETECTION SENSITIVITY",
                    valueText = "${(settings.autoMarkerSensitivity * 100).roundToInt()}%",
                    value = settings.autoMarkerSensitivity,
                    onValueChange = viewModel::setMarkerSensitivity,
                    valueRange = 0f..1f,
                )
                LabeledSlider(
                    label = "MINIMUM GAP",
                    valueText = "${settings.autoMarkerGapSeconds.roundToInt()} s",
                    value = settings.autoMarkerGapSeconds,
                    onValueChange = viewModel::setMarkerGapSeconds,
                    valueRange = 15f..180f,
                )
                Text(
                    text = "Markers are placed when the low and high bands of the mix both change " +
                        "character — the signature of a track swap. Raise the gap if breakdowns are " +
                        "being marked as new tracks.",
                    color = DeckColors.TextSecondary,
                    fontSize = 11.sp,
                )
            }

            Spacer(Modifier.height(12.dp))

            SectionCard(title = "FILES") {
                Text(
                    text = "AUTO-SPLIT",
                    style = MaterialTheme.typography.labelSmall,
                    color = DeckColors.TextSecondary,
                )
                Spacer(Modifier.height(6.dp))
                ChipRow(
                    options = AppSettings.AUTO_SPLIT_OPTIONS,
                    selected = settings.autoSplitMinutes,
                    label = { if (it == 0) "Off" else "$it min" },
                    onSelect = viewModel::setAutoSplitMinutes,
                    enabled = !locked,
                )
                Spacer(Modifier.height(12.dp))
                // Local state, committed on change: binding the field straight to the settings
                // StateFlow round-trips every keystroke through storage and back, which drops and
                // reorders characters when typing quickly.
                var prefix by remember(settings.fileNamePrefix.isEmpty()) {
                    mutableStateOf(settings.fileNamePrefix)
                }
                OutlinedTextField(
                    value = prefix,
                    onValueChange = {
                        prefix = it
                        viewModel.setFileNamePrefix(it)
                    },
                    label = { Text("File name prefix", fontSize = 12.sp) },
                    singleLine = true,
                    enabled = !locked,
                    modifier = Modifier.fillMaxWidth(),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = DeckColors.SurfaceRaised,
                        unfocusedContainerColor = DeckColors.SurfaceRaised,
                        disabledContainerColor = DeckColors.SurfaceRaised,
                        focusedIndicatorColor = DeckColors.Accent,
                        unfocusedIndicatorColor = DeckColors.Outline,
                        focusedTextColor = DeckColors.TextPrimary,
                        unfocusedTextColor = DeckColors.TextPrimary,
                        focusedLabelColor = DeckColors.Accent,
                        unfocusedLabelColor = DeckColors.TextSecondary,
                    ),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "WAV files are split automatically before they reach 4 GB, because the " +
                        "format cannot address past that no matter what this is set to.",
                    color = DeckColors.TextSecondary,
                    fontSize = 11.sp,
                )
            }

            Spacer(Modifier.height(12.dp))

            SectionCard(title = "BEHAVIOUR") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Keep screen on", color = DeckColors.TextPrimary, fontSize = 14.sp)
                        Text(
                            "While recording only",
                            color = DeckColors.TextSecondary,
                            fontSize = 11.sp,
                        )
                    }
                    Switch(
                        checked = settings.keepScreenOn,
                        onCheckedChange = viewModel::setKeepScreenOn,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.Black,
                            checkedTrackColor = DeckColors.Accent,
                            uncheckedTrackColor = DeckColors.SurfaceRaised,
                            uncheckedBorderColor = DeckColors.Outline,
                        ),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            SectionCard(title = "STORAGE") {
                // StatFs off the composition: cheap, but it re-ran on every recomposition.
                var freeBytes by remember { mutableStateOf(0L) }
                LaunchedEffect(state.remainingSeconds) {
                    freeBytes = withContext(Dispatchers.IO) { viewModel.availableBytes() }
                }
                InfoRow("Free space", formatBytes(freeBytes))
                InfoRow("Recording time left", formatDuration(state.remainingSeconds * 1000))
                InfoRow("Saved sets", "${recordings.size}")
            }

            Spacer(Modifier.height(12.dp))

            SectionCard(title = "ABOUT") {
                Text(
                    text = "DeckRec records the master output of a class-compliant USB mixer " +
                        "straight to your phone. Connect the mixer with a USB-C OTG cable, pick it " +
                        "as the input, and hit REC.",
                    color = DeckColors.TextSecondary,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(8.dp))
                InfoRow("Version", "1.0")
            }

            Spacer(Modifier.height(28.dp))
        }
    }
}
