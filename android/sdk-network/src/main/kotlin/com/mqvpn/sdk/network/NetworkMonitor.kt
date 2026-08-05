// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 mp0rta and mqvpn contributors

package com.mqvpn.sdk.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import androidx.core.content.getSystemService
import java.util.concurrent.ConcurrentHashMap

/**
 * Monitors WiFi / Cellular / Ethernet availability via ConnectivityManager,
 * and — crucially for a bonding VPN — asks the system to keep more than one
 * of them up at the same time.
 *
 * Uses NET_CAPABILITY_VALIDATED to filter out captive portals and
 * unvalidated networks that would cause packet loss if used as VPN paths.
 */
class NetworkMonitor(private val context: Context) {

    private val cm = context.getSystemService<ConnectivityManager>()!!

    private val _activeNetworks = ConcurrentHashMap<Network, NetworkPath>()
    val activeNetworks: Map<Network, NetworkPath> get() = _activeNetworks

    private var callback: ConnectivityManager.NetworkCallback? = null

    /**
     * Held only to keep their transports alive; path bookkeeping stays with
     * [callback]. One per transport, because a request naming several
     * transports is satisfied by any one of them.
     */
    private val keepAlive = mutableListOf<ConnectivityManager.NetworkCallback>()

    fun start(listener: (NetworkEvent) -> Unit) {
        // registerNetworkCallback only *observes*. Android keeps a single
        // default network and tears cellular data down once Wi-Fi validates,
        // so a passive watcher sees one path at a time and there is nothing to
        // aggregate — no scheduler can bond a link that the OS has switched
        // off. requestNetwork is what asks for a transport to be brought up
        // and held alongside the default, which is the whole premise here.
        keepTransportUp(NetworkCapabilities.TRANSPORT_CELLULAR)
        keepTransportUp(NetworkCapabilities.TRANSPORT_WIFI)

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                /* capabilities may not be available yet; wait for onCapabilitiesChanged */
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) {
                val type = classifyTransport(capabilities)
                val name = networkName(network, type)
                val metered = !capabilities.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_NOT_METERED,
                )
                val path = NetworkPath(network, type, name, metered)
                val isNew = _activeNetworks.put(network, path) == null
                if (isNew) {
                    Log.d(TAG, "Available: $path")
                    listener(NetworkEvent.Available(path))
                }
            }

            override fun onLost(network: Network) {
                val path = _activeNetworks.remove(network) ?: return
                Log.d(TAG, "Lost: $path")
                listener(NetworkEvent.Lost(path))
            }
        }

        callback = cb
        cm.registerNetworkCallback(request, cb)
    }

    /** Remove a network so the next onCapabilitiesChanged treats it as new. */
    fun removeNetwork(network: Network) {
        _activeNetworks.remove(network)
    }

    /**
     * Asks the system to bring up [transport] and hold it, in addition to
     * whatever the default network is. The callback is intentionally empty:
     * holding the request open is the entire effect, and paths are still
     * discovered through the observing callback so a network is never counted
     * twice.
     *
     * Costs real battery and, on cellular, real data — which is the bargain a
     * bonding VPN makes on purpose. Released in [stop] with the tunnel.
     */
    private fun keepTransportUp(transport: Int) {
        val request = NetworkRequest.Builder()
            .addTransportType(transport)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {}
        try {
            cm.requestNetwork(request, cb)
            keepAlive += cb
        } catch (e: SecurityException) {
            // CHANGE_NETWORK_STATE missing: fall back to observing only, which
            // still works — with one path at a time.
            Log.w(TAG, "cannot hold transport $transport up: ${e.message}")
        } catch (e: RuntimeException) {
            // Too many outstanding requests, or a transport this device lacks.
            Log.w(TAG, "requestNetwork($transport) rejected: ${e.message}")
        }
    }

    fun stop() {
        for (cb in keepAlive) {
            try { cm.unregisterNetworkCallback(cb) } catch (_: IllegalArgumentException) {}
        }
        keepAlive.clear()
        callback?.let { cm.unregisterNetworkCallback(it) }
        callback = null
        _activeNetworks.clear()
    }

    companion object {
        private const val TAG = "NetworkMonitor"

        internal fun classifyTransport(caps: NetworkCapabilities): PathType = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> PathType.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> PathType.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> PathType.ETHERNET
            else -> PathType.OTHER
        }

        /**
         * Stable per-network path name.
         *
         * The identity lives in the HIGH half of the handle: Android builds
         * it as `(netId << 32) | 0xcafed00d`. Masking the low 12 bits
         * therefore returned `0xd00d and 0xFFF` — a constant 13 — for every
         * network on every device, so all Wi-Fi networks (and all cellular
         * ones) collapsed onto the same name and nothing could tell one
         * incarnation of a link from the next. A field trace with five
         * successive Wi-Fi networks named every one of them "wifi-13".
         *
         * Taken modulo 10000 so the longest name ("cellular-9999", 13 chars)
         * still fits libmqvpn's `char iface[16]`.
         */
        internal fun networkName(network: Network, type: PathType): String =
            "${type.name.lowercase()}-${networkIdOf(network.networkHandle)}"

        /** Split out from [networkName] so the arithmetic can be pinned by a test. */
        internal fun networkIdOf(handle: Long): Long = (handle ushr 32) % 10000
    }
}
