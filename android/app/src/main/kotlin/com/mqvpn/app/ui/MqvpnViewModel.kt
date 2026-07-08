// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 mp0rta and mqvpn contributors

package com.mqvpn.app.ui

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mqvpn.app.data.SettingsRepository
import com.mqvpn.app.service.MyVpnService
import com.mqvpn.sdk.core.MqvpnManager
import com.mqvpn.sdk.core.model.MqvpnConfig
import com.mqvpn.sdk.core.model.MqvpnState
import com.mqvpn.sdk.core.model.PathInfo
import com.mqvpn.sdk.core.model.ReorderStats
import com.mqvpn.sdk.core.model.VpnStats
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class MqvpnViewModel @Inject constructor(
    private val manager: MqvpnManager,
    private val settings: SettingsRepository,
) : ViewModel() {

    init {
        // Pick up a service auto-started at boot (or surviving the UI).
        manager.attachIfRunning(MyVpnService::class.java)
    }

    /** Last config the user connected with — null on first run. */
    val savedConfig: MqvpnConfig? = settings.loadConfig()

    var autoStartEnabled: Boolean
        get() = settings.autoStart
        set(value) { settings.autoStart = value }

    val vpnState: StateFlow<MqvpnState> = manager.vpnState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MqvpnState.Disconnected)

    val stats: StateFlow<VpnStats> = manager.stats
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), VpnStats())

    val paths: StateFlow<List<PathInfo>> = manager.paths
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val reorderStats: StateFlow<ReorderStats> = manager.reorderStats
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ReorderStats())

    fun connect(config: MqvpnConfig) {
        settings.saveConfig(config)
        manager.connect(config, MyVpnService::class.java)
    }

    fun disconnect() {
        manager.disconnect()
    }

    fun prepareVpn(): Intent? = manager.prepareVpn()

    override fun onCleared() {
        manager.destroy()
    }
}
