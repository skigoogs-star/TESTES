package com.deckrec.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deckrec.audio.dsp.Levels
import com.deckrec.ui.theme.DeckColors
import androidx.compose.foundation.Canvas

private const val METER_FLOOR_DB = -60f
private val SCALE_MARKS = listOf(-60, -40, -20, -12, -6, -3, 0)

/**
 * Stereo record meter with the ballistics and colour breaks of a mixer's output meter.
 *
 * Peak hold is drawn as a thin bar rather than a segment so a transient that has already decayed
 * is still readable, which is what tells a DJ their gain staging is wrong before the set is ruined.
 */
@Composable
fun StereoLevelMeter(
    levels: Levels,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChannelLabel("L")
            MeterBar(
                peakDb = levels.peakDbL,
                holdDb = levels.holdDbL,
                modifier = Modifier
                    .weight(1f)
                    .height(18.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChannelLabel("R")
            MeterBar(
                peakDb = levels.peakDbR,
                holdDb = levels.holdDbR,
                modifier = Modifier
                    .weight(1f)
                    .height(18.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        MeterScale(modifier = Modifier.padding(start = 22.dp))
    }
}

@Composable
private fun ChannelLabel(text: String) {
    Text(
        text = text,
        color = DeckColors.TextSecondary,
        fontSize = 11.sp,
        modifier = Modifier.width(22.dp),
    )
}

@Composable
private fun MeterBar(
    peakDb: Float,
    holdDb: Float,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(3.dp))
            .background(DeckColors.Background),
    ) {
        val width = size.width
        val height = size.height
        val fraction = dbToFraction(peakDb)

        // Colour breaks match the meter scale: green to -12, yellow to -6, amber to -3, red above.
        val stops = listOf(
            dbToFraction(-12f) to DeckColors.MeterLow,
            dbToFraction(-6f) to DeckColors.MeterMid,
            dbToFraction(-3f) to DeckColors.MeterHigh,
            1f to DeckColors.MeterClip,
        )
        var start = 0f
        for ((limit, colour) in stops) {
            if (fraction <= start) break
            val end = minOf(fraction, limit)
            drawRect(
                color = colour,
                topLeft = Offset(start * width, 0f),
                size = Size((end - start) * width, height),
            )
            start = limit
        }

        val holdFraction = dbToFraction(holdDb)
        if (holdFraction > 0.001f) {
            val x = (holdFraction * width).coerceIn(0f, width - 2f)
            drawRect(
                color = Color.White,
                topLeft = Offset(x, 0f),
                size = Size(2f.coerceAtLeast(width * 0.004f), height),
            )
        }

        // Unity reference tick.
        val unityX = dbToFraction(0f) * width
        drawRect(
            color = DeckColors.Outline,
            topLeft = Offset(unityX - 1f, 0f),
            size = Size(1f, height),
        )
    }
}

@Composable
private fun MeterScale(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        SCALE_MARKS.forEach { db ->
            Text(
                text = if (db == 0) "0" else "$db",
                color = DeckColors.TextSecondary,
                fontSize = 9.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Small red LED that latches for a moment after any sample hits full scale. */
@Composable
fun ClipIndicator(clipping: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(10.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(if (clipping) DeckColors.MeterClip else DeckColors.Outline),
    )
}

/** Horizontal gain-reduction readout for the limiter, drawn right-to-left like a mixer's. */
@Composable
fun GainReductionMeter(
    reductionDb: Float,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = "LIMITER  ${if (reductionDb > 0.05f) "-%.1f dB".format(reductionDb) else "—"}",
            color = if (reductionDb > 0.05f) DeckColors.Accent else DeckColors.TextSecondary,
            style = MaterialTheme.typography.labelSmall,
        )
        Spacer(Modifier.height(4.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(DeckColors.Background),
        ) {
            val fraction = (reductionDb / MAX_REDUCTION_DB).coerceIn(0f, 1f)
            if (fraction > 0f) {
                drawRect(
                    color = DeckColors.Accent,
                    topLeft = Offset(size.width * (1f - fraction), 0f),
                    size = Size(size.width * fraction, size.height),
                )
            }
        }
    }
}

private const val MAX_REDUCTION_DB = 12f

private fun dbToFraction(db: Float): Float {
    if (db <= METER_FLOOR_DB) return 0f
    val clamped = db.coerceIn(METER_FLOOR_DB, 0f)
    return (clamped - METER_FLOOR_DB) / -METER_FLOOR_DB
}
