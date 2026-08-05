// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 mp0rta and mqvpn contributors

package com.mqvpn.sdk.core

import android.content.Intent
import android.net.VpnService
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import com.mqvpn.sdk.core.internal.PathManager
import com.mqvpn.sdk.core.internal.TunnelCallbacks
import com.mqvpn.sdk.core.internal.UdpReaderPool
import com.mqvpn.sdk.core.model.MqvpnConfig
import com.mqvpn.sdk.core.model.MqvpnError
import com.mqvpn.sdk.core.model.MqvpnState
import com.mqvpn.sdk.core.model.PathInfo
import com.mqvpn.sdk.core.model.ReconnectInfo
import com.mqvpn.sdk.core.model.ReorderStats
import com.mqvpn.sdk.core.model.TunnelInfo
import com.mqvpn.sdk.core.model.VpnStats
import com.mqvpn.sdk.network.NetworkMonitor
import com.mqvpn.sdk.runtime.MqvpnPoller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress

/**
 * Abstract VPN service base class for mqvpn Android SDK.
 *
 * Subclasses implement [onCreateTun] and [onVpnStateChanged].
 * The SDK manages all internal components (executor, tunnel, I/O, paths).
 *
 * Thread safety: All JNI calls are serialized on the executor thread.
 * [onCreateTun] is called from the executor thread (NOT the UI thread).
 */
abstract class MqvpnVpnService : VpnService(), TunnelCallbacks {

