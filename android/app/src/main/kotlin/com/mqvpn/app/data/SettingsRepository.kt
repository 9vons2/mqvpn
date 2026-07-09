// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 mp0rta and mqvpn contributors

package com.mqvpn.app.data

import android.content.Context
import android.content.SharedPreferences
import com.mqvpn.sdk.core.model.MqvpnConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** Named server profile. */
data class Profile(val name: String, val config: MqvpnConfig)

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

    // --- Server profiles ---

    fun loadProfiles(): List<Profile> {
        val raw = prefs.getString(KEY_PROFILES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = obj.optString("name").takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val config = try {
                    MqvpnConfig.fromJson(obj.optString("config"))
                } catch (_: Exception) {
                    return@mapNotNull null
                }
                Profile(name, config)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Insert or replace the profile with the same name. */
    fun saveProfile(name: String, config: MqvpnConfig) {
        val rest = loadProfiles().filterNot { it.name == name }
        storeProfiles(rest + Profile(name, config))
    }

    fun deleteProfile(name: String) {
        storeProfiles(loadProfiles().filterNot { it.name == name })
        if (activeProfile == name) activeProfile = null
    }

    private fun storeProfiles(profiles: List<Profile>) {
        val arr = JSONArray()
        profiles.forEach { p ->
            arr.put(
                JSONObject()
                    .put("name", p.name)
                    .put("config", p.config.toJson())
            )
        }
        prefs.edit().putString(KEY_PROFILES, arr.toString()).apply()
    }

    var activeProfile: String?
        get() = prefs.getString(KEY_ACTIVE_PROFILE, null)
        set(value) {
            prefs.edit().putString(KEY_ACTIVE_PROFILE, value).apply()
        }

    // --- Trusted Wi-Fi networks ---

    /** SSIDs where the VPN should not run (stored as a JSON array). */
    var trustedSsids: List<String>
        get() {
            val raw = prefs.getString(KEY_TRUSTED_SSIDS, null) ?: return emptyList()
            return try {
                val arr = JSONArray(raw)
                (0 until arr.length()).mapNotNull { i ->
                    arr.optString(i).takeIf { it.isNotBlank() }
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
        set(value) {
            val arr = JSONArray()
            value.map { it.trim() }.filter { it.isNotEmpty() }.forEach { arr.put(it) }
            prefs.edit().putString(KEY_TRUSTED_SSIDS, arr.toString()).apply()
        }

    companion object {
        private const val PREFS_NAME = "mqvpn_settings"
        private const val KEY_CONFIG_JSON = "last_config_json"
        private const val KEY_AUTOSTART = "autostart_on_boot"
        private const val KEY_PROFILES = "profiles_json"
        private const val KEY_ACTIVE_PROFILE = "active_profile"
        private const val KEY_TRUSTED_SSIDS = "trusted_ssids_json"
    }
}
