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

private val PATH_STATUS_NAMES = mapOf(
    0 to "Pending",
    1 to "Active",
    2 to "Degraded",
    3 to "Standby",
    4 to "Closed",
)

/**
 * One provider row: name (tap to rename), live down/up rates, RTT, status,
 * and lifetime totals. The color dot matches the chart series.
 */
@Composable
fun PathCard(path: PathThroughput, onRename: (String?) -> Unit) {
    val icon = when {
        path.key.startsWith("wifi") || path.key.startsWith("wlan") -> Icons.Default.Wifi
        path.key.startsWith("cellular") || path.key.startsWith("rmnet") ||
            path.key.startsWith("ccmni") -> Icons.Default.SignalCellularAlt
        else -> Icons.Default.Cable
    }
    val statusName = PATH_STATUS_NAMES[path.status] ?: "Unknown"
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
                    Text("${path.label} — $statusName")
                }
                Text(
                    "↓ ${formatBps(path.downBps)}   ↑ ${formatBps(path.upBps)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    "RTT ${path.srttMs} ms · total ↓ ${formatBytes(path.totalRx)} " +
                        "↑ ${formatBytes(path.totalTx)} · ${path.key}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
