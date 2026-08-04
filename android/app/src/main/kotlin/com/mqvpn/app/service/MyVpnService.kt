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
import com.mqvpn.app.ui.SpeedTracker
import com.mqvpn.app.ui.formatBps
import com.mqvpn.sdk.core.MqvpnVpnService
import com.mqvpn.sdk.core.model.MqvpnConfig
import com.mqvpn.sdk.core.model.MqvpnError
import com.mqvpn.sdk.core.model.MqvpnState
import com.mqvpn.sdk.core.model.PathInfo
import com.mqvpn.sdk.core.model.TunnelInfo
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class MyVpnService : MqvpnVpnService() {

    @Inject lateinit var settingsRepo: SettingsRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val diag: DiagnosticsLog by lazy { diagnosticsLog(applicationContext) }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        diag.log("service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            diag.log("disconnect requested by user")
            setPaused(null)
            stopTunnel()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RESUME) {
            resumeFromTrustedPause("manual resume")
            return START_STICKY
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

        lastConfig = config
        persistConfig(config)

        startForeground(
            NOTIFICATION_ID,
            buildNotification("Connecting..."),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )

        // Boot auto-start on a trusted network parks in paused mode and waits
        // for the phone to leave it. A manual connect never does — the user's
        // explicit tap always wins over the trusted list.
        val parkIfTrusted = intent?.getBooleanExtra(EXTRA_START_PAUSED_IF_TRUSTED, false) == true
        serviceScope.launch {
            val trusted = loadTrustedSsids()
            val ssid = currentWifiSsid(applicationContext)
            val park = parkIfTrusted && ssid != null && ssid in trusted
            withMain {
                if (park) {
                    setPaused(ssid)
                    updateNotification("Paused: trusted Wi-Fi \"$ssid\"")
                    Log.i(TAG, "started paused: on trusted Wi-Fi \"$ssid\"")
                } else {
                    setPaused(null)
                    startTunnel(config)
                }
                startTrustedWifiWatcher(trusted)
            }
        }
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
        diag.log("state → ${newState::class.simpleName}")
        when (newState) {
            is MqvpnState.Connected -> {
                connectedForNotif = true
                lastNotifPollMs = 0L
                notifPrev.clear()
                diagPathStatus.clear()
                diagSilentSince.clear()
                updateNotification("Connected: ${newState.tunnelInfo.assignedIp}")
            }
            is MqvpnState.Reconnecting -> {
                connectedForNotif = false
                updateNotification("Reconnecting...")
            }
            is MqvpnState.Disconnected -> {
                connectedForNotif = false
                // Paused on trusted Wi-Fi: the service stays alive, watching
                // for the network change that will resume the tunnel.
                if (!pausedOnTrustedWifi) stopSelf()
            }
            is MqvpnState.Error -> {
                connectedForNotif = false
                diag.log("ERROR ${newState.error.message}")
                updateNotification("Error: ${newState.error.message}")
                // Do NOT stop here for a dropped connection. libmqvpn reports
                // the close first and only then decides whether to reconnect —
                // and that decision is `!shutting_down && reconnect_enable`.
                // Stopping the service runs cleanup(), which sets
                // shutting_down, so stopSelf() on every error was racing the
                // library and, whenever it won, permanently disabling the
                // reconnect the user had configured. That is the tunnel that
                // "hangs" on the road and needs a manual reconnect.
                //
                // Only give up on errors no retry can fix.
                if (isFatal(newState.error)) {
                    diag.log("fatal — stopping service")
                    stopSelf()
                } else {
                    diag.log("waiting for the library to reconnect")
                }
            }
            else -> {}
        }
    }

    /**
     * Errors where retrying is pointless: no amount of reconnecting fixes a
     * wrong key, a rejected certificate, or a TUN the OS refused to hand over.
     * Everything else — dropped connections above all — is exactly what the
     * reconnect logic exists for.
     */
    private fun isFatal(error: MqvpnError): Boolean = when (error) {
        is MqvpnError.TunCreationFailed,
        is MqvpnError.AuthFailed,
        is MqvpnError.AbiMismatch,
        -> true
        else -> false
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
        diag.log("reconnect scheduled in ${delaySec}s")
        updateNotification("Reconnecting in ${delaySec}s...")
    }

    override fun onDestroy() {
        diag.log("service destroyed")
        isRunning = false
        connectedForNotif = false
        TrustedPauseState.setPaused(null)
        stopTrustedWifiWatcher()
        clearPersistedConfig()
        serviceScope.cancel()
        super.onDestroy()
    }

    // --- Trusted Wi-Fi pause/resume ---

    private var wifiCallback: ConnectivityManager.NetworkCallback? = null
    private var ssidAtStart: String? = null
    private var lastConfig: MqvpnConfig? = null

    /** True while the tunnel is parked because we're on a trusted network. */
    private var pausedOnTrustedWifi = false

    private suspend fun loadTrustedSsids(): List<String> = try {
        settingsRepo.settings.first().parsedTrustedSsids()
    } catch (_: Exception) {
        emptyList()
    }

    private suspend fun withMain(block: () -> Unit) {
        kotlinx.coroutines.withContext(Dispatchers.Main) { block() }
    }

    /**
     * JOINING a trusted Wi-Fi while the tunnel is up pauses it (the service
     * stays alive, watching); leaving the trusted network — to another Wi-Fi
     * or to cellular — resumes it. The network present at start is
     * deliberately ignored: an explicit user connect wins over the list.
     */
    private fun startTrustedWifiWatcher(trusted: List<String>) {
        stopTrustedWifiWatcher()
        if (trusted.isEmpty()) return
        val cm = getSystemService(ConnectivityManager::class.java) ?: return

        ssidAtStart = currentWifiSsid(applicationContext)
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val handler = Handler(mainLooper)
        val onWifiCaps: (NetworkCapabilities) -> Unit = { caps ->
            val ssid = ssidFromCaps(caps) ?: currentWifiSsid(applicationContext)
            handler.post {
                when {
                    !pausedOnTrustedWifi &&
                        ssid != null && ssid != ssidAtStart && ssid in trusted -> {
                        // System Always-on VPN fights this feature: lockdown
                        // blackholes ALL traffic the moment we stop, and plain
                        // always-on force-restarts us in a loop. Warn instead.
                        if (Build.VERSION.SDK_INT >= 29 && (isAlwaysOn || isLockdownEnabled)) {
                            Log.w(
                                TAG,
                                "trusted Wi-Fi \"$ssid\" but system always-on " +
                                    "VPN active — skipping auto-pause",
                            )
                            updateNotification(
                                "Trusted Wi-Fi detected, but system Always-on VPN " +
                                    "blocks auto-pause",
                            )
                        } else {
                            Log.i(TAG, "trusted Wi-Fi \"$ssid\" joined — pausing VPN")
                            diag.log("paused on trusted Wi-Fi \"$ssid\"")
                            setPaused(ssid)
                            stopTunnel()
                            updateNotification("Paused: trusted Wi-Fi \"$ssid\"")
                        }
                    }

                    pausedOnTrustedWifi && ssid != null && ssid !in trusted ->
                        resumeFromTrustedPause("switched to Wi-Fi \"$ssid\"")
                }
            }
        }
        val onWifiLost: () -> Unit = {
            handler.post {
                if (pausedOnTrustedWifi && currentWifiSsid(applicationContext) == null) {
                    resumeFromTrustedPause("Wi-Fi lost")
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

                override fun onLost(network: Network) = onWifiLost()
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                    onWifiCaps(caps)

                override fun onLost(network: Network) = onWifiLost()
            }
        }
        try {
            cm.registerNetworkCallback(request, cb)
            wifiCallback = cb
        } catch (e: Exception) {
            Log.w(TAG, "trusted Wi-Fi watcher failed to start: ${e.message}")
        }
    }

    private fun resumeFromTrustedPause(reason: String) {
        if (!pausedOnTrustedWifi) return
        val config = lastConfig ?: restoreConfig()
        if (config == null) {
            Log.w(TAG, "cannot resume from trusted pause: no config")
            stopSelf()
            return
        }
        Log.i(TAG, "left trusted Wi-Fi ($reason) — resuming VPN")
        diag.log("resuming: $reason")
        setPaused(null)
        ssidAtStart = currentWifiSsid(applicationContext)
        updateNotification("Connecting...")
        startTunnel(config)
    }

    /** Single source of truth for the paused flag + the UI-facing signal. */
    private fun setPaused(ssid: String?) {
        pausedOnTrustedWifi = ssid != null
        TrustedPauseState.setPaused(ssid)
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

    // --- Live throughput in the notification ---

    private var connectedForNotif = false
    private var lastNotifPollMs = 0L
    private val notifPrev = HashMap<String, Pair<Long, Long>>() // iface -> (tx, rx)

    /**
     * Refreshes the ongoing notification with per-provider down/up rates, at
     * most ~once a second (which also yields a clean per-second rate). Only
     * while actively connected — paused/reconnecting keep their text.
     */
    override fun onPathsPolled(paths: List<PathInfo>) {
        diagnosePaths(paths)
        if (!connectedForNotif || pausedOnTrustedWifi || paths.isEmpty()) return
        val now = System.currentTimeMillis()
        val first = lastNotifPollMs == 0L
        val dt = (now - lastNotifPollMs) / 1000.0
        if (!first && dt < NOTIF_MIN_INTERVAL_SEC) return

        val overrides = customProviderNames()
        var aggDown = 0.0
        var aggUp = 0.0
        val lines = ArrayList<String>(paths.size)
        for (p in paths) {
            val prev = notifPrev[p.iface]
            if (!first && prev != null && dt > 0) {
                val down = (p.bytesRx - prev.second).coerceAtLeast(0) * 8 / dt
                val up = (p.bytesTx - prev.first).coerceAtLeast(0) * 8 / dt
                aggDown += down
                aggUp += up
                val auto = SpeedTracker.fallbackLabel(p.iface)
                val label = overrides[auto.lowercase()] ?: auto
                lines += "$label   ↓ ${formatBps(down)}  ↑ ${formatBps(up)} · ${p.srttMs} ms"
            }
            notifPrev[p.iface] = p.bytesTx to p.bytesRx
        }
        notifPrev.keys.retainAll(paths.map { it.iface }.toSet())
        lastNotifPollMs = now
        if (first || lines.isEmpty()) return // baseline tick: no rates yet

        val aggregate = "↓ ${formatBps(aggDown)}   ↑ ${formatBps(aggUp)}"
        updateRichNotification(aggregate, (listOf(aggregate) + lines).joinToString("\n"))
    }

    // --- Diagnostics ---

    // Keyed by path handle, NOT by iface. get_paths() returns closed paths
    // alongside live ones, and a re-created path reuses its iface name, so two
    // entries can share it — an iface-keyed map then flip-flops between their
    // statuses on every single poll tick and floods the log with phantom
    // transitions. The handle is the only stable per-path identity.
    private val diagPathStatus = HashMap<Long, Int>()
    private val diagPrev = HashMap<Long, Pair<Long, Long>>()
    private val diagSilentSince = HashMap<Long, Long>()
    private val diagReported = HashSet<Long>()

    /**
     * Records path lifecycle and, more importantly, the failure this app keeps
     * hitting: a link the OS still calls up, where sends keep succeeding and
     * nothing ever comes back. libmqvpn counts bytes_tx per successful
     * sendto() and bytes_rx only per packet actually received, so "tx climbing,
     * rx frozen" names that state precisely — and unlike srtt, which freezes at
     * its last live measurement, it cannot lie about a dead link.
     *
     * Only transitions are written. This runs on every poll tick, so logging
     * per-tick state would bury the events worth reading.
     */
    private fun diagnosePaths(paths: List<PathInfo>) {
        val now = System.currentTimeMillis()
        val seen = HashSet<Long>()

        for (p in paths) {
            seen += p.handle
            val prevStatus = diagPathStatus.put(p.handle, p.status)
            if (prevStatus == null) {
                diag.log("path ${p.iface} appeared (status=${p.status})")
            } else if (prevStatus != p.status) {
                diag.log("path ${p.iface} status $prevStatus → ${p.status}")
            }

            val prev = diagPrev.put(p.handle, p.bytesTx to p.bytesRx)
            if (prev == null) continue
            val txMoved = p.bytesTx > prev.first
            val rxMoved = p.bytesRx > prev.second

            when {
                rxMoved -> {
                    if (diagReported.remove(p.handle)) {
                        val since = diagSilentSince[p.handle]
                        val secs = if (since != null) (now - since) / 1000 else 0
                        diag.log("path ${p.iface} answering again after ${secs}s of silence")
                    }
                    diagSilentSince.remove(p.handle)
                }
                txMoved -> {
                    val since = diagSilentSince.getOrPut(p.handle) { now }
                    val silentMs = now - since
                    if (silentMs >= SILENT_REPORT_MS && diagReported.add(p.handle)) {
                        diag.log(
                            "path ${p.iface} SILENT: sending for ${silentMs / 1000}s with no " +
                                "reply (srtt still reports ${p.srttMs} ms, status=${p.status})",
                        )
                    }
                }
                // Neither counter moved: idle, not broken. Leave the timer be —
                // an idle gap must not clear a silence that is still running.
            }
        }

        val gone = diagPathStatus.keys - seen
        for (handle in gone) {
            diag.log("path handle $handle gone")
            diagPathStatus.remove(handle)
            diagPrev.remove(handle)
            diagSilentSince.remove(handle)
            diagReported.remove(handle)
        }
    }

    /** Best-effort custom provider names (set via the rename dialog). */
    private fun customProviderNames(): Map<String, String> = try {
        getSharedPreferences("provider_names", MODE_PRIVATE).all
            .mapNotNull { (k, v) -> (v as? String)?.let { k to it } }
            .toMap()
    } catch (_: Exception) {
        emptyMap()
    }

    // --- Config persistence ---

    // Device Protected Storage: the service is directBootAware, so these run
    // in the Direct Boot phase where credential-encrypted prefs throw.
    private fun servicePrefs() =
        createDeviceProtectedStorageContext()
            .also { it.moveSharedPreferencesFrom(this, PREFS_NAME) }
            .getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

    private fun persistConfig(config: MqvpnConfig) {
        servicePrefs().edit().putString(KEY_CONFIG_JSON, config.toJson()).apply()
    }

    private fun restoreConfig(): MqvpnConfig? {
        val json = servicePrefs().getString(KEY_CONFIG_JSON, null) ?: return null
        return try {
            MqvpnConfig.fromJson(json)
        } catch (_: Exception) {
            null
        }
    }

    private fun clearPersistedConfig() {
        servicePrefs().edit().remove(KEY_CONFIG_JSON).apply()
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

    private fun baseNotification(): NotificationCompat.Builder {
        val disconnectPi = PendingIntent.getService(
            this, 1, disconnectIntent(this), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setSmallIcon(R.drawable.ic_vpn)
            .setOngoing(true)
            // Per-second speed refreshes must not re-alert (buzz/peek).
            .setOnlyAlertOnce(true)
            .addAction(0, "Disconnect", disconnectPi)
    }

    private fun buildNotification(text: String): Notification =
        baseNotification().setContentText(text).build()

    private fun updateNotification(text: String) {
        getSystemService<NotificationManager>()
            ?.notify(NOTIFICATION_ID, buildNotification(text))
    }

    /** Collapsed line = aggregate; expanded = one row per provider. */
    private fun updateRichNotification(collapsed: String, expanded: String) {
        val n = baseNotification()
            .setContentText(collapsed)
            .setStyle(NotificationCompat.BigTextStyle().bigText(expanded))
            .build()
        getSystemService<NotificationManager>()?.notify(NOTIFICATION_ID, n)
    }

    companion object {
        /** True between onCreate and onDestroy — BootReceiver's double-start guard. */
        @Volatile
        var isRunning: Boolean = false
            private set

        private const val TAG = "MqvpnService"
        private const val CHANNEL_ID = "mqvpn_vpn"
        private const val NOTIFICATION_ID = 1
        private const val PREFS_NAME = "mqvpn_service"
        private const val KEY_CONFIG_JSON = "config_json"
        private const val EXTRA_CONFIG_JSON = "mqvpn_config_json"

        private const val ACTION_DISCONNECT = "com.mqvpn.app.action.DISCONNECT"
        private const val ACTION_RESUME = "com.mqvpn.app.action.RESUME"
        private const val EXTRA_START_PAUSED_IF_TRUSTED = "mqvpn_start_paused_if_trusted"

        // Notification speed refresh cadence (also the rate-averaging window).
        private const val NOTIF_MIN_INTERVAL_SEC = 1.0

        /** Silence before a path is written to the trace as not answering. */
        private const val SILENT_REPORT_MS = 5_000L

        /**
         * Diagnostics live in device-protected storage: the service is
         * directBootAware and logs before the user has unlocked, where
         * credential-encrypted storage is not readable yet.
         */
        fun diagnosticsLog(context: Context): DiagnosticsLog =
            DiagnosticsLog(
                File(
                    context.createDeviceProtectedStorageContext().filesDir,
                    DiagnosticsLog.FILE_NAME,
                ),
            )

        /**
         * Start intent carrying a config — used by BootReceiver and the QS
         * tile. With [pausedIfTrusted] the service parks in paused mode when
         * the current Wi-Fi is trusted, instead of connecting.
         */
        fun startIntent(
            context: Context,
            config: MqvpnConfig,
            pausedIfTrusted: Boolean = false,
        ): Intent =
            Intent(context, MyVpnService::class.java)
                .putExtra(EXTRA_CONFIG_JSON, config.toJson())
                .putExtra(EXTRA_START_PAUSED_IF_TRUSTED, pausedIfTrusted)

        /** Stop intent — notification action and QS tile. */
        fun disconnectIntent(context: Context): Intent =
            Intent(context, MyVpnService::class.java).setAction(ACTION_DISCONNECT)

        /** Resume intent — overrides a trusted-Wi-Fi pause on demand. */
        fun resumeIntent(context: Context): Intent =
            Intent(context, MyVpnService::class.java).setAction(ACTION_RESUME)

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
            return s.takeIf {
                it.isNotEmpty() && it != WifiManager.UNKNOWN_SSID && it != "<unknown ssid>"
            }
        }
    }
}
