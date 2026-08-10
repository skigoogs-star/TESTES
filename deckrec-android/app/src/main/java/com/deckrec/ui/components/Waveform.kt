package com.deckrec.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.deckrec.audio.write.PeakBucket
import com.deckrec.ui.theme.DeckColors

/**
 * Waveform overview with the playhead and cue markers drawn over it.
 *
 * Buckets come from the sidecar the recorder wrote as it went, so a two-hour set draws instantly
 * instead of streaming gigabytes off disk to work out its own shape.
 */
@Composable
fun WaveformView(
    buckets: List<PeakBucket>,
    normalisation: Float,
    positionFraction: Float,
    markerFractions: List<Float>,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val seek = rememberUpdatedState(onSeek)

    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(DeckColors.Background)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    seek.value((offset.x / size.width).coerceIn(0f, 1f))
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    seek.value((change.position.x / size.width).coerceIn(0f, 1f))
                }
            },
    ) {
        val width = size.width
        val height = size.height
        val centreY = height / 2f

        drawRect(
            color = DeckColors.Outline,
            topLeft = Offset(0f, centreY - 0.5f),
            size = Size(width, 1f),
        )

        if (buckets.isNotEmpty()) {
            val columnWidth = width / buckets.size
            val barWidth = columnWidth.coerceAtLeast(1f)
            buckets.forEachIndexed { index, bucket ->
                val x = index * columnWidth
                val top = (centreY - (bucket.max * normalisation).coerceIn(-1f, 1f) * centreY)
                val bottom = (centreY - (bucket.min * normalisation).coerceIn(-1f, 1f) * centreY)
                val played = x / width <= positionFraction
                drawRect(
                    color = if (played) DeckColors.Accent else DeckColors.TextSecondary,
                    topLeft = Offset(x, minOf(top, bottom)),
                    size = Size(barWidth, maxOf(1f, kotlin.math.abs(bottom - top))),
                )
            }
        }

        markerFractions.forEach { fraction ->
            val x = (fraction.coerceIn(0f, 1f) * width)
            drawRect(
                color = DeckColors.MeterLow,
                topLeft = Offset(x - 1f, 0f),
                size = Size(2f, height),
            )
        }

        val playheadX = (positionFraction.coerceIn(0f, 1f) * width)
        drawRect(
            color = Color.White,
            topLeft = Offset(playheadX - 1f, 0f),
            size = Size(2f, height),
        )
    }
}
