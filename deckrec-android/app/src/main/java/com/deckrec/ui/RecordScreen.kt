package com.deckrec.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deckrec.audio.RecorderState
import com.deckrec.audio.dsp.BrickwallLimiter
import com.deckrec.audio.dsp.DspChain
import com.deckrec.data.RecordingFormat
import com.deckrec.data.formatBytes
import com.deckrec.data.formatDuration
import com.deckrec.ui.components.ChipRow
import com.deckrec.ui.components.ClipIndicator
import com.deckrec.ui.components.GainReductionMeter
import com.deckrec.ui.components.LabeledSlider
import com.deckrec.ui.components.SectionCard
import com.deckrec.ui.components.StereoLevelMeter
import com.deckrec.ui.theme.DeckColors
import kotlin.math.roundToInt

@Composable
fun RecordScreen(
    viewModel: DeckRecViewModel,
    contentPadding: PaddingValues,
    onOpenLibrary: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Monitoring is tied to this screen being on top: the input is open for metering only while
    // the DJ can see the meters, so the microphone is never held in the background.
    LifecycleResumeEffect(state.selectedInput?.id, state.isRecording) {
        if (!state.isRecording) viewModel.startMonitoring()
        onPauseOrDispose { viewModel.stopMonitoring() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Header(onOpenLibrary = onOpenLibrary, onOpenSettings = onOpenSettings)

        InputCard(state = state, viewModel = viewModel)
        Spacer(Modifier.height(12.dp))

        TimeCard(state = state)
        Spacer(Modifier.height(12.dp))

        MeterCard(state = state)
        Spacer(Modifier.height(12.dp))

        SoundCard(state = state, viewModel = viewModel)
        Spacer(Modifier.height(12.dp))

        MarkerCard(state = state, viewModel = viewModel)
        Spacer(Modifier.height(20.dp))

        Transport(state = state, viewModel = viewModel)
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun Header(onOpenLibrary: () -> Unit, onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "DECKREC",
            color = DeckColors.TextPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            letterSpacing = 3.sp,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onOpenLibrary) {
            Icon(Icons.Filled.LibraryMusic, contentDescription = "Library", tint = DeckColors.TextSecondary)
        }
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = DeckColors.TextSecondary)
        }
    }
}

