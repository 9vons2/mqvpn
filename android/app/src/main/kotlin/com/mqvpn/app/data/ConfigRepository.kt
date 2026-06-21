// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 mp0rta and mqvpn contributors

package com.mqvpn.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "mqvpn_ui")

/**
 * Persistent storage for UI-side configuration. Survives reboots and force-stop.
 *
 * Note: the VPN service itself has separate SharedPreferences persistence
 * (see MyVpnService.persistConfig). This DataStore exists so the UI can show
 * remembered values even before the user pressed Connect for the first time
 * after install, and to retain user input across app cold starts.
 */
@Singleton
class ConfigRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val store: DataStore<Preferences> = context.dataStore

    val config: Flow<UiConfig> = store.data.map { p ->
        UiConfig(
            serverAddress = p[KEY_SERVER] ?: "",
            serverPort = p[KEY_PORT] ?: "443",
            authKey = p[KEY_AUTH] ?: "",
            insecure = p[KEY_INSECURE] ?: true,
            killSwitch = p[KEY_KILLSWITCH] ?: false,
            autoStart = p[KEY_AUTOSTART] ?: false,
        )
    }

    suspend fun setServerAddress(value: String) = store.edit { it[KEY_SERVER] = value }
    suspend fun setServerPort(value: String) = store.edit { it[KEY_PORT] = value }
    suspend fun setAuthKey(value: String) = store.edit { it[KEY_AUTH] = value }
    suspend fun setInsecure(value: Boolean) = store.edit { it[KEY_INSECURE] = value }
    suspend fun setKillSwitch(value: Boolean) = store.edit { it[KEY_KILLSWITCH] = value }
    suspend fun setAutoStart(value: Boolean) = store.edit { it[KEY_AUTOSTART] = value }

    private companion object {
        val KEY_SERVER = stringPreferencesKey("server_address")
        val KEY_PORT = stringPreferencesKey("server_port")
        val KEY_AUTH = stringPreferencesKey("auth_key")
        val KEY_INSECURE = booleanPreferencesKey("insecure")
        val KEY_KILLSWITCH = booleanPreferencesKey("kill_switch")
        val KEY_AUTOSTART = booleanPreferencesKey("auto_start")
    }
}

data class UiConfig(
    val serverAddress: String,
    val serverPort: String,
    val authKey: String,
    val insecure: Boolean,
    val killSwitch: Boolean,
    val autoStart: Boolean,
)
