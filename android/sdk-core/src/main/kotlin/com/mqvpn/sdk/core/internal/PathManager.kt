// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 mp0rta and mqvpn contributors

package com.mqvpn.sdk.core.internal

import android.net.Network
import android.system.Os
import android.util.Log
import com.mqvpn.sdk.core.MqvpnTunnel
import com.mqvpn.sdk.core.model.PathInfo
import com.mqvpn.sdk.network.NetworkEvent
import com.mqvpn.sdk.network.NetworkMonitor
import com.mqvpn.sdk.network.PathBinder
import com.mqvpn.sdk.runtime.MqvpnExecutor

/**
 * Bridges [NetworkEvent]s from NetworkMonitor to libmqvpn path management.
 *
 * Execution context:
 * - handleEvent() runs on IO dispatcher (caller provides)
 * - Socket creation (blocking I/O) happens on calling thread
 * - tunnel.addPathFd/connect/removePath are serialized via executor.call
 */
internal class PathManager(
    private val executor: MqvpnExecutor,
    private val tunnel: MqvpnTunnel,
    private val udpReaderPool: UdpReaderPool,
    private val networkMonitor: NetworkMonitor,
    private val protector: (Int) -> Boolean,
    private val serverHost: String,
    private val serverPort: Int,
    private val bindUdp: (Network, String, Int, (Int) -> Boolean) -> Int =
        { network, host, port, prot ->
            PathBinder.bindAndDetachUdp(network, host, port, prot)
        },
) {
    private var connected = false
    private val pathHandles = mutableMapOf<Network, Long>()  // network → pathHandle
    private val pathFds = mutableMapOf<Long, Int>()           // pathHandle → fd

    /**
     * Handle a network event. Must be called from IO dispatcher.
     *
     * Available: [IO] bindSocket → [executor] addPathFd + connect → startReader
     * Lost: [executor] removePath → stopReader → close(fd)
     */
    suspend fun handleEvent(event: NetworkEvent) {
        when (event) {
            is NetworkEvent.Available -> handleAvailable(event)
            is NetworkEvent.Lost -> handleLost(event)
        }
    }

    private suspend fun handleAvailable(event: NetworkEvent.Available) {
        val network = event.path.network
        val name = event.path.name

        // Step 1: Create socket (blocking I/O, runs on IO thread)
        val fd = bindUdp(network, serverHost, serverPort, protector)
        if (fd < 0) {
            Log.e(TAG, "Failed to bind socket for $name, will retry on next event")
            networkMonitor.removeNetwork(network)
            return
        }

        // Step 2: Add path + connect via executor (thread-safe)
        val handle = executor.call {
            // onLost can fire on a binder thread while bind was running on IO.
            // If the network is no longer in NetworkMonitor's active set, abort
            // — adding a path bound to an already-dead Network would leak a slot
            // (Lost wouldn't fire again for this Network).
            if (!networkMonitor.activeNetworks.containsKey(network)) {
                Log.i(TAG, "Network $name lost during bind, discarding fd")
                return@call ABORT_LOST_DURING_BIND
            }
            val h = tunnel.addPathFd(fd, name)
            if (h < 0) {
                Log.e(TAG, "addPathFd failed for $name: $h")
                return@call h
            }
            pathHandles[network] = h
            pathFds[h] = fd

            if (!connected) {
                tunnel.setServerAddr(serverHost, serverPort)
                tunnel.connect()
                connected = true
            }
            h
        }

        if (handle < 0) {
            closeFdSafe(fd)
            return
        }

        // Step 3: Start UDP reader
        udpReaderPool.startReader(fd, handle, name, tunnel)
        Log.i(TAG, "Path added: $name (handle=$handle, fd=$fd)")
    }

    private suspend fun handleLost(event: NetworkEvent.Lost) {
        val network = event.path.network
        val name = event.path.name

        // Step 1: Remove path via executor (must happen before fd close)
        val (handle, fd) = executor.call {
            val h = pathHandles.remove(network) ?: return@call Pair(-1L, -1)
            val f = pathFds.remove(h) ?: return@call Pair(h, -1)
            tunnel.removePath(h)
            Pair(h, f)
        }

        if (handle < 0) return

        // Step 2: Stop reader (shutdown, NOT close)
        udpReaderPool.stopReader(handle)

        // Step 3: Close fd (last — after C and reader are done)
        if (fd >= 0) closeFdSafe(fd)
        Log.i(TAG, "Path removed: $name (handle=$handle)")
    }

    /** handle → when it was first seen dead, for the debounce below. */
    private val closedSince = mutableMapOf<Long, Long>()

    /** handle → (bytesTx, bytesRx, when rx last moved). */
    private val traffic = mutableMapOf<Long, Triple<Long, Long, Long>>()

    /**
     * Whether a path is beyond saving, by either of the two ways it happens.
     *
     * CLOSED is the tidy case. The other is a path that keeps its ACTIVE — or
     * DEGRADED, or PENDING — status while nothing at all comes back over it:
     * a field trace caught one going ACTIVE → DEGRADED → PENDING and sitting
     * there silent for three minutes with the network underneath perfectly
     * healthy. Checking the status alone missed it entirely.
     *
     * Silence is judged from the counters rather than from srtt, which freezes
     * at its last live measurement and so keeps insisting a dead link is fine.
     * bytes_tx rises on every successful send and bytes_rx only on a packet
     * actually received, so "sending, nothing coming back" is exactly what the
     * pair says. An idle tunnel moves neither and is left alone.
     */
    private fun isDead(info: PathInfo, nowMs: Long): Boolean {
        if (info.status == MQVPN_PATH_CLOSED) return true

        val prev = traffic[info.handle]
        val rxMoved = prev == null || info.bytesRx > prev.second
        val txMoved = prev != null && info.bytesTx > prev.first
        val lastRx = when {
            rxMoved -> nowMs
            else -> prev?.third ?: nowMs
        }
        traffic[info.handle] = Triple(info.bytesTx, info.bytesRx, lastRx)

        // Only sending counts as evidence. Without it the path is merely
        // unused, and tearing down an idle link would be pure churn.
        if (!txMoved || rxMoved) return false
        return nowMs - lastRx >= DEAD_SILENCE_MS
    }

    /**
     * Rebuilds paths that died over a network the OS still has.
     *
     * libmqvpn closes a path on its own once it stops answering, but nothing
     * tells Android: a NetworkCallback fires when the *network* changes, and
     * here the network is fine — it is the path over it that died. Nothing in
     * this class had a rule for that, and the consequences compound:
     *
     * - removePath was never called, so the C slot kept platform_attached=1
     *   and could not be reused. With MQVPN_MAX_PATHS slots, eight such deaths
     *   leave add_path_fd failing outright and no path can ever be added again.
     * - removeNetwork was never called either, so the network stayed in
     *   NetworkMonitor's active set and could never be reported as new. No
     *   further Available event was possible for it — ever.
     * - reactivate_path is not exposed through JNI, so the dead path could not
     *   be revived either.
     *
     * The link therefore kept working for the rest of the phone — status bar,
     * hotspot, everything — while the tunnel had a corpse over it and no way
     * to make a new one. Only a full manual reconnect cleared it.
     *
     * Removing and immediately re-adding is deliberate over reviving: it walks
     * the same two paths that already handle a network coming and going, so
     * there is no second code path to keep correct.
     */
    suspend fun rebuildDeadPaths(nowMs: Long = System.currentTimeMillis()) {
        val stale = executor.call {
            val byHandle = tunnel.getPaths().associateBy { it.handle }
            val out = mutableListOf<Network>()
            for ((network, handle) in pathHandles) {
                val info = byHandle[handle]
                if (info == null || !isDead(info, nowMs)) {
                    closedSince.remove(handle)
                    if (info == null) traffic.remove(handle)
                    continue
                }
                // Only act while the OS still has the network. If it went away
                // too, the ordinary Lost event is already doing this properly.
                if (!networkMonitor.activeNetworks.containsKey(network)) continue
                val since = closedSince.getOrPut(handle) { nowMs }
                // A path often closes moments before its network disappears;
                // rebinding into that gap wastes a socket and races the Lost
                // event. Waiting confirms the network really did outlive it.
                if (nowMs - since >= REBUILD_AFTER_MS) {
                    // Cleared here, on the executor thread that owns this map,
                    // because handleLost below drops the handle from
                    // pathHandles and the entry would otherwise never be
                    // visited again.
                    closedSince.remove(handle)
                    traffic.remove(handle)
                    out += network
                }
            }
            out
        }

        for (network in stale) {
            val path = networkMonitor.activeNetworks[network] ?: continue
            Log.i(TAG, "Path over ${path.name} died while the network is still up; rebuilding")
            handleLost(NetworkEvent.Lost(path))
            handleAvailable(NetworkEvent.Available(path))
        }
    }

    /** Close all remaining fds. Called from executor during cleanup. */
    fun closeAllFds() {
        for ((_, fd) in pathFds) {
            closeFdSafe(fd)
        }
        pathFds.clear()
        pathHandles.clear()
        closedSince.clear()
        traffic.clear()
        connected = false
    }

    private fun closeFdSafe(fd: Int) {
        try {
            val fdObj = java.io.FileDescriptor()
            val field = java.io.FileDescriptor::class.java.getDeclaredField("descriptor")
            field.isAccessible = true
            field.setInt(fdObj, fd)
            Os.close(fdObj)
        } catch (e: Exception) {
            Log.w(TAG, "close fd=$fd failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "PathManager"

        /** mqvpn_path_status_t CLOSED — retries exhausted, never revived. */
        private const val MQVPN_PATH_CLOSED = 4

        /**
         * How long a path must stay closed over a live network before it is
         * rebuilt. Long enough that a path closing just ahead of its network
         * disappearing is handled by the ordinary Lost event instead.
         */
        private const val REBUILD_AFTER_MS = 8_000L

        /**
         * Sending this long with nothing coming back means the path is gone,
         * whatever its status still claims. Well past any real stall — the
         * trace that motivated this showed 165 s and still climbing.
         */
        private const val DEAD_SILENCE_MS = 45_000L

        /**
         * Sentinel returned from the executor block in [handleAvailable] when
         * the network was lost during the IO bind step. Must not collide with
         * any negative error code returned by [MqvpnTunnel.addPathFd] (xquic /
         * JNI errors are small negatives in the 0..-32 range).
         */
        private const val ABORT_LOST_DURING_BIND = -1000L
    }
}
