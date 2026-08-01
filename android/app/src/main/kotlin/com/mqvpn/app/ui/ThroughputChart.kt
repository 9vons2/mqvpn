// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 mp0rta and mqvpn contributors

package com.mqvpn.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mqvpn.app.R

// Categorical series colors (colorblind-safe fixed slot order; light/dark
// variants tuned per surface). Slot follows the provider, never its rank.
private val SERIES_LIGHT = listOf(
    Color(0xFF2A78D6), // blue
    Color(0xFF1BAF7A), // aqua
    Color(0xFFEDA100), // yellow
    Color(0xFF008300), // green
    Color(0xFF4A3AA7), // violet
    Color(0xFFE34948), // red
)
private val SERIES_DARK = listOf(
    Color(0xFF3987E5),
    Color(0xFF199E70),
    Color(0xFFC98500),
    Color(0xFF008300),
    Color(0xFF9085E9),
    Color(0xFFE66767),
)

@Composable
fun seriesColor(slot: Int): Color {
    val palette = if (isSystemInDarkTheme()) SERIES_DARK else SERIES_LIGHT
    return palette[slot % palette.size]
}

/**
 * Live throughput card: aggregate headline, download and upload sparkline
 * panels (one line per provider + a thicker neutral aggregate line), and a
 * legend with current per-provider rates. Window is the last
 * [SpeedTracker.MAX_SAMPLES] seconds.
 */
@Composable
fun ThroughputSection(t: ThroughputUi) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.tp_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(
                        R.string.tp_rates,
                        formatBps(t.aggregateDownBps), formatBps(t.aggregateUpBps),
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            RatePanel(stringResource(R.string.tp_download), t) { it.downBps }
            Spacer(modifier = Modifier.height(8.dp))
            RatePanel(stringResource(R.string.tp_upload), t) { it.upBps }

            Spacer(modifier = Modifier.height(8.dp))
            t.paths.forEach { p ->
                Row(
                    modifier = Modifier.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(seriesColor(p.colorSlot)),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        p.label,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        stringResource(R.string.tp_rates, formatBps(p.downBps), formatBps(p.upBps)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Row(
                modifier = Modifier.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    stringResource(R.string.tp_all_providers),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(
                        R.string.tp_rates,
                        formatBps(t.aggregateDownBps), formatBps(t.aggregateUpBps),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RatePanel(
    title: String,
    t: ThroughputUi,
    select: (ThroughputFrame.Sample) -> Double,
) {
    val history = t.history
    val peak = history.maxOfOrNull { frame ->
        maxOf(
            select(frame.aggregate),
            frame.perPath.values.maxOfOrNull(select) ?: 0.0,
        )
    } ?: 0.0
    // Floor the y-scale at 1 Mbit/s so idle noise doesn't render as drama.
    val yMax = peak.coerceAtLeast(1_000_000.0)

    val slotsByKey = t.paths.associate { it.key to it.colorSlot }
    val dark = isSystemInDarkTheme()
    val surface = MaterialTheme.colorScheme.surfaceVariant
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    val aggColor = MaterialTheme.colorScheme.onSurfaceVariant

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.weight(1f),
        )
        Text(
            stringResource(R.string.tp_peak, formatBps(peak)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(84.dp),
    ) {
        drawRect(surface)
        // recessive mid-scale gridline
        drawLine(
            gridColor,
            Offset(0f, size.height / 2),
            Offset(size.width, size.height / 2),
            strokeWidth = 1.dp.toPx(),
        )

        val stepX = size.width / (SpeedTracker.MAX_SAMPLES - 1).toFloat()
        val startX = (SpeedTracker.MAX_SAMPLES - history.size) * stepX

        fun polyline(values: List<Double?>, color: Color, strokeDp: Float) {
            val path = Path()
            var penDown = false
            values.forEachIndexed { i, v ->
                if (v == null) {
                    penDown = false
                    return@forEachIndexed
                }
                val x = startX + i * stepX
                val y = size.height - (v / yMax).toFloat().coerceIn(0f, 1f) * size.height
                if (penDown) path.lineTo(x, y) else path.moveTo(x, y)
                penDown = true
            }
            drawPath(path, color, style = Stroke(width = strokeDp.dp.toPx()))
        }

        val palette = if (dark) SERIES_DARK else SERIES_LIGHT
        slotsByKey.forEach { (key, slot) ->
            polyline(
                history.map { f -> f.perPath[key]?.let(select) },
                palette[slot % palette.size],
                strokeDp = 2f,
            )
        }
        polyline(history.map { select(it.aggregate) }, aggColor, strokeDp = 3f)
    }
}
