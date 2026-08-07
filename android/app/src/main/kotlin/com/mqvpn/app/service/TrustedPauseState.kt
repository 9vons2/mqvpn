// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 mp0rta and mqvpn contributors

package com.mqvpn.app.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-level signal for the trusted-Wi-Fi pause state.
 *
 * The tunnel being parked on a trusted network is not a libmqvpn tunnel
 * state (the native side just sees Disconnected), so it can't ride on
 * [com.mqvpn.sdk.core.model.MqvpnState]. This tiny holder lets [MyVpnService]
 * publish "paused on SSID X" and the UI render a distinct paused state
 * instead of showing plain "Disconnected / Connect".
 */
object TrustedPauseState {
    private val _pausedSsid = MutableStateFlow<String?>(null)

    /** Non-null (the trusted SSID) while parked on a trusted network. */
    val pausedSsid: StateFlow<String?> = _pausedSsid.asStateFlow()

    fun setPaused(ssid: String?) {
        _pausedSsid.value = ssid
    }
}