    /** Binder for local (in-process) binding from MqvpnManager. */
    inner class LocalBinder : Binder() {
        fun getService(): MqvpnVpnService = this@MqvpnVpnService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    internal var manager: MqvpnManager? = null

    private lateinit var executor: MqvpnPoller
    private var tunnel: MqvpnTunnel? = null
    private var tunnelBridge: TunnelBridge? = null
    private var udpReaderPool: UdpReaderPool? = null
    private var pathManager: PathManager? = null
    private var networkMonitor: NetworkMonitor? = null
    private var currentConfig: MqvpnConfig? = null
    private var currentTunPfd: ParcelFileDescriptor? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // --- Lifecycle ---

    override fun onCreate() {
        super.onCreate()
        executor = MqvpnPoller(scope,
            tickFn = {
                val t = tunnel
                val result = t?.tick() ?: 0
                // Poll stats/paths and push to MqvpnManager on each tick
                if (t != null) {
                    val polledStats = t.getStats()
                    manager?.updateStats(polledStats)
                    val polledPaths = t.getPaths()
                    manager?.updatePaths(polledPaths)
                    onPathsPolled(polledPaths)
                    val polledReorder = t.getReorderStats()
                    manager?.updateReorderStats(polledReorder)
                    onStatsPolled(polledStats, polledReorder)
                }
                result
            },
            interestFn = {
                val i = tunnel?.getInterest()
                if (i != null) intArrayOf(i.nextTimerMs, if (i.tunReadable) 1 else 0, if (i.isIdle) 1 else 0)
                else intArrayOf(0, 0, 0)
            })
        executor.start()
    }

    override fun onDestroy() {
        runBlocking {
            withTimeoutOrNull(2000) {
                executor.call { cleanup() }
            }
        }
        scope.cancel()
        executor.stop()
        super.onDestroy()
    }

    override fun onRevoke() {
        runBlocking {
            withTimeoutOrNull(2000) {
                executor.call { cleanup() }
            }
        }
        scope.cancel()
        executor.stop()
        stopSelf()
    }

    // --- Public API ---

    /**
     * Start VPN tunnel. Called from app code.
     * connect() is deferred until first network path is available.
     */
    protected fun startTunnel(config: MqvpnConfig) {
        currentConfig = config
        executor.enqueue {
            val t = MqvpnTunnel.create(config, this)
            tunnel = t

            val pool = UdpReaderPool(executor)
            udpReaderPool = pool

            val monitor = NetworkMonitor(this)
            networkMonitor = monitor

            val pm = PathManager(
                executor, t, pool, monitor,
                protector = { fd -> protect(fd) },
                serverHost = config.serverAddress,
                serverPort = config.serverPort,
            )
            pathManager = pm

            monitor.start { event ->
                scope.launch(Dispatchers.IO) { pm.handleEvent(event) }
            }

            // A path can die without its network going anywhere, and Android
            // only reports the latter. Nothing else will ever notice, so this
            // has to be a poll rather than an event. See
            // PathManager.rebuildDeadPaths.
            scope.launch(Dispatchers.IO) {
                while (true) {
                    delay(REAP_INTERVAL_MS)
                    if (tunnel == null) break
                    try {
                        pm.rebuildDeadPaths()
                    } catch (e: Exception) {
                        Log.w(TAG, "path rebuild failed", e)
                    }
                }
            }

            emitState(MqvpnState.Connecting)
        }
    }

    /**
     * Stop VPN tunnel. Called by [MqvpnManager.disconnect], and by service
     * subclasses that tear the tunnel down on their own — a notification
     * Disconnect action, or parking on a trusted Wi-Fi. `internal` would
     * cover the manager but not those subclasses, which live in the app
     * module, so this stays public.
     * Do NOT call from onDestroy — cleanup runs automatically.
     */
    fun stopTunnel() {
        executor.enqueue { cleanup() }
    }

    // --- Internal cleanup (idempotent) ---

    private fun cleanup() {
        if (tunnel == null) return // already cleaned up
        networkMonitor?.stop()
        tunnelBridge?.stop()
        udpReaderPool?.stopAll()
        tunnel?.disconnect()
        tunnel?.tick() // send CONNECTION_CLOSE
        tunnel?.destroy()
        pathManager?.closeAllFds()
        currentTunPfd?.close()
        tunnel = null
        tunnelBridge = null
        udpReaderPool = null
        pathManager = null
        networkMonitor = null
        currentTunPfd = null
        emitState(MqvpnState.Disconnected)
    }

    // --- TunnelCallbacks implementation ---

    override fun onNativeTunnelConfigReady(
        assignedIp: ByteArray, prefix: Int,
        assignedIp6: ByteArray?, prefix6: Int,
        serverIp: ByteArray, serverPrefix: Int,
        mtu: Int, hasV6: Boolean,
    ) {
        val info = TunnelInfo(
            assignedIp = formatIp4(assignedIp),
            prefix = prefix,
            serverIp = formatIp4(serverIp),
            serverPrefix = serverPrefix,
            mtu = mtu,
            assignedIp6 = assignedIp6?.let { formatIp6(it) },
            prefix6 = prefix6,
            hasV6 = hasV6,
        )

        val tunPfd = try {
            onCreateTun(info, currentConfig!!)
        } catch (e: Exception) {
            Log.e(TAG, "onCreateTun failed", e)
            emitState(MqvpnState.Error(
                MqvpnError.TunCreationFailed(e.message ?: "VPN permission denied")))
            executor.enqueue { tunnel?.disconnect() }
            return
        }

        tunnel?.setTunActive(true, tunPfd.fd)

        // On reconnect: stop old TunnelBridge, close old TUN
        tunnelBridge?.stop()
        currentTunPfd?.close()
        currentTunPfd = tunPfd

        tunnelBridge = TunnelBridge(executor, tunnel!!)
        tunnelBridge?.startTunReader(tunPfd, mtu, scope)
        tunnelBridge?.startSender(scope)

        emitState(MqvpnState.Connected(info))
    }

    override fun onNativeTunnelClosed(errorCode: Int) {
        if (errorCode != 0) {
            emitState(MqvpnState.Error(MqvpnError.fromNativeCode(errorCode)))
        }
    }

    override fun onNativeStateChanged(oldState: Int, newState: Int) {
        // Map native states to MqvpnState
        when (newState) {
            1, 2 -> emitState(MqvpnState.Connecting)
            5 -> {} // RECONNECTING — handled by onNativeReconnectScheduled
            6 -> {} // CLOSED — handled by onNativeTunnelClosed or cleanup
        }
    }

    override fun onNativePathEvent(pathHandle: Long, newStatus: Int) {
        // Path changes are picked up by stats/paths polling in tickFn
    }

    override fun onNativeLog(level: Int, message: String) {
        onLog(level, message)
    }

    override fun onNativeReconnectScheduled(delaySec: Int) {
        emitState(MqvpnState.Reconnecting(ReconnectInfo(delaySec)))
        onReconnectScheduled(delaySec)
    }

    /**
     * Emit state to both Manager (StateFlow → UI) and app callback.
     */
    private fun emitState(newState: MqvpnState) {
        lastState = newState
        manager?.updateState(newState)
        onVpnStateChanged(newState)
    }

    /**
     * Last state this service emitted. A manager binding to an already-running
     * service (started at boot or from the QS tile) reads it to adopt the live
     * state instead of showing Disconnected.
     */
    @Volatile
    var lastState: MqvpnState = MqvpnState.Disconnected
        private set

    // --- Abstract methods (app implements) ---

    /**
     * Create TUN device using VpnService.Builder.
     *
     * Called from the executor thread (NOT the UI thread).
     * Called once per connection, and again on reconnect.
     *
     * @return ParcelFileDescriptor for the TUN device.
     * @throws Exception if TUN creation fails (triggers Error state).
     */
    abstract fun onCreateTun(info: TunnelInfo, config: MqvpnConfig): ParcelFileDescriptor

    /** State change notification. Update UI from here. */
    abstract fun onVpnStateChanged(newState: MqvpnState)

    // --- Optional callbacks ---

    open fun onLog(level: Int, message: String) {}
    open fun onReconnectScheduled(delaySec: Int) {}

    /** Per-tick snapshot of live paths — subclasses may surface throughput. */
    open fun onPathsPolled(paths: List<PathInfo>) {}

    /**
     * Per-tick tunnel-wide counters. Separate from [onPathsPolled] because
     * datagram loss and reorder behaviour are properties of the connection,
     * not of any one path, and diagnosing a flaky link needs both.
     */
    open fun onStatsPolled(stats: VpnStats, reorder: ReorderStats) {}

    // --- Helpers ---

    private fun formatIp4(bytes: ByteArray): String =
        InetAddress.getByAddress(bytes).hostAddress ?: "0.0.0.0"

    private fun formatIp6(bytes: ByteArray): String =
        InetAddress.getByAddress(bytes).hostAddress ?: "::"

    companion object {
        private const val TAG = "MqvpnVpnService"

        /**
         * How often to look for paths that died over a network that is still
         * there. Cheap — one getPaths() against an in-memory map — and the
         * failure it catches otherwise lasts until the user reconnects by hand.
         */
        private const val REAP_INTERVAL_MS = 5_000L
    }
}