@Composable
private fun InputCard(state: RecordUiState, viewModel: DeckRecViewModel) {
    SectionCard(
        title = "INPUT",
        trailing = {
            IconButton(onClick = viewModel::refreshDevices, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = "Rescan inputs",
                    tint = DeckColors.TextSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
        },
    ) {
        val selected = state.selectedInput
        if (selected == null) {
            Text(
                text = "No audio input found",
                color = DeckColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (state.hasUnroutedDjHardware) {
                    "Your mixer is on the USB bus but Android has not exposed it as an audio input. " +
                        "Check that the mixer's USB output is switched on and that you are using an " +
                        "OTG cable that carries data."
                } else {
                    "Connect your mixer to the phone with a USB-C OTG cable, then tap the refresh icon."
                },
                color = DeckColors.TextSecondary,
                fontSize = 12.sp,
            )
        } else {
            ChipRow(
                options = state.inputs,
                selected = selected,
                label = { "${it.productName} · ${it.typeLabel()}" },
                onSelect = viewModel::selectInput,
                enabled = !state.isRecording,
            )

            if (state.channelPairs.size > 1) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "SOURCE CHANNELS (${selected.maxChannelCount} available)",
                    style = MaterialTheme.typography.labelSmall,
                    color = DeckColors.TextSecondary,
                )
                Spacer(Modifier.height(6.dp))
                ChipRow(
                    options = state.channelPairs,
                    selected = state.settings.channelPair,
                    label = { it.label() },
                    onSelect = viewModel::selectChannelPair,
                    enabled = !state.isRecording,
                )
            }

            if (state.hasUnroutedDjHardware) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "A DJ mixer is connected but is not the selected input.",
                    color = DeckColors.MeterMid,
                    fontSize = 12.sp,
                )
            }
        }

        val failure = state.state as? RecorderState.Failed
        if (failure != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = failure.message,
                color = DeckColors.MeterClip,
                fontSize = 12.sp,
            )
        }

        // Surfaced rather than logged: silently recording the wrong channel pair for two hours is
        // exactly the kind of failure a DJ only discovers when it is far too late to redo.
        state.notice?.let { notice ->
            Spacer(Modifier.height(10.dp))
            Text(
                text = notice,
                color = DeckColors.MeterMid,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun TimeCard(state: RecordUiState) {
    SectionCard {
        Text(
            text = formatDuration(state.progress.elapsedMs),
            style = MaterialTheme.typography.displayLarge,
            color = if (state.isRecording && !state.isPaused) DeckColors.TextPrimary else DeckColors.TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = state.settings.formatLabel(),
                color = DeckColors.TextSecondary,
                fontSize = 12.sp,
            )
            Text(
                text = formatBytes(state.progress.sizeBytes),
                color = DeckColors.TextSecondary,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = "${formatDuration(state.remainingSeconds * 1000)} left",
                color = DeckColors.TextSecondary,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        if (state.progress.partIndex > 0) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Auto-split: recording part ${state.progress.partIndex + 1}",
                color = DeckColors.Accent,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun MeterCard(state: RecordUiState) {
    val title = when {
        state.isRecording -> "RECORD LEVEL"
        state.monitoring -> "INPUT LEVEL · MONITORING"
        else -> "INPUT LEVEL"
    }
    SectionCard(
        title = title,
        trailing = { ClipIndicator(clipping = state.levels.clipping) },
    ) {
        StereoLevelMeter(levels = state.levels)
        Spacer(Modifier.height(10.dp))
        GainReductionMeter(reductionDb = state.levels.limiterReductionDb)
        if (!state.isRecording) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (state.monitoring) {
                    "Live — play something and set your gain before you hit REC."
                } else {
                    "Select an input to see levels."
                },
                color = DeckColors.TextSecondary,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun SoundCard(state: RecordUiState, viewModel: DeckRecViewModel) {
    // Local drag state so the knob tracks the finger without a storage write per frame; the
    // settings value seeds it and the commit on release is what persists.
    var gain by remember { mutableStateOf(state.settings.inputGainDb) }
    var subBass by remember { mutableStateOf(state.settings.subBassAmount) }
    var loudness by remember { mutableStateOf(state.settings.loudnessAmount) }

    SectionCard(title = "SOUND") {
        LabeledSlider(
            label = "GAIN",
            valueText = "%+.1f dB".format(gain),
            value = gain,
            onValueChange = {
                gain = it
                viewModel.previewGain(it)
            },
            onValueChangeFinished = { viewModel.commitGain(gain) },
            valueRange = DspChain.MIN_GAIN_DB..DspChain.MAX_GAIN_DB,
        )
        LabeledSlider(
            label = "SUB BASS",
            valueText = "${(subBass * 100).roundToInt()}%",
            value = subBass,
            onValueChange = {
                subBass = it
                viewModel.previewSubBass(it)
            },
            onValueChangeFinished = { viewModel.commitSubBass(subBass) },
            valueRange = 0f..1f,
        )
        LabeledSlider(
            label = "LOUDNESS",
            valueText = "${(loudness * 100).roundToInt()}%",
            value = loudness,
            onValueChange = {
                loudness = it
                viewModel.previewLoudness(it)
            },
            onValueChangeFinished = { viewModel.commitLoudness(loudness) },
            valueRange = 0f..1f,
        )
        Spacer(Modifier.height(4.dp))
        ToggleRow(
            label = "Peak limiter",
            description = "Brickwall at ${"%.1f".format(BrickwallLimiter.DEFAULT_CEILING_DB)} dBFS",
            checked = state.settings.limiterEnabled,
            onCheckedChange = viewModel::setLimiter,
        )
    }
}

@Composable
private fun MarkerCard(state: RecordUiState, viewModel: DeckRecViewModel) {
    SectionCard(
        title = "TRACK MARKERS",
        trailing = {
            Text(
                text = "${state.markers.size}",
                color = DeckColors.Accent,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
            )
        },
    ) {
        ToggleRow(
            label = "Detect transitions",
            description = "Drops a marker when the mix changes track",
            checked = state.settings.autoMarkersEnabled,
            onCheckedChange = viewModel::setAutoMarkers,
        )
        val recent = state.markers.takeLast(4).reversed()
        if (recent.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            recent.forEach { marker ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = formatDuration(marker.positionMs),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = DeckColors.TextPrimary,
                    )
                    Text(
                        text = if (marker.automatic) "auto" else "manual",
                        fontSize = 11.sp,
                        color = if (marker.automatic) DeckColors.TextSecondary else DeckColors.Accent,
                    )
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, color = DeckColors.TextPrimary, fontSize = 14.sp)
            Text(text = description, color = DeckColors.TextSecondary, fontSize = 11.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black,
                checkedTrackColor = DeckColors.Accent,
                uncheckedTrackColor = DeckColors.SurfaceRaised,
                uncheckedBorderColor = DeckColors.Outline,
            ),
        )
    }
}

@Composable
private fun Transport(state: RecordUiState, viewModel: DeckRecViewModel) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        TransportButton(
            text = "MARK",
            enabled = state.isRecording && !state.isPaused,
            onClick = viewModel::addMarker,
        )

        RecordButton(
            isRecording = state.isRecording,
            isBusy = state.isBusy,
            onClick = {
                if (state.isRecording) viewModel.stopRecording() else viewModel.startRecording()
            },
        )

        TransportButton(
            text = if (state.isPaused) "RESUME" else "PAUSE",
            enabled = state.isRecording,
            onClick = viewModel::togglePause,
        )
    }
}

@Composable
private fun TransportButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(76.dp)
            .clip(CircleShape)
            .background(DeckColors.Surface)
            .border(1.dp, if (enabled) DeckColors.Outline else DeckColors.Surface, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (enabled) DeckColors.TextPrimary else DeckColors.TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun RecordButton(isRecording: Boolean, isBusy: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(104.dp)
            .clip(CircleShape)
            .background(if (isRecording) DeckColors.SurfaceRaised else DeckColors.Record)
            .border(2.dp, if (isRecording) DeckColors.Record else Color.Transparent, CircleShape)
            .clickable(enabled = !isBusy, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (isRecording) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(DeckColors.Record),
            )
        } else {
            Text(
                text = if (isBusy) "…" else "REC",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                letterSpacing = 1.sp,
            )
        }
    }
}

/** Compact one-line description of what is being written, for the header row. */
private fun com.deckrec.data.AppSettings.formatLabel(): String = buildString {
    append(format.displayName)
    if (format == RecordingFormat.AAC) append(" ${aacBitrateKbps}k")
    append(" · ")
    append("%.1f kHz".format(sampleRate / 1000f))
}
