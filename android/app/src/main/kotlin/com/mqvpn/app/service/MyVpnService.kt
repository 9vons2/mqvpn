// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 mp0rta and mqvpn contributors

package com.mqvpn.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.mqvpn.app.R
import com.mqvpn.sdk.core.MqvpnVpnService
import com.mqvpn.sdk.core.model.MqvpnConfig
import com.mqvpn.sdk.core.model.MqvpnState
import com.mqvpn.sdk.core.model.TunnelInfo

class MyVpnService : MqvpnVpnService() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            stopTunnel()
            stopSelf()
            return START_NOT_STICKY
        }

        val configJson = intent?.getStringExtra(EXTRA_CONFIG_JSON)
        val config = if (configJson != null) {
            MqvpnConfig.fromJson(configJson)
        } else {
            restoreConfig()
        } ?: run {
            stopSelf()
            return START_NOT_STICKY
        }

        persistConfig(config)

        startForeground(
            NOTIFICATION_ID,
            buildNotification(getString(R.string.notif_connecting)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )

        startTunnel(config)
        return START_STICKY
    }

    override fun onCreateTun(info: TunnelInfo, config: MqvpnConfig): ParcelFileDescriptor {
        val builder = Builder()
            .setSession("mqvpn")
            .addAddress(info.assignedIp, info.prefix)
            .setMtu(info.mtu)
            .setBlocking(true)

        builder.addRoute("0.0.0.0", 0)

        val ip6 = info.assignedIp6
        if (info.hasV6 && ip6 != null) {
            builder.addAddress(ip6, info.prefix6)
            builder.addRoute("::", 0)
        } else if (config.killSwitch) {
            builder.addRoute("::", 0)
        }

        config.dnsServers.forEach { builder.addDnsServer(it) }

        return builder.establish()
            ?: throw IllegalStateException("VPN permission denied")
    }

    override fun onVpnStateChanged(newState: MqvpnState) {
        when (newState) {
            is MqvpnState.Connected ->
                updateNotification(
                    getString(R.string.notif_connected, newState.tunnelInfo.assignedIp)
                )
            is MqvpnState.Reconnecting ->
                updateNotification(getString(R.string.notif_reconnecting))
            is MqvpnState.Disconnected ->
                stopSelf()
            is MqvpnState.Error -> {
                updateNotification(
                    getString(R.string.notif_error, newState.error.message)
                )
                stopSelf()
            }
            else -> {}
        }
    }

    override fun onLog(level: Int, message: String) {
        when (level) {
            0 -> Log.d(TAG, message)
            1 -> Log.i(TAG, message)
            2 -> Log.w(TAG, message)
            3 -> Log.e(TAG, message)
        }
    }

    override fun onReconnectScheduled(delaySec: Int) {
        updateNotification(getString(R.string.notif_reconnecting_in, delaySec))
    }

    override fun onDestroy() {
        clearPersistedConfig()
        super.onDestroy()
    }

    // --- Config persistence ---

    private fun persistConfig(config: MqvpnConfig) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(KEY_CONFIG_JSON, config.toJson())
            .apply()
    }

    private fun restoreConfig(): MqvpnConfig? {
        val json = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(KEY_CONFIG_JSON, null) ?: return null
        return try {
            MqvpnConfig.fromJson(json)
        } catch (_: Exception) {
            null
        }
    }

    private fun clearPersistedConfig() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .remove(KEY_CONFIG_JSON)
            .apply()
    }

    // --- Notifications ---

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService<NotificationManager>()?.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val disconnectPi = PendingIntent.getService(
            this, 1, disconnectIntent(this), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_vpn)
            .setOngoing(true)
            .addAction(0, getString(R.string.notif_action_disconnect), disconnectPi)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService<NotificationManager>()
            ?.notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        private const val TAG = "MqvpnService"
        private const val CHANNEL_ID = "mqvpn_vpn"
        private const val NOTIFICATION_ID = 1
        private const val PREFS_NAME = "mqvpn_service"
        private const val KEY_CONFIG_JSON = "config_json"
        private const val EXTRA_CONFIG_JSON = "mqvpn_config_json"

        private const val ACTION_DISCONNECT = "com.mqvpn.app.action.DISCONNECT"

        /** Start intent carrying a config — used by [BootReceiver]. */
        fun startIntent(context: Context, config: MqvpnConfig): Intent =
            Intent(context, MyVpnService::class.java)
                .putExtra(EXTRA_CONFIG_JSON, config.toJson())

        /** Stop intent — used by the notification action and the QS tile. */
        fun disconnectIntent(context: Context): Intent =
            Intent(context, MyVpnService::class.java).setAction(ACTION_DISCONNECT)
    }
}
