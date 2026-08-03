// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 mp0rta and mqvpn contributors

package com.mqvpn.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Per-provider row: live down/up rates, RTT, lifetime totals, and the chart's
 * colour dot. Tapping it renames the provider.
 *
 * Sits beside [PathCard] rather than replacing it: PathCard renders a raw
 * [com.mqvpn.sdk.core.model.PathInfo] and shares `pathStatusName` with the
 * event log, while this one renders a resolved [PathThroughput].
 */
@Composable
fun ProviderCard(path: PathThroughput, onRename: (String?) -> Unit) {
    val icon = when {
        path.key.startsWith("wifi") || path.key.startsWith("wlan") -> Icons.Default.Wifi
        path.key.startsWith("cellular") || path.key.startsWith("rmnet") ||
            path.key.startsWith("ccmni") -> Icons.Default.SignalCellularAlt
        else -> Icons.Default.Cable
    }
    var showRename by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .clickable { showRename = true }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = icon, contentDescription = path.key)
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(seriesColor(path.colorSlot)),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("${path.label} — ${pathStatusName(path.status)}")
                    if (path.isStale) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Default.WarningAmber,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "↓ ${formatBps(path.downBps)}   ↑ ${formatBps(path.upBps)}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    // Which link is actually carrying the traffic right now —
                    // with a weighted scheduler that is not answerable from the
                    // rates alone without doing the arithmetic in your head.
                    if (path.downShare > 0f) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "${(path.downShare * 100).roundToInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = seriesColor(path.colorSlot),
                        )
                    }
                }
                Text(
                    // A stale RTT is worse than no RTT: it reads as a healthy
                    // link. Say how long the silence has lasted instead.
                    if (path.isStale) {
                        "no reply for ${path.noReplyMs / 1000}s · total ↓ " +
                            "${formatBytes(path.totalRx)} ↑ ${formatBytes(path.totalTx)} · ${path.key}"
                    } else {
                        "RTT ${path.srttMs} ms · total ↓ ${formatBytes(path.totalRx)} " +
                            "↑ ${formatBytes(path.totalTx)} · ${path.key}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (path.isStale) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }

    if (showRename) {
        var name by remember(path.label) { mutableStateOf(path.label) }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("Rename provider") },
            text = {
                Column {
                    Text(
                        "Shown for this network everywhere in the app. " +
                            "Leave empty to restore the automatic name.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(modifier = Modifier.padding(4.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        label = { Text("Name (e.g. Starlink)") },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onRename(name.trim().ifEmpty { null })
                    showRename = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showRename = false }) { Text("Cancel") }
            },
        )
    }
}
