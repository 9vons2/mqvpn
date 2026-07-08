// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 mp0rta and mqvpn contributors

package com.mqvpn.app.data

import android.content.Context
import android.content.SharedPreferences
import com.mqvpn.sdk.core.model.MqvpnConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the last-used connection settings and the auto-start flag.
 *
 * Plain constructor on purpose: [com.mqvpn.app.service.BootReceiver] runs
 * before any Hilt component exists and constructs this directly.
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs: SharedPreferences
        get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Last config the user connected with, or null on first run / bad data. */
    fun loadConfig(): MqvpnConfig? {
        val json = prefs.getString(KEY_CONFIG_JSON, null) ?: return null
        return try {
            MqvpnConfig.fromJson(json)
        } catch (_: Exception) {
            null
        }
    }

    fun saveConfig(config: MqvpnConfig) {
        prefs.edit().putString(KEY_CONFIG_JSON, config.toJson()).apply()
    }

    var autoStart: Boolean
        get() = prefs.getBoolean(KEY_AUTOSTART, false)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTOSTART, value).apply()
        }

    companion object {
        private const val PREFS_NAME = "mqvpn_settings"
        private const val KEY_CONFIG_JSON = "last_config_json"
        private const val KEY_AUTOSTART = "autostart_on_boot"
    }
}
