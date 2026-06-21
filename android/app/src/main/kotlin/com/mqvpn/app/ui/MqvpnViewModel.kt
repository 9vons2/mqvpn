// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 mp0rta and mqvpn contributors

package com.mqvpn.app.ui

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mqvpn.app.data.ConfigRepository
import com.mqvpn.app.data.UiConfig
import com.mqvpn.app.service.MyVpnService
import com.mqvpn.sdk.core.MqvpnManager
import com.mqvpn.sdk.core.model.MqvpnConfig
import com.mqvpn.sdk.core.model.MqvpnState
import com.mqvpn.sdk.core.model.PathInfo
import com.mqvpn.sdk.core.model.VpnStats
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MqvpnViewModel @Inject constructor(
    private val manager: MqvpnManager,
    private val configRepo: ConfigRepository,
) : ViewModel() {

    val vpnState: StateFlow<MqvpnState> = manager.vpnState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MqvpnState.Disconnected)

    val stats: StateFlow<VpnStats> = manager.stats
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), VpnStats())

    val paths: StateFlow<List<PathInfo>> = manager.paths
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val uiConfig: StateFlow<UiConfig> = configRepo.config
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            UiConfig(
                serverAddress = "",
                serverPort = "443",
                authKey = "",
                insecure = true,
                killSwitch = false,
                autoStart = false,
            ),
        )

    fun connect(config: MqvpnConfig) {
        manager.connect(config, MyVpnService::class.java)
    }

    fun disconnect() {
        manager.disconnect()
    }

    fun prepareVpn(): Intent? = manager.prepareVpn()

    fun updateServerAddress(value: String) = viewModelScope.launch {
        configRepo.setServerAddress(value)
    }
    fun updateServerPort(value: String) = viewModelScope.launch {
        configRepo.setServerPort(value)
    }
    fun updateAuthKey(value: String) = viewModelScope.launch {
        configRepo.setAuthKey(value)
    }
    fun updateInsecure(value: Boolean) = viewModelScope.launch {
        configRepo.setInsecure(value)
    }
    fun updateKillSwitch(value: Boolean) = viewModelScope.launch {
        configRepo.setKillSwitch(value)
    }
    fun updateAutoStart(value: Boolean) = viewModelScope.launch {
        configRepo.setAutoStart(value)
    }

    override fun onCleared() {
        manager.destroy()
    }
}
