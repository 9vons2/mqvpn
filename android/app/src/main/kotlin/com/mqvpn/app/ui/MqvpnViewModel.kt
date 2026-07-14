// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 mp0rta and mqvpn contributors

package com.mqvpn.app.ui

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mqvpn.app.net.ProviderDirectory
import com.mqvpn.app.service.MyVpnService
import com.mqvpn.sdk.core.MqvpnManager
import com.mqvpn.sdk.core.model.MqvpnConfig
import com.mqvpn.sdk.core.model.MqvpnState
import com.mqvpn.sdk.core.model.PathInfo
import com.mqvpn.sdk.core.model.ReorderStats
import com.mqvpn.sdk.core.model.VpnStats
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class MqvpnViewModel @Inject constructor(
    private val manager: MqvpnManager,
    private val providers: ProviderDirectory,
) : ViewModel() {

    val vpnState: StateFlow<MqvpnState> = manager.vpnState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MqvpnState.Disconnected)

    val stats: StateFlow<VpnStats> = manager.stats
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), VpnStats())

    val paths: StateFlow<List<PathInfo>> = manager.paths
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val reorderStats: StateFlow<ReorderStats> = manager.reorderStats
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ReorderStats())

    private val tracker = SpeedTracker()

    /**
     * Per-provider rates + rolling history, resampled at 1 Hz from the
     * cumulative counters in [MqvpnManager.paths]. Runs only while the UI
     * collects it (WhileSubscribed), so no ticking in the background.
     */
    val throughput: StateFlow<ThroughputUi> = flow {
        providers.start()
        try {
            while (true) {
                val p = manager.paths.value
                if (p.isEmpty()) {
                    tracker.reset()
                    emit(ThroughputUi())
                } else {
                    emit(
                        tracker.update(
                            p,
                            providers.labels.value,
                            providers.customNames.value,
                            System.nanoTime() / 1_000_000,
                        )
                    )
                }
                delay(1000)
            }
        } finally {
            providers.stop()
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ThroughputUi())

    fun connect(config: MqvpnConfig) {
        manager.connect(config, MyVpnService::class.java)
    }

    fun disconnect() {
        manager.disconnect()
    }

    /** Persist a custom display name for the provider behind [pathKey]. */
    fun renameProvider(pathKey: String, name: String?) {
        val auto = providers.labels.value[pathKey] ?: SpeedTracker.fallbackLabel(pathKey)
        providers.rename(auto, name)
    }

    fun prepareVpn(): Intent? = manager.prepareVpn()

    override fun onCleared() {
        manager.destroy()
    }
}
