// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 mp0rta and mqvpn contributors

package com.mqvpn.app.service

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.mqvpn.app.data.SettingsRepository
import com.mqvpn.app.ui.MainActivity
import com.mqvpn.sdk.core.MqvpnManager
import com.mqvpn.sdk.core.model.MqvpnState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Quick Settings tile: one-tap connect/disconnect with the saved config.
 * Falls back to opening the app when there is no saved config or the VPN
 * permission has not been granted yet.
 */
@AndroidEntryPoint
class MqvpnTileService : TileService() {

    @Inject lateinit var manager: MqvpnManager

    @Inject lateinit var repository: SettingsRepository

    private val tileScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var scope: CoroutineScope? = null
    private var stateJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        manager.attachIfRunning(MyVpnService::class.java)
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = s
        stateJob = s.launch {
            manager.vpnState.collect { renderTile(it) }
        }
    }

    override fun onStopListening() {
        stateJob?.cancel()
        stateJob = null
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        when (manager.vpnState.value) {
            is MqvpnState.Connected,
            is MqvpnState.Connecting,
            is MqvpnState.Reconnecting,
            -> startService(MyVpnService.disconnectIntent(this))

            else -> {
                if (VpnService.prepare(this) != null) {
                    openApp() // permission revoked — needs the consent dialog
                    return
                }
                // Settings live in DataStore (suspending), so resolve the saved
                // config off the click thread and start once it is known good.
                tileScope.launch {
                    val saved = try {
                        repository.settings.first()
                    } catch (_: Exception) {
                        null
                    }
                    withContext(Dispatchers.Main) {
                        if (saved == null || !saved.isValid()) {
                            openApp() // nothing usable saved yet
                        } else {
                            startForegroundService(
                                MyVpnService.startIntent(this@MqvpnTileService, saved.toMqvpnConfig())
                            )
                            manager.attachIfRunning(MyVpnService::class.java)
                        }
                    }
                }
            }
        }
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            )
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }

    private fun renderTile(state: MqvpnState) {
        val tile = qsTile ?: return
        tile.state = when (state) {
            is MqvpnState.Connected,
            is MqvpnState.Connecting,
            is MqvpnState.Reconnecting,
            -> Tile.STATE_ACTIVE

            else -> Tile.STATE_INACTIVE
        }
        tile.updateTile()
    }

    override fun onDestroy() {
        tileScope.cancel()
        super.onDestroy()
    }
}
