// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 mp0rta and mqvpn contributors

package com.mqvpn.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import com.mqvpn.app.data.ConfigRepository
import com.mqvpn.app.service.MyVpnService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Auto-starts the VPN service after device boot when the user has enabled
 * the "Auto-start on boot" toggle in the UI.
 *
 * Android requires VpnService.prepare() to return null (consent already
 * given) for a background start to succeed. The standard way to satisfy
 * this is to enable "Always-on VPN" for mqvpn in
 * Settings → Network → VPN → mqvpn → ⚙ → Always-on VPN.
 *
 * If consent is not yet given, this receiver becomes a no-op and the user
 * must open the app once after boot to authorize the VPN session.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var configRepo: ConfigRepository

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            return
        }

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val cfg = configRepo.config.first()
                if (!cfg.autoStart) {
                    Log.i(TAG, "Auto-start disabled, skipping")
                    return@launch
                }
                if (cfg.serverAddress.isBlank() || cfg.authKey.isBlank()) {
                    Log.w(TAG, "No saved server address or auth key, skipping auto-start")
                    return@launch
                }

                // VpnService.prepare() returns null when consent is already given
                // (Always-on VPN, or previous user grant still valid).
                val prepareIntent = VpnService.prepare(context)
                if (prepareIntent != null) {
                    Log.w(TAG, "VPN consent not granted; enable Always-on VPN in system settings")
                    return@launch
                }

                // Start the service with no extras: MyVpnService.onStartCommand
                // will fall through to restoreConfig() which reads from the
                // service's own SharedPreferences (persisted from the last
                // successful connect).
                val svc = Intent(context, MyVpnService::class.java)
                context.startForegroundService(svc)
                Log.i(TAG, "Auto-started MyVpnService")
            } catch (e: Exception) {
                Log.e(TAG, "Auto-start failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "MqvpnBoot"
    }
}
