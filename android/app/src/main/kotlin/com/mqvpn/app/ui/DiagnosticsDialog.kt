// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 mp0rta and mqvpn contributors

package com.mqvpn.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mqvpn.app.service.MyVpnService

/**
 * Shows the persisted diagnostic trace and lets it leave the phone.
 *
 * Shared as intent text rather than a file: a FileProvider would be the
 * tidier route for arbitrary sizes, but the log is capped at 64 KB precisely
 * so it fits in an intent, and this keeps a debugging aid from adding a
 * provider and its manifest surface to the app.
 */
@Composable
fun DiagnosticsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    // Read once per open: the file is appended by the service, and a trace
    // that reflows while being read is harder to follow, not easier.
    var text by remember { mutableStateOf(MyVpnService.diagnosticsLog(context).read()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Diagnostics") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (text.isBlank()) {
                    Text(
                        "Nothing recorded yet. The log fills while the tunnel runs and " +
                            "survives restarts, so after a trip it shows what happened.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState()),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { shareText(context, text) },
                enabled = text.isNotBlank(),
            ) { Text("Share") }
        },
        dismissButton = {
            Column {
                TextButton(
                    onClick = { copyText(context, text) },
                    enabled = text.isNotBlank(),
                ) { Text("Copy") }
                TextButton(
                    onClick = {
                        MyVpnService.diagnosticsLog(context).clear()
                        text = ""
                    },
                    enabled = text.isNotBlank(),
                ) { Text("Clear") }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
}

private fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "mqvpn diagnostics")
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, "Share diagnostics"))
}

private fun copyText(context: Context, text: String) {
    val cm = context.getSystemService(ClipboardManager::class.java) ?: return
    cm.setPrimaryClip(ClipData.newPlainText("mqvpn diagnostics", text))
}
