// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 mp0rta and mqvpn contributors

package com.mqvpn.app.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mqvpn.app.R
import com.mqvpn.sdk.core.model.MqvpnConfig
import com.mqvpn.sdk.core.model.MqvpnState
import com.mqvpn.sdk.core.model.ReorderStats
import com.mqvpn.sdk.core.model.VpnStats

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(
    modifier: Modifier = Modifier,
    viewModel: MqvpnViewModel = hiltViewModel(),
) {
    val state by viewModel.vpnState.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val paths by viewModel.paths.collectAsStateWithLifecycle()
    val reorderStats by viewModel.reorderStats.collectAsStateWithLifecycle()

    // Initial field values: last saved config, falling back to defaults.
    val saved = remember { viewModel.savedConfig }
    var serverAddress by rememberSaveable {
        mutableStateOf(saved?.serverAddress ?: "160.251.143.149")
    }
    var serverPort by rememberSaveable {
        mutableStateOf((saved?.serverPort ?: 443).toString())
    }
    var authKey by rememberSaveable {
        mutableStateOf(saved?.authKey ?: "tiiUC0/Fx51w5XuxAnpOgdRZb19SLqglwFdhxbbsbnM=")
    }
    var insecure by rememberSaveable { mutableStateOf(saved?.insecure ?: true) }
    var killSwitch by rememberSaveable { mutableStateOf(saved?.killSwitch ?: false) }
    var autoStart by rememberSaveable { mutableStateOf(viewModel.autoStartEnabled) }
    var reorderEnabled by rememberSaveable { mutableStateOf(saved?.reorderEnabled ?: false) }
    var reorderProfileName by rememberSaveable {
        mutableStateOf((saved?.reorderProfile ?: MqvpnConfig.ReorderProfile.CELLULAR_BOND).name)
    }
    val reorderProfile = MqvpnConfig.ReorderProfile.entries.firstOrNull {
        it.name == reorderProfileName
    } ?: MqvpnConfig.ReorderProfile.CELLULAR_BOND
    var reorderPorts by rememberSaveable {
        mutableStateOf(saved?.reorderPorts?.joinToString(",") ?: "")
    }
    var hybridEnabled by rememberSaveable { mutableStateOf(saved?.hybridEnabled ?: false) }
    var hybridTcpModeName by rememberSaveable {
        mutableStateOf((saved?.hybridTcpMode ?: MqvpnConfig.HybridTcpMode.AUTO).name)
    }
    val hybridTcpMode = MqvpnConfig.HybridTcpMode.entries.firstOrNull {
        it.name == hybridTcpModeName
    } ?: MqvpnConfig.HybridTcpMode.AUTO

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.connect(
                buildConfig(
                    serverAddress, serverPort, authKey, insecure, killSwitch,
                    reorderEnabled, reorderProfile, reorderPorts,
                    hybridEnabled, hybridTcpMode,
                )
            )
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text("mqvpn", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))

        // Server config inputs
        val isDisconnected = state is MqvpnState.Disconnected || state is MqvpnState.Error
        OutlinedTextField(
            value = serverAddress,
            onValueChange = { serverAddress = it },
            label = { Text(stringResource(R.string.server_address)) },
            modifier = Modifier.fillMaxWidth(),
            enabled = isDisconnected,
            singleLine = true,
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = serverPort,
            onValueChange = { serverPort = it },
            label = { Text(stringResource(R.string.server_port)) },
            modifier = Modifier.fillMaxWidth(),
            enabled = isDisconnected,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = authKey,
            onValueChange = { authKey = it },
            label = { Text(stringResource(R.string.auth_key)) },
            modifier = Modifier.fillMaxWidth(),
            enabled = isDisconnected,
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
        Spacer(modifier = Modifier.height(8.dp))

        SettingSwitchRow(
            label = stringResource(R.string.insecure_skip_tls),
            description = stringResource(R.string.insecure_desc),
            checked = insecure,
            onCheckedChange = { insecure = it },
            enabled = isDisconnected,
        )
        SettingSwitchRow(
            label = stringResource(R.string.kill_switch),
            description = stringResource(R.string.kill_switch_desc),
            checked = killSwitch,
            onCheckedChange = { killSwitch = it },
            enabled = isDisconnected,
        )
        SettingSwitchRow(
            label = stringResource(R.string.autostart_on_boot),
            description = stringResource(R.string.autostart_desc),
            checked = autoStart,
            onCheckedChange = {
                autoStart = it
                viewModel.autoStartEnabled = it
            },
        )

        // Battery-optimization hint: OEM power managers kill background
        // VPNs and silently block boot auto-start — surface the fix inline.
        val context = LocalContext.current
        val powerManager = remember { context.getSystemService(PowerManager::class.java) }
        var ignoringBatteryOpt by remember {
            mutableStateOf(
                powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: true
            )
        }
        val batteryLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            ignoringBatteryOpt =
                powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: true
        }
        if (autoStart && !ignoringBatteryOpt) {
            Spacer(modifier = Modifier.height(4.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(stringResource(R.string.battery_title), style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.battery_text),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            batteryLauncher.launch(
                                Intent(
                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:${context.packageName}"),
                                )
                            )
                        },
                    ) {
                        Text(stringResource(R.string.battery_button))
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        // Reorder buffer settings
        SettingSwitchRow(
            label = stringResource(R.string.reorder_buffer),
            description = stringResource(R.string.reorder_desc),
            checked = reorderEnabled,
            onCheckedChange = { reorderEnabled = it },
            enabled = isDisconnected,
        )
        if (reorderEnabled) {
            Spacer(modifier = Modifier.height(8.dp))
            var profileExpanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = profileExpanded,
                onExpandedChange = { if (isDisconnected) profileExpanded = it },
            ) {
                OutlinedTextField(
                    value = reorderProfile.name.replace("_", " "),
                    onValueChange = {},
                    label = { Text(stringResource(R.string.reorder_profile)) },
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = profileExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                    enabled = isDisconnected,
                )
                ExposedDropdownMenu(
                    expanded = profileExpanded,
                    onDismissRequest = { profileExpanded = false },
                ) {
                    MqvpnConfig.ReorderProfile.entries.forEach { profile ->
                        DropdownMenuItem(
                            text = { Text(profile.name.replace("_", " ")) },
                            onClick = {
                                reorderProfileName = profile.name
                                profileExpanded = false
                            },
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = reorderPorts,
                onValueChange = { reorderPorts = it },
                label = { Text(stringResource(R.string.reorder_ports_hint)) },
                modifier = Modifier.fillMaxWidth(),
                enabled = isDisconnected,
                singleLine = true,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))

        // Hybrid mode (TCP lane) settings
        SettingSwitchRow(
            label = stringResource(R.string.hybrid_mode),
            description = stringResource(R.string.hybrid_desc),
            checked = hybridEnabled,
            onCheckedChange = { hybridEnabled = it },
            enabled = isDisconnected,
        )
        if (hybridEnabled) {
            Spacer(modifier = Modifier.height(8.dp))
            var tcpModeExpanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = tcpModeExpanded,
                onExpandedChange = { if (isDisconnected) tcpModeExpanded = it },
            ) {
                OutlinedTextField(
                    value = hybridTcpMode.name,
                    onValueChange = {},
                    label = { Text(stringResource(R.string.hybrid_tcp_mode)) },
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = tcpModeExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                    enabled = isDisconnected,
                )
                ExposedDropdownMenu(
                    expanded = tcpModeExpanded,
                    onDismissRequest = { tcpModeExpanded = false },
                ) {
                    MqvpnConfig.HybridTcpMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(mode.name) },
                            onClick = {
                                hybridTcpModeName = mode.name
                                tcpModeExpanded = false
                            },
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        // Connect/Disconnect button
        Button(
            onClick = {
                when (state) {
                    is MqvpnState.Connected,
                    is MqvpnState.Reconnecting -> viewModel.disconnect()

                    is MqvpnState.Disconnected,
                    is MqvpnState.Error -> {
                        val prepareIntent = viewModel.prepareVpn()
                        if (prepareIntent != null) {
                            vpnPermissionLauncher.launch(prepareIntent)
                        } else {
                            viewModel.connect(
                                buildConfig(
                                    serverAddress, serverPort, authKey, insecure, killSwitch,
                                    reorderEnabled, reorderProfile, reorderPorts,
                                    hybridEnabled, hybridTcpMode,
                                )
                            )
                        }
                    }

                    else -> {}
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = state !is MqvpnState.Connecting,
        ) {
            Text(
                when (state) {
                    is MqvpnState.Connected -> stringResource(R.string.btn_disconnect)
                    is MqvpnState.Connecting -> stringResource(R.string.btn_connecting)
                    is MqvpnState.Reconnecting -> stringResource(R.string.btn_reconnecting)
                    else -> stringResource(R.string.btn_connect)
                }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Status
        when (val s = state) {
            is MqvpnState.Connected -> {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            stringResource(R.string.status_connected),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            stringResource(
                                R.string.status_ip, s.tunnelInfo.assignedIp, s.tunnelInfo.prefix,
                            )
                        )
                        val ip6 = s.tunnelInfo.assignedIp6
                        if (s.tunnelInfo.hasV6 && ip6 != null) {
                            Text(stringResource(R.string.status_ipv6, ip6, s.tunnelInfo.prefix6))
                        }
                        Text(stringResource(R.string.status_mtu, s.tunnelInfo.mtu))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(stringResource(R.string.status_rtt, stats.srttMs))
                        Text(
                            stringResource(
                                R.string.status_tx_rx,
                                formatBytes(stats.bytesTx), formatBytes(stats.bytesRx),
                            )
                        )
                        Text(
                            stringResource(
                                R.string.status_dgram,
                                stats.dgramSent, stats.dgramRecv, stats.dgramLost,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                if (paths.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(stringResource(R.string.paths_title), style = MaterialTheme.typography.titleSmall)
                    BandwidthChart(paths)
                    Spacer(modifier = Modifier.height(4.dp))
                    paths.forEach { path -> PathCard(path) }
                }

                if (reorderStats.delivered > 0 || reorderStats.gapCount > 0) {
                    Spacer(modifier = Modifier.height(12.dp))
                    ReorderStatsCard(reorderStats)
                }

                if (stats.pktsLaneTcp + stats.pktsLaneDgram + stats.pktsLaneRaw > 0) {
                    Spacer(modifier = Modifier.height(12.dp))
                    HybridLaneStatsCard(stats)
                }
            }

            is MqvpnState.Reconnecting -> {
                Text(
                    stringResource(R.string.reconnecting_in, s.info.delaySec),
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            is MqvpnState.Error -> {
                Text(
                    stringResource(R.string.error_prefix, s.error.message),
                    color = MaterialTheme.colorScheme.error,
                )
            }

            else -> {}
        }
    }
}

/**
 * Switch row with an ⓘ toggle that expands a detailed description below —
 * the settings are dense enough that labels alone stop being memorable.
 */
@Composable
private fun SettingSwitchRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    var showInfo by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        IconButton(onClick = { showInfo = !showInfo }) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.outline,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
    if (showInfo) {
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }
}

@Composable
private fun ReorderStatsCard(rs: ReorderStats) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(stringResource(R.string.reorder_stats_title), style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(4.dp))
            val fillRate = if (rs.gapCount > 0) {
                "%.1f%%".format(rs.gapFilled * 100.0 / rs.gapCount)
            } else "—"
            Text(stringResource(R.string.reorder_delivered_gaps, rs.delivered, rs.gapCount, fillRate))
            Text(
                stringResource(R.string.reorder_timeout_ack, rs.gapTimeout, rs.ackDemote),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                stringResource(R.string.reorder_latency, rs.bufferedP50Ms, rs.bufferedP99Ms),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun HybridLaneStatsCard(stats: VpnStats) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(stringResource(R.string.hybrid_stats_title), style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                stringResource(
                    R.string.hybrid_lane_counts,
                    stats.pktsLaneTcp, stats.pktsLaneDgram, stats.pktsLaneRaw,
                )
            )
            Text(
                stringResource(
                    R.string.hybrid_flows,
                    stats.tcpFlowsActive, stats.tcpFlowsTotal, stats.tcpFlowsRejected,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            if (stats.pktsLaneTcpDropped > 0 || stats.rawMarkersActive > 0) {
                Text(
                    stringResource(
                        R.string.hybrid_dropped,
                        stats.pktsLaneTcpDropped, stats.rawMarkersActive,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun buildConfig(
    address: String,
    port: String,
    key: String,
    insecure: Boolean,
    killSwitch: Boolean,
    reorderEnabled: Boolean,
    reorderProfile: MqvpnConfig.ReorderProfile,
    reorderPorts: String,
    hybridEnabled: Boolean,
    hybridTcpMode: MqvpnConfig.HybridTcpMode,
): MqvpnConfig {
    return MqvpnConfig(
        serverAddress = address.trim(),
        serverPort = port.trim().toIntOrNull() ?: 443,
        authKey = key.trim(),
        insecure = insecure,
        killSwitch = killSwitch,
        reorderEnabled = reorderEnabled,
        reorderProfile = reorderProfile,
        reorderPorts = reorderPorts.split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 1..65535 },
        hybridEnabled = hybridEnabled,
        hybridTcpMode = hybridTcpMode,
    )
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000 -> "%.1f KB".format(bytes / 1_000.0)
        else -> "$bytes B"
    }
}
