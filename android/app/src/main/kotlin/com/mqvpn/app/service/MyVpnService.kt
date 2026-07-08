// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 mp0rta and mqvpn contributors

package com.mqvpn.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.mqvpn.app.R
import com.mqvpn.app.data.SettingsRepository
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
        startTrustedWifiWatcher()
        return START_STICKY
    }

    // --- Trusted Wi-Fi ---

    private var wifiCallback: ConnectivityManager.NetworkCallback? = null
    private var ssidAtStart: String? = null

    /**
     * Auto-disconnects when the phone JOINS a trusted Wi-Fi network while
     * the tunnel is up (e.g. coming home to a router that already bonds
     * links itself). The network present at start is deliberately ignored —
     * an explicit user connect always wins over the trusted list.
     */
    private fun startTrustedWifiWatcher() {
        stopTrustedWifiWatcher()
        val trusted = SettingsRepository(applicationContext).trustedSsids
        if (trusted.isEmpty()) return
        val cm = getSystemService(ConnectivityManager::class.java) ?: return

        ssidAtStart = currentWifiSsid(applicationContext)
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val handler = Handler(mainLooper)
        val onWifiCaps: (NetworkCapabilities) -> Unit = { caps ->
            val ssid = ssidFromCaps(caps) ?: currentWifiSsid(applicationContext)
            if (ssid != null && ssid != ssidAtStart && ssid in trusted) {
                Log.i(TAG, "trusted Wi-Fi \"$ssid\" joined — stopping VPN")
                handler.post {
                    stopTunnel()
                    stopSelf()
                }
            }
        }
        // API 31+ redacts the SSID from WifiInfo unless the callback is
        // registered with FLAG_INCLUDE_LOCATION_INFO (plus location perm).
        val cb = if (Build.VERSION.SDK_INT >= 31) {
            object : ConnectivityManager.NetworkCallback(
                ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO
            ) {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                    onWifiCaps(caps)
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                    onWifiCaps(caps)
            }
        }
        try {
            cm.registerNetworkCallback(request, cb)
            wifiCallback = cb
        } catch (e: Exception) {
            Log.w(TAG, "trusted Wi-Fi watcher failed to start: ${e.message}")
        }
    }

    private fun stopTrustedWifiWatcher() {
        wifiCallback?.let { cb ->
            try {
                getSystemService(ConnectivityManager::class.java)
                    ?.unregisterNetworkCallback(cb)
            } catch (_: Exception) {
            }
        }
        wifiCallback = null
    }

    private fun ssidFromCaps(caps: NetworkCapabilities): String? {
        if (Build.VERSION.SDK_INT < 29) return null
        val info = caps.transportInfo as? WifiInfo ?: return null
        return normalizeSsid(info.ssid)
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

        // Split tunneling: listed apps bypass the tunnel entirely.
        config.excludedApps.forEach { pkg ->
            try {
                builder.addDisallowedApplication(pkg)
            } catch (_: PackageManager.NameNotFoundException) {
                Log.w(TAG, "excluded app not installed: $pkg")
            }
        }

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
        stopTrustedWifiWatcher()
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

        /**
         * Best-effort current Wi-Fi SSID. Needs ACCESS_FINE_LOCATION (and
         * location services on) — returns null when unavailable/redacted.
         */
        fun currentWifiSsid(context: Context): String? = try {
            @Suppress("DEPRECATION")
            val ssid = context.applicationContext
                .getSystemService(WifiManager::class.java)
                ?.connectionInfo?.ssid
            normalizeSsid(ssid)
        } catch (_: Exception) {
            null
        }

        fun normalizeSsid(raw: String?): String? {
            val s = raw?.removeSurrounding("\"")?.trim() ?: return null
            return s.takeIf { it.isNotEmpty() && it != WifiManager.UNKNOWN_SSID && it != "<unknown ssid>" }
        }
    }
}
