// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 mp0rta and mqvpn contributors

package com.mqvpn.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import com.mqvpn.app.data.SettingsRepository

/**
 * Auto-starts the VPN after device boot when the user enabled it.
 *
 * Requires all three: the auto-start toggle on, a saved config from a
 * previous connection, and VPN permission already granted (the consent
 * dialog cannot be shown from a receiver — if the user revoked it, they
 * must reconnect from the app once).
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != ACTION_QUICKBOOT_POWERON
        ) return

        val settings = SettingsRepository(context.applicationContext)
        if (!settings.autoStart) return
        val config = settings.loadConfig() ?: run {
            Log.w(TAG, "auto-start enabled but no saved config")
            return
        }
        if (VpnService.prepare(context) != null) {
            Log.w(TAG, "auto-start skipped: VPN permission not granted")
            return
        }

        Log.i(TAG, "auto-starting VPN to ${config.serverAddress}")
        context.startForegroundService(MyVpnService.startIntent(context, config))
    }

    companion object {
        private const val TAG = "MqvpnBootReceiver"

        // Some vendors (HTC, Xiaomi) broadcast this instead of BOOT_COMPLETED.
        private const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
    }
}
