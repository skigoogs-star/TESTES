package com.deckrec.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deckrec.ui.theme.DeckColors

/** Grouping container used throughout the app so every panel reads the same. */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = DeckColors.Surface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, DeckColors.Outline),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            if (title != null || trailing != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = DeckColors.TextSecondary,
                    )
                    trailing?.invoke()
                }
                Spacer(Modifier.height(10.dp))
            }
            content()
        }
    }
}

/** Slider with the parameter name on the left and its current value on the right. */
@Composable
fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (enabled) DeckColors.TextPrimary else DeckColors.TextSecondary,
            )
            Text(
                text = valueText,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = DeckColors.Accent,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = DeckColors.Accent,
                activeTrackColor = DeckColors.Accent,
                inactiveTrackColor = DeckColors.Outline,
            ),
        )
    }
}

/** Horizontally scrolling row of single-select chips. */
@Composable
fun <T> ChipRow(
    options: List<T>,
    selected: T?,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            SelectableChip(
                text = label(option),
                selected = option == selected,
                enabled = enabled,
                onClick = { onSelect(option) },
            )
        }
    }
}

@Composable
fun SelectableChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val background = when {
        selected && enabled -> DeckColors.Accent
        selected -> DeckColors.AccentMuted
        else -> DeckColors.SurfaceRaised
    }
    val textColour = when {
        selected -> androidx.compose.ui.graphics.Color.Black
        enabled -> DeckColors.TextPrimary
        else -> DeckColors.TextSecondary
    }
    Text(
        text = text,
        color = textColour,
        fontSize = 13.sp,
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            .background(background)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** Two-line key/value row used for read-only detail. */
@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, fontSize = 13.sp, color = DeckColors.TextSecondary)
        Text(text = value, fontSize = 13.sp, color = DeckColors.TextPrimary)
    }
}
