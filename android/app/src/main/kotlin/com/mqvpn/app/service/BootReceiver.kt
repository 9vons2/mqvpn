// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 mp0rta and mqvpn contributors

package com.mqvpn.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import com.mqvpn.app.data.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Auto-starts the VPN after device boot when the user enabled it.
 *
 * Requires all three: the auto-start toggle on, a saved config from a
 * previous connection, and VPN permission already granted (the consent
 * dialog cannot be shown from a receiver — if the user revoked it, they
 * must reconnect from the app once).
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var repository: SettingsRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            intent.action != ACTION_QUICKBOOT_POWERON
        ) return
        // LOCKED_BOOT fires first on direct-boot devices; BOOT_COMPLETED
        // follows after unlock. Guard against starting the service twice.
        if (intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            MyVpnService.isRunning
        ) return

        if (VpnService.prepare(context) != null) {
            Log.w(TAG, "auto-start skipped: VPN permission not granted")
            return
        }

        // The store is DataStore-backed (suspending) but a receiver has no
        // scope of its own; goAsync keeps the process alive for the short read.
        val pending = goAsync()
        val settings = try {
            runBlocking { withContext(Dispatchers.IO) { repository.settings.first() } }
        } catch (e: Exception) {
            Log.w(TAG, "auto-start: settings read failed: ${e.message}")
            pending.finish()
            return
        }
        if (!settings.autoStart) {
            pending.finish()
            return
        }
        if (!settings.isValid()) {
            Log.w(TAG, "auto-start enabled but the saved config is incomplete")
            pending.finish()
            return
        }
        val config = settings.toMqvpnConfig()

        // On a trusted Wi-Fi the service starts PAUSED and resumes itself
        // once the phone leaves that network — no manual tap needed.
        Log.i(TAG, "auto-starting VPN to ${config.serverAddress}")
        context.startForegroundService(
            MyVpnService.startIntent(context, config, pausedIfTrusted = true)
        )
        pending.finish()
    }

    companion object {
        private const val TAG = "MqvpnBootReceiver"

        // Some vendors (HTC, Xiaomi) broadcast this instead of BOOT_COMPLETED.
        private const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
    }
}
