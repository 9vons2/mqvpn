// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 mp0rta and mqvpn contributors

package com.mqvpn.app.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import kotlin.math.abs

/** What Android believes about one network, reduced to the parts worth tracing. */
data class NetSnapshot(
    val transport: String,
    /** Android ran its own connectivity probe and it came back OK. */
    val validated: Boolean,
    val metered: Boolean,
    val downKbps: Int,
    val upKbps: Int,
    /** dBm; [UNKNOWN_SIGNAL] when the OS does not expose it. */
    val signal: Int = UNKNOWN_SIGNAL,
) {
    companion object {
        const val UNKNOWN_SIGNAL = Int.MIN_VALUE
    }
}

/**
 * Traces Android's own opinion of every network, alongside the per-path lines
 * libmqvpn produces.
 *
 * The two views disagree, and the disagreement is the bug: on the road the OS
 * kept a Starlink Wi-Fi marked validated long after nothing came back over it.
 * A trace that only shows mqvpn paths cannot show that — it shows a silent
 * path and no reason. It also cannot show *which* Wi-Fi, so a stretch spent
 * behind an OpenMPTCProuter (a tunnel on top of another bonder) reads exactly
 * like a direct link.
 *
 * Only changes are written. [onCapabilitiesChanged] fires several times a
 * second on a moving vehicle, and a per-callback line would bury everything
 * else in the ring buffer.
 */
class NetworkTrace(
    context: Context,
    private val labelFor: (String) -> String,
    private val sink: (String) -> Unit,
) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val seen = HashMap<String, NetSnapshot>()
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var defaultCallback: ConnectivityManager.NetworkCallback? = null
    private var defaultKey: String? = null

    fun start() {
        if (cm == null || callback != null) return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(n: Network, c: NetworkCapabilities) =
                onCaps(n, c)

            override fun onLost(n: Network) = onLostNetwork(n)
        }
        val defCb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(n: Network, c: NetworkCapabilities) {
                val key = keyOf(n, c)
                if (key == defaultKey) return
                defaultKey = key
                // The default network is the one Android routes everything
                // else over — including its own reachability probes. When it
                // points at a link our paths call silent, the OS is wrong.
                sink("default network → $key \"${labelFor(key)}\"")
            }

            override fun onLost(n: Network) {
                defaultKey = null
                sink("default network lost")
            }
        }
        try {
            cm.registerNetworkCallback(request, cb)
            callback = cb
            cm.registerDefaultNetworkCallback(defCb)
            defaultCallback = defCb
        } catch (e: Exception) {
            Log.w(TAG, "network trace failed to start: ${e.message}")
        }
    }

    fun stop() {
        callback?.let { runCatching { cm?.unregisterNetworkCallback(it) } }
        defaultCallback?.let { runCatching { cm?.unregisterNetworkCallback(it) } }
        callback = null
        defaultCallback = null
        defaultKey = null
        seen.clear()
    }

    private fun onCaps(network: Network, caps: NetworkCapabilities) {
        val key = keyOf(network, caps)
        val now = snapshot(caps)
        val line = describeChange(key, labelFor(key), seen[key], now) ?: return
        seen[key] = now
        sink(line)
    }

    private fun onLostNetwork(network: Network) {
        val suffix = "-${network.networkHandle and 0xFFF}"
        val gone = seen.keys.filter { it.endsWith(suffix) }
        for (key in gone) {
            seen.remove(key)
            sink("net down: $key \"${labelFor(key)}\"")
        }
    }

    /** Same key libmqvpn paths carry, so the two traces line up by eye. */
    private fun keyOf(network: Network, caps: NetworkCapabilities): String {
        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
        return "$transport-${network.networkHandle and 0xFFF}"
    }

    private fun snapshot(caps: NetworkCapabilities) = NetSnapshot(
        transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        },
        validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
        downKbps = caps.linkDownstreamBandwidthKbps,
        upKbps = caps.linkUpstreamBandwidthKbps,
        signal = if (Build.VERSION.SDK_INT >= 29) {
            caps.signalStrength
        } else {
            NetSnapshot.UNKNOWN_SIGNAL
        },
    )

    companion object {
        private const val TAG = "NetworkTrace"

        /** Below this, a bandwidth estimate wobble is noise, not an event. */
        private const val BW_CHANGE_RATIO = 0.25

        /** dBm. Roughly one bar on a phone. */
        private const val SIGNAL_CHANGE_DB = 8

        /**
         * The line to write for a capability update, or null when nothing
         * worth reading changed.
         *
         * Pure so the thresholds can be tested without a device: getting them
         * wrong either floods the 64 KB ring or hides the transition that
         * explains an outage.
         */
        fun describeChange(
            key: String,
            label: String,
            prev: NetSnapshot?,
            now: NetSnapshot,
        ): String? {
            if (prev == null) {
                return "net up: $key \"$label\" ${state(now)} ${rates(now)}"
            }
            val reasons = mutableListOf<String>()
            if (prev.validated != now.validated) {
                // Android's verdict flipping is the single most telling event
                // here: it is what the OS acts on when it tears a transport
                // down under us.
                reasons += if (now.validated) "validated" else "LOST VALIDATION"
            }
            if (prev.metered != now.metered) {
                reasons += if (now.metered) "now metered" else "no longer metered"
            }
            if (changedEnough(prev.downKbps, now.downKbps) ||
                changedEnough(prev.upKbps, now.upKbps)
            ) {
                reasons += rates(now)
            }
            if (prev.signal != NetSnapshot.UNKNOWN_SIGNAL &&
                now.signal != NetSnapshot.UNKNOWN_SIGNAL &&
                abs(prev.signal - now.signal) >= SIGNAL_CHANGE_DB
            ) {
                reasons += "signal ${prev.signal}→${now.signal} dBm"
            }
            if (reasons.isEmpty()) return null
            return "net $key \"$label\": ${reasons.joinToString(", ")}"
        }

        private fun changedEnough(prev: Int, now: Int): Boolean {
            if (prev == now) return false
            if (prev <= 0 || now <= 0) return true
            val base = maxOf(prev, now).toDouble()
            return abs(now - prev) / base >= BW_CHANGE_RATIO
        }

        private fun state(s: NetSnapshot): String {
            val v = if (s.validated) "validated" else "unvalidated"
            val m = if (s.metered) "metered" else "unmetered"
            return "$v/$m"
        }

        private fun rates(s: NetSnapshot): String =
            "est ↓${s.downKbps}kbps ↑${s.upKbps}kbps"
    }
}
