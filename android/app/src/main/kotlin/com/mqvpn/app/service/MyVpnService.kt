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
import com.mqvpn.app.BuildConfig
import com.mqvpn.app.R
import com.mqvpn.app.data.SettingsRepository
import com.mqvpn.app.net.ProviderDirectory
import com.mqvpn.app.ui.SpeedTracker
import com.mqvpn.app.ui.formatBps
import com.mqvpn.app.ui.formatBytes
import com.mqvpn.app.ui.hasLocationPermission
import com.mqvpn.app.ui.isLocationEnabled
import com.mqvpn.sdk.core.MqvpnVpnService
import com.mqvpn.sdk.core.model.MqvpnConfig
import com.mqvpn.sdk.core.model.MqvpnError
import com.mqvpn.sdk.core.model.MqvpnState
import com.mqvpn.sdk.core.model.PathInfo
import com.mqvpn.sdk.core.model.ReorderStats
import com.mqvpn.sdk.core.model.TunnelInfo
import com.mqvpn.sdk.core.model.VpnStats
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class MyVpnService : MqvpnVpnService() {

    @Inject lateinit var settingsRepo: SettingsRepository

    /**
     * Resolves "wifi-13" to "Starlink" / "Kyivstar" for the notification.
     * The service owns its lifecycle rather than the ViewModel: names are
     * needed for as long as the tunnel runs, and the ViewModel only exists
     * while someone has the app open — which, on the road, is nobody.
     */
    @Inject lateinit var providers: ProviderDirectory

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val diag: DiagnosticsLog by lazy { diagnosticsLog(applicationContext) }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        providers.start()
        netTrace.start()
        // First line of every run: which build produced this trace. Reading a
        // log against the wrong version has already sent one diagnosis down
        // the wrong path.
        diag.log(
            "service created — mqvpn ${BuildConfig.VERSION_NAME} " +
                "(${BuildConfig.GIT_SHA}, build ${BuildConfig.VERSION_CODE})",
        )
    }

    /**
     * Android's view of the links, traced next to libmqvpn's. Started with the
     * service rather than with the tunnel: the interesting stretches are the
     * ones where the tunnel is down and only the OS has anything to say.
     */
    private val netTrace: NetworkTrace by lazy {
        NetworkTrace(applicationContext, ::pathLabel, { diag.log(it) }, ::onNetworkAppeared)
    }

    private var lastForcedRestartMs = 0L

    /**
     * Restarts the tunnel when a genuinely new transport shows up while it is
     * down, instead of waiting out libmqvpn's backoff.
     *
     * The backoff is right for "the server is unreachable" and wrong for
     * "the phone just got a different way out". The 08-05 trace shows the
     * difference costing minutes: five reconnects, every one of them over the
     * same cellular network the OS had not yet given up on, backoff climbing
     * to its 60 s ceiling — and at the moment a fresh network finally
     * appeared, the client was sitting in that minute doing nothing. To the
     * user that is a phone with working mobile data and no internet, because
     * the TUN still holds the default route.
     *
     * Only fires once the library's own quick retries have had their turn, and
     * not more than once per cooldown, so a flapping link cannot turn this
     * into a restart loop.
     */
    private fun onNetworkAppeared(key: String) {
        if (userRequestedStop || pausedOnTrustedWifi) return
        if (downSinceMs == 0L) return // tunnel is up; nothing to rescue
        val now = System.currentTimeMillis()
        val downFor = now - downSinceMs
        if (downFor < NEW_NETWORK_GRACE_MS) return
        if (now - lastForcedRestartMs < FORCED_RESTART_COOLDOWN_MS) return
        val config = lastConfig ?: restoreConfig() ?: return
        lastForcedRestartMs = now
        diag.log(
            "new network $key after ${downFor / 1000}s down — restarting the " +
                "tunnel now instead of waiting out the backoff",
        )
        stopTunnel("restarting for a new network")
        startTunnel(config)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            diag.log("disconnect requested by user")
            userRequestedStop = true
            setPaused(null)
            stopTunnel("notification Disconnect action")
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
                    clearStopRequest()
                    // Header for the run: without it a trace cannot be read
                    // back against the settings it was produced under. No
                    // server address or key — this log gets shared.
                    diag.log(
                        "starting: scheduler=${config.scheduler.name} " +
                            "reorder=${config.reorderEnabled} hybrid=${config.hybridEnabled} " +
                            "trustedSsids=${trusted.size} excludedApps=${config.excludedApps.size} " +
                            // Two independent gates, and either one closed
                            // means every Wi-Fi is called "Wi-Fi" and Trusted
                            // Wi-Fi has nothing to match. A trace has to say
                            // which, or the next reader repeats the guess.
                            "wifiNames=${wifiNameState()}",
                    )
                    startTunnel(config)
                }
                startTrustedWifiWatcher(trusted)
            }
        }
        return START_STICKY
    }

    /** A fresh connect supersedes any earlier stop request. */
    private fun clearStopRequest() {
        userRequestedStop = false
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
        val now = System.currentTimeMillis()
        val name = newState::class.simpleName
        // How long the previous state held is what turns the trace into a
        // timeline: "Connecting for 26s then Error" and "Connecting for 2s
        // then Connected" are the same two lines without it.
        val held = if (stateSinceMs == 0L) null else (now - stateSinceMs) / 1000
        diag.log(
            "state → $name" +
                if (held != null) " (was $lastStateName for ${held}s)" else "",
        )
        lastStateName = name
        stateSinceMs = now
        if (newState !is MqvpnState.Connected && downSinceMs == 0L) downSinceMs = now

        when (newState) {
            is MqvpnState.Connected -> {
                connectedForNotif = true
                lastNotifPollMs = 0L
                notifPrev.clear()
                notifSilentSince.clear()
                notifHistory.clear()
                // Tunnel-wide counters belong to the QUIC connection and
                // restart at zero with it, so a delta taken across a reconnect
                // is negative nonsense. Paths outlive the connection and are
                // handle-keyed, so their maps are deliberately NOT cleared —
                // clearing them made every surviving path log "appeared"
                // again after each reconnect.
                prevDgram = null
                // The cost of the outage, in the two numbers that matter: how
                // long the user had no tunnel, and how many tries it took.
                // A backoff stuck at its ceiling shows up here as a small
                // attempt count against a very large downtime.
                val i = newState.tunnelInfo
                val downFor = if (downSinceMs == 0L) 0 else (now - downSinceMs) / 1000
                diag.log(
                    "CONNECTED ${i.assignedIp}/${i.prefix} mtu=${i.mtu} v6=${i.hasV6} " +
                        "after ${downFor}s down and $reconnectAttempts reconnect attempt(s)",
                )
                downSinceMs = 0L
                reconnectAttempts = 0
                updateNotification("Connected: ${i.assignedIp}")
            }
            is MqvpnState.Reconnecting -> {
                connectedForNotif = false
                updateNotification("Reconnecting...")
            }
            is MqvpnState.Disconnected -> {
                connectedForNotif = false
                // Same race as the Error branch, through the other door: the
                // library emits Disconnected from cleanup() and may still be
                // about to schedule a reconnect. The 08-04 09:27:57 trace shows
                // exactly that — Error, then Disconnected, then a 27 s gap
                // before anything reconnected, because stopSelf() fired here.
                //
                // Stop only when the user asked to stop. Anything else is the
                // library's business, and it has a reconnect timer for it.
                when {
                    pausedOnTrustedWifi -> Unit // parked, watching for the network to change
                    userRequestedStop -> stopSelf()
                    else -> diag.log("disconnected without a user request — staying up")
                }
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

    /**
     * Names whatever tore the tunnel down.
     *
     * Disconnected can only come out of cleanup(), so it always means someone
     * asked for it — but the 08-05 trace has three of them at 15:28, 15:29 and
     * 15:30 with no caller identifiable anywhere, the last leading to three and
     * a half hours of nothing. Restarting on Disconnected would have papered
     * over that rather than explaining it, so the reason comes first.
     */
    override fun onTunnelStopping(reason: String) {
        diag.log("tunnel stopping: $reason")
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
        reconnectAttempts++
        val downFor = if (downSinceMs == 0L) 0 else (System.currentTimeMillis() - downSinceMs) / 1000
        diag.log(
            "reconnect #$reconnectAttempts scheduled in ${delaySec}s " +
                "(tunnel down ${downFor}s)",
        )
        updateNotification("Reconnecting in ${delaySec}s...")
    }

    override fun onDestroy() {
        diag.log("service destroyed")
        netTrace.stop()
        providers.stop()
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
    private var lastConfig: MqvpnConfig? = null

    /** True while the tunnel is parked because we're on a trusted network. */
    private var pausedOnTrustedWifi = false

    /**
     * Set only by an explicit Disconnect. Distinguishes "the user is done"
     * from "the transport dropped", which look identical by the time
     * Disconnected reaches [onVpnStateChanged] — and only the first should
     * take the service down.
     */
    private var userRequestedStop = false

    private suspend fun loadTrustedSsids(): List<String> = try {
        settingsRepo.settings.first().parsedTrustedSsids()
    } catch (_: Exception) {
        emptyList()
    }

    private suspend fun withMain(block: () -> Unit) {
        kotlinx.coroutines.withContext(Dispatchers.Main) { block() }
    }

    /**
     * JOINING a trusted Wi-Fi pauses the tunnel (the service stays alive,
     * watching); leaving it — to another Wi-Fi or to cellular — resumes.
     *
     * The network present at start used to be excluded, on the reasoning that
     * an explicit Connect should beat the list. In practice that is backwards:
     * home is exactly where you both press Connect and sit on the trusted
     * network, so the one case the feature exists for was the one it skipped.
     */
    private var trustedList: List<String> = emptyList()

    /** The Wi-Fi the phone is on, kept so the decision can be retaken later. */
    private var currentWifi: Pair<Network, NetworkCapabilities>? = null
    private var labelWatchJob: Job? = null

    /** Every way this check can end, so the trace can name the one it took. */
    private enum class TrustedVerdict { PARK, RESUME, NO_LIST, NO_NAME, NOT_TRUSTED, PARKED, ALWAYS_ON }

    /** Deduped so a check that runs on every capability update prints once. */
    private val trustedCheckLogged = HashSet<String>()

    /**
     * Decides whether this Wi-Fi should park the tunnel, or release it.
     *
     * Every outcome is written to the trace, including the ones that do
     * nothing. Two separate hypotheses about why the pause "sometimes" failed
     * — a name that arrived too late, then always-on VPN — were both wrong,
     * and each cost a build and a round of field testing, because the silent
     * paths looked identical to the check never running at all. The code knew
     * the answer the whole time and had no way to say it.
     *
     * Deliberately idempotent: [pausedOnTrustedWifi] flips before any work, so
     * re-running on every name update settles rather than oscillates.
     */
    private fun evaluateTrustedWifi(network: Network, caps: NetworkCapabilities) {
        val key = "wifi-${ProviderDirectory.networkId(network)}"
        val ssid = trustedSsidOf(network, caps)
        val trusted = trustedList
        val isTrusted = ssid != null && ssid in trusted
        // System Always-on VPN fights this feature: lockdown blackholes ALL
        // traffic the moment we stop, and plain always-on force-restarts us
        // in a loop. Refuse to park rather than break the phone.
        val alwaysOn = Build.VERSION.SDK_INT >= 29 && (isAlwaysOn || isLockdownEnabled)

        val verdict = when {
            trusted.isEmpty() -> TrustedVerdict.NO_LIST
            ssid == null -> TrustedVerdict.NO_NAME
            isTrusted && pausedOnTrustedWifi -> TrustedVerdict.PARKED
            isTrusted && alwaysOn -> TrustedVerdict.ALWAYS_ON
            isTrusted -> TrustedVerdict.PARK
            pausedOnTrustedWifi -> TrustedVerdict.RESUME
            else -> TrustedVerdict.NOT_TRUSTED
        }

        if (trustedCheckLogged.add("$key/$verdict")) {
            diag.log(
                "trusted check $key ssid=${ssid ?: "unknown"} " +
                    "trusted=${trusted.size} parked=$pausedOnTrustedWifi " +
                    "alwaysOn=$alwaysOn → $verdict",
            )
        }

        when (verdict) {
            TrustedVerdict.PARK -> {
                Log.i(TAG, "trusted Wi-Fi \"$ssid\" joined — pausing VPN")
                diag.log("paused on trusted Wi-Fi \"$ssid\"")
                setPaused(ssid)
                stopTunnel("parking on trusted Wi-Fi \"$ssid\"")
                updateNotification("Paused: trusted Wi-Fi \"$ssid\"")
            }

            TrustedVerdict.RESUME ->
                resumeFromTrustedPause("switched to Wi-Fi \"$ssid\"")

            TrustedVerdict.ALWAYS_ON -> updateNotification(
                "Trusted Wi-Fi detected, but system Always-on VPN blocks auto-pause",
            )

            else -> Unit
        }
    }

    private fun startTrustedWifiWatcher(trusted: List<String>) {
        stopTrustedWifiWatcher()
        if (trusted.isEmpty()) return
        val cm = getSystemService(ConnectivityManager::class.java) ?: return

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        trustedList = trusted
        val handler = Handler(mainLooper)
        val onWifiCaps: (Network, NetworkCapabilities) -> Unit = { network, caps ->
            currentWifi = network to caps
            handler.post { evaluateTrustedWifi(network, caps) }
        }
        val onWifiLost: (Network) -> Unit = { network ->
            handler.post {
                if (currentWifi?.first == network) currentWifi = null
                if (pausedOnTrustedWifi && currentWifiSsid(applicationContext) == null) {
                    resumeFromTrustedPause("Wi-Fi lost")
                }
            }
        }
        // The decision above is made in a network's first second, which is
        // exactly when its name is least likely to be known: the SSID arrives
        // through the location app-op, and the user is in the Wi-Fi settings —
        // so mqvpn is backgrounded — precisely when they switch networks. The
        // 08-05 trace shows wifi-394 logging "no SSID" at 22:13:14 and
        // resolving to RT-AX52-5G only at 22:13:59, with no further
        // capabilities callback in between and therefore no second chance.
        //
        // ProviderDirectory publishes names as it learns them, so the arrival
        // of a name is itself the event this was missing.
        labelWatchJob = serviceScope.launch {
            providers.labels.collect {
                val (network, caps) = currentWifi ?: return@collect
                withMain { evaluateTrustedWifi(network, caps) }
            }
        }
        // API 31+ redacts the SSID from WifiInfo unless the callback is
        // registered with FLAG_INCLUDE_LOCATION_INFO (plus location perm).
        val cb = if (Build.VERSION.SDK_INT >= 31) {
            object : ConnectivityManager.NetworkCallback(
                ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO
            ) {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                    onWifiCaps(network, caps)

                override fun onLost(network: Network) = onWifiLost(network)
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                    onWifiCaps(network, caps)

                override fun onLost(network: Network) = onWifiLost(network)
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
        labelWatchJob?.cancel()
        labelWatchJob = null
        currentWifi = null
        trustedList = emptyList()
        trustedCheckLogged.clear()
    }

    /**
     * The SSID for trusted-network matching, cache first.
     *
     * Both live reads go through the location app-op, which Android gates on
     * the app being in the foreground — and the moment this has to work is
     * precisely the moment it is not: the user is in the Wi-Fi settings
     * switching networks, so mqvpn is in the background and every live read
     * returns null. That is the whole of "sometimes it pauses and sometimes it
     * doesn't". ProviderDirectory already holds a name it resolved earlier and
     * refreshes whenever the app is on screen, so it answers when the live
     * reads cannot — the same trace shows it naming RT-AX52-5G in the very
     * second the watcher saw nothing.
     */
    private fun trustedSsidOf(network: Network, caps: NetworkCapabilities): String? {
        val key = "wifi-${ProviderDirectory.networkId(network)}"
        val cached = providers.labels.value[key]?.takeIf { it != "Wi-Fi" }
        return cached ?: ssidFromCaps(caps) ?: currentWifiSsid(applicationContext)
    }

    private fun ssidFromCaps(caps: NetworkCapabilities): String? {
        if (Build.VERSION.SDK_INT < 29) return null
        val info = caps.transportInfo as? WifiInfo ?: return null
        return normalizeSsid(info.ssid)
    }

    // --- Live throughput in the notification ---

    private var connectedForNotif = false
    private var lastNotifPollMs = 0L
    // Keyed by handle for the same reason the diagnostics maps are: a closed
    // path and its replacement can share an iface name.
    private val notifPrev = HashMap<Long, Pair<Long, Long>>()
    private val notifSilentSince = HashMap<Long, Long>()
    private val notifHistory = ArrayDeque<Double>()

    /**
     * Rebuilds the ongoing notification about once a second — which is also
     * the rate-averaging window. Only while actively connected; paused and
     * reconnecting keep their own text.
     *
     * Closed paths are dropped: they carry nothing, and showing a dead entry
     * beside its live replacement under the same provider name is worse than
     * saying nothing. The diagnostics log is where their fate is recorded.
     */
    override fun onPathsPolled(paths: List<PathInfo>) {
        diagnosePaths(paths)
        if (!connectedForNotif || pausedOnTrustedWifi || paths.isEmpty()) return
        val now = System.currentTimeMillis()
        val first = lastNotifPollMs == 0L
        val dt = (now - lastNotifPollMs) / 1000.0
        if (!first && dt < NOTIF_MIN_INTERVAL_SEC) return

        val active = paths.filter { it.status != SpeedTracker.STATUS_CLOSED }
        val overrides = customProviderNames()
        val autoLabels = providers.labels.value

        var aggDown = 0.0
        var aggUp = 0.0
        val rows = ArrayList<NotifPath>(active.size)

        for (p in active) {
            val prev = notifPrev.put(p.handle, p.bytesTx to p.bytesRx)
            if (first || prev == null || dt <= 0) continue

            val down = (p.bytesRx - prev.second).coerceAtLeast(0) * 8 / dt
            val up = (p.bytesTx - prev.first).coerceAtLeast(0) * 8 / dt
            aggDown += down
            aggUp += up

            // Same "sending into silence" test the dashboard uses: srtt cannot
            // fall on a dead link, so it must not be what marks one healthy.
            if (p.bytesRx > prev.second) {
                notifSilentSince.remove(p.handle)
            } else if (p.bytesTx > prev.first) {
                notifSilentSince.putIfAbsent(p.handle, now)
            }
            val silentSince = notifSilentSince[p.handle]
            val noReply = if (silentSince != null && now - silentSince >= SpeedTracker.NO_REPLY_WARN_MS) {
                now - silentSince
            } else {
                0L
            }

            val auto = autoLabels[p.iface] ?: SpeedTracker.fallbackLabel(p.iface)
            rows += NotifPath(
                label = overrides[auto.lowercase()] ?: auto,
                downBps = down, upBps = up, srttMs = p.srttMs,
                noReplyMs = noReply, status = p.status, share = 0f,
            )
        }
        notifPrev.keys.retainAll(paths.map { it.handle }.toSet())
        notifSilentSince.keys.retainAll(notifPrev.keys)
        lastNotifPollMs = now
        if (first || rows.isEmpty()) return // baseline tick: no rates yet

        notifHistory.addLast(aggDown)
        while (notifHistory.size > NOTIF_HISTORY) notifHistory.removeFirst()

        val withShare = if (aggDown > 0) {
            rows.map { it.copy(share = (it.downBps / aggDown).toFloat()) }
        } else {
            rows
        }
        val content = NotificationRender.render(
            withShare, aggDown, aggUp, notifHistory.toList(), lastConfig?.scheduler?.name ?: "",
        )
        updateRichNotification(content)
        logThroughput(withShare, aggDown, aggUp, now)
    }

    private var lastThroughputLogMs = 0L

    /**
     * Periodic throughput line — the one record that answers "is the bandwidth
     * actually adding up", which no amount of path-lifecycle logging can.
     *
     * Written on a fixed interval rather than per tick (this runs ~1 Hz and
     * would drown everything else), and only while at least one path is
     * carrying, so a parked phone does not fill the ring with zeroes.
     */
    private fun logThroughput(
        paths: List<NotifPath>,
        aggDown: Double,
        aggUp: Double,
        now: Long,
    ) {
        if (aggDown <= 0 && aggUp <= 0) return
        if (now - lastThroughputLogMs < THROUGHPUT_LOG_INTERVAL_MS) return
        lastThroughputLogMs = now

        val split = paths
            .sortedByDescending { it.downBps }
            .joinToString(" · ") { p ->
                "${p.label} ${(p.share * 100).toInt()}% (↓${formatBps(p.downBps)})"
            }
        diag.log(
            "throughput ↓${formatBps(aggDown)} ↑${formatBps(aggUp)} " +
                "over ${paths.size} path(s): $split",
        )
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
    private val diagBornAt = HashMap<Long, Long>()
    private var lastSnapshotMs = 0L
    private var lastStatsLogMs = 0L
    private var prevDgram: Triple<Long, Long, Long>? = null

    // Outage accounting. downSinceMs is the moment the tunnel stopped being
    // Connected; both are cleared the moment it is Connected again.
    private var stateSinceMs = 0L
    private var lastStateName: String? = null
    private var downSinceMs = 0L
    private var reconnectAttempts = 0

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
            // The handle is in every line: two live paths can share an iface
            // name (a replacement created before its predecessor is reaped),
            // and without it the trace reads as one path contradicting itself.
            if (prevStatus == null) {
                diagBornAt[p.handle] = now
                diag.log("path ${p.iface}#${p.handle} \"${pathLabel(p.iface)}\" appeared (status=${p.status})")
                // Permission held but the name still generic means Android
                // redacted it anyway — which it does to a backgrounded app.
                // Worth one line, because it is the difference between "ask
                // the user for the permission" and "the permission is not
                // enough", and guessing between those wastes a whole trip.
                if (p.iface.startsWith("wifi") && pathLabel(p.iface) == "Wi-Fi") {
                    diag.log("  ↳ no SSID (${wifiNameState()})")
                }
            } else if (prevStatus != p.status) {
                // A path reaching CLOSED is the end of its life — record what it
                // managed to carry, since from Android there is no way to
                // revive it (reactivate_path is not exposed through JNI) and
                // this is the last thing the trace will ever say about it.
                if (p.status == SpeedTracker.STATUS_CLOSED) {
                    val lived = diagBornAt[p.handle]?.let { (now - it) / 1000 } ?: -1
                    diag.log(
                        "path ${p.iface}#${p.handle} \"${pathLabel(p.iface)}\" CLOSED after " +
                            "${lived}s alive, carried ↓${formatBytes(p.bytesRx)} ↑${formatBytes(p.bytesTx)}",
                    )
                }
                diag.log(
                    "path ${p.iface}#${p.handle} \"${pathLabel(p.iface)}\" " +
                        "status $prevStatus → ${p.status}",
                )
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
                        diag.log(
                            "path ${p.iface}#${p.handle} \"${pathLabel(p.iface)}\" " +
                                "answering again after ${secs}s of silence",
                        )
                    }
                    diagSilentSince.remove(p.handle)
                }
                txMoved -> {
                    val since = diagSilentSince.getOrPut(p.handle) { now }
                    val silentMs = now - since
                    if (silentMs >= SILENT_REPORT_MS && diagReported.add(p.handle)) {
                        diag.log(
                            "path ${p.iface}#${p.handle} \"${pathLabel(p.iface)}\" SILENT: " +
                                "sending for ${silentMs / 1000}s with no reply " +
                                "(srtt still reports ${p.srttMs} ms, status=${p.status})",
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
            diagBornAt.remove(handle)
        }

        logPathSnapshot(paths, now)
    }

    /**
     * Full roll-call of every path on a fixed interval, transitions or not.
     *
     * Event-only logging answers "what changed" but never "what is the state
     * right now" — and a trace read hours later, after a stretch with no
     * events, cannot distinguish a healthy idle tunnel from a wedged one.
     */
    private fun logPathSnapshot(paths: List<PathInfo>, now: Long) {
        if (now - lastSnapshotMs < SNAPSHOT_INTERVAL_MS) return
        lastSnapshotMs = now
        if (paths.isEmpty()) {
            diag.log("snapshot: no paths at all")
            return
        }
        val rows = paths.joinToString(" | ") { p ->
            val silent = diagSilentSince[p.handle]?.let { " silent=${(now - it) / 1000}s" } ?: ""
            "${p.iface}#${p.handle} \"${pathLabel(p.iface)}\" st=${p.status} " +
                "srtt=${p.srttMs}ms ↓${formatBytes(p.bytesRx)} ↑${formatBytes(p.bytesTx)}$silent"
        }
        diag.log("snapshot: $rows")
    }

    /**
     * Tunnel-wide counters. Datagram loss is a property of the connection, not
     * of any path, so it cannot be inferred from the per-path rows above — and
     * a rising lost/sent ratio is the clearest early sign of a link going bad
     * while it still technically answers.
     */
    override fun onStatsPolled(stats: VpnStats, reorder: ReorderStats) {
        if (!connectedForNotif || pausedOnTrustedWifi) return
        val now = System.currentTimeMillis()
        if (now - lastStatsLogMs < STATS_LOG_INTERVAL_MS) return
        lastStatsLogMs = now

        val prev = prevDgram
        prevDgram = Triple(stats.dgramSent, stats.dgramRecv, stats.dgramLost)
        if (prev == null) return // first sample is a baseline, deltas need two

        val dSent = stats.dgramSent - prev.first
        val dRecv = stats.dgramRecv - prev.second
        val dLost = stats.dgramLost - prev.third
        // Any counter going backwards means the connection was replaced
        // between samples; re-baseline rather than print a negative delta.
        if (dSent < 0 || dRecv < 0 || dLost < 0) return
        val lossPct = if (dSent > 0) dLost * 100.0 / dSent else 0.0
        diag.log(
            "tunnel: dgram +${dSent} sent +${dRecv} recv +${dLost} lost " +
                "(${"%.1f".format(lossPct)}% this window) srtt=${stats.srttMs}ms · " +
                "reorder gaps=${reorder.gapCount} filled=${reorder.gapFilled} " +
                "timeout=${reorder.gapTimeout} bufP99=${reorder.bufferedP99Ms}ms",
        )
    }

    /**
     * Human name for a path key, for the diagnostics trace.
     *
     * Without this every Wi-Fi reads as "wifi-13" and a log covering both a
     * direct Starlink link and an OMR router's Wi-Fi is impossible to tell
     * apart afterwards — which matters, because a tunnel running on top of a
     * router that already bonds its own uplinks behaves nothing like a direct
     * link.
     */
    private fun pathLabel(iface: String): String {
        val auto = providers.labels.value[iface] ?: SpeedTracker.fallbackLabel(iface)
        return customProviderNames()[auto.lowercase()] ?: auto
    }

    /**
     * Why a Wi-Fi may be nameless, in one token.
     *
     * "on" means both gates are open and the SSID was still withheld — which
     * is what Android does to a backgrounded app holding only while-in-use
     * location, and it points at a different fix from the other two.
     */
    private fun wifiNameState(): String = when {
        !hasLocationPermission(this) -> "NO-PERMISSION"
        !isLocationEnabled(this) -> "LOCATION-OFF"
        else -> "on"
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
    /**
     * Collapsed line = aggregate plus whichever link needs attention;
     * expanded = a row per provider. Colorized so the lock screen answers
     * "is the link OK" before a word is read — allowed here because this is
     * a foreground service notification.
     */
    private fun updateRichNotification(content: NotifContent) {
        val tint = when (content.health) {
            Health.OK -> 0xFF2E7D32.toInt()
            Health.WARN -> 0xFFEF6C00.toInt()
            Health.BAD -> 0xFFC62828.toInt()
        }
        val n = baseNotification()
            .setContentText(content.collapsed)
            .setSubText(content.subText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.expanded))
            .setColorized(true)
            .setColor(tint)
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

        /** Sparkline width — ~30 s of history at one sample per second. */
        private const val NOTIF_HISTORY = 30

        /**
         * How often the throughput summary is written. 30 s keeps a full day of
         * driving inside the 64 KB ring while still showing how the split moves.
         */
        private const val THROUGHPUT_LOG_INTERVAL_MS = 30_000L

        /** Full path roll-call cadence — answers "what is the state now". */
        private const val SNAPSHOT_INTERVAL_MS = 60_000L

        /** Tunnel-wide counter cadence (datagram loss, reorder). */
        private const val STATS_LOG_INTERVAL_MS = 60_000L

        /** Silence before a path is written to the trace as not answering. */
        private const val SILENT_REPORT_MS = 5_000L

        /**
         * How long the tunnel must already be down before a new network is
         * treated as worth a restart. Below this the library's own 5 s and
         * 10 s retries are still running and are the better tool.
         */
        private const val NEW_NETWORK_GRACE_MS = 15_000L

        /** Floor between forced restarts, so a flapping link cannot loop us. */
        private const val FORCED_RESTART_COOLDOWN_MS = 30_000L

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
