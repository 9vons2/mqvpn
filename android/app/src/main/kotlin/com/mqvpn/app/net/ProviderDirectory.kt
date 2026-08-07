// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 mp0rta and mqvpn contributors

package com.mqvpn.app.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.TelephonyNetworkSpecifier
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves human-readable provider names for VPN paths.
 *
 * libmqvpn identifies a path by the name the SDK registered it under —
 * "{transport}-{networkHandle & 0xFFF}" (see sdk-network NetworkMonitor).
 * This class watches the same networks through its own callback, derives
 * the identical key, and maps it to a friendly label:
 * - cellular → carrier name from TelephonyManager ("Kyivstar", "Vodafone UA")
 * - wifi     → SSID when the OS exposes it (needs location permission on
 *              API 27+), otherwise "Wi-Fi"
 * - ethernet → "Ethernet"
 *
 * Users can override any auto label with a custom name (e.g. "Starlink");
 * overrides persist in SharedPreferences keyed by the auto label, so a
 * rename survives reconnects and network churn.
 */
@Singleton
class ProviderDirectory @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val prefs = context.getSharedPreferences("provider_names", Context.MODE_PRIVATE)

    private val _labels = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Path key ("wifi-291") → auto-resolved label ("HomeNet" / "Kyivstar"). */
    val labels: StateFlow<Map<String, String>> = _labels.asStateFlow()

    private val _customNames = MutableStateFlow(loadCustomNames())

    /** Auto label (lowercased) → user-chosen name. */
    val customNames: StateFlow<Map<String, String>> = _customNames.asStateFlow()

    private var callback: ConnectivityManager.NetworkCallback? = null

    fun start() {
        if (callback != null || cm == null) return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = newCallback()
        callback = cb
        try {
            cm.registerNetworkCallback(request, cb)
        } catch (e: Exception) {
            Log.w(TAG, "registerNetworkCallback failed: ${e.message}")
            callback = null
        }
    }

    /**
     * Re-resolves the name of every live network.
     *
     * A name is otherwise decided once, on the first [onCapabilitiesChanged]
     * for that network, and can never change afterwards — and two things
     * conspire to make that first answer the wrong one. The callback is
     * registered when the VPN service starts, which may be long before the
     * user grants the location permission; and Android gates location behind
     * an app-op that returns "ignored" for a backgrounded app holding only
     * while-in-use access, so a Wi-Fi that appears while the phone is in a
     * pocket has its SSID redacted. Either way the network is recorded as
     * plain "Wi-Fi" and stays that way for its whole life.
     *
     * Re-registering makes the system re-deliver capabilities for every
     * matching network, evaluated against the permissions in force now.
     * Labels are deliberately not cleared first: they are replaced as the
     * fresh callbacks land, so the UI never flickers back to the fallback.
     */
    fun refresh() {
        val cb = callback ?: return
        try {
            cm?.unregisterNetworkCallback(cb)
        } catch (_: Exception) {
        }
        callback = null
        start()
    }

    fun stop() {
        callback?.let {
            try {
                cm?.unregisterNetworkCallback(it)
            } catch (_: Exception) {
            }
        }
        callback = null
        _labels.value = emptyMap()
    }

    /** Set (or clear, with null) the custom name for an auto label. */
    fun rename(autoLabel: String, customName: String?) {
        val k = autoLabel.lowercase()
        val editor = prefs.edit()
        if (customName.isNullOrBlank()) editor.remove(k) else editor.putString(k, customName.trim())
        editor.apply()
        _customNames.value = loadCustomNames()
    }

    // FLAG_INCLUDE_LOCATION_INFO is required on API 31+ for the callback's
    // NetworkCapabilities to carry the WifiInfo SSID (still gated on the
    // app holding location permission — without it we fall back to "Wi-Fi").
    private fun newCallback(): ConnectivityManager.NetworkCallback =
        if (Build.VERSION.SDK_INT >= 31) {
            object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                override fun onCapabilitiesChanged(n: Network, c: NetworkCapabilities) = onCaps(n, c)
                override fun onLost(n: Network) = onNetworkLost(n)
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(n: Network, c: NetworkCapabilities) = onCaps(n, c)
                override fun onLost(n: Network) = onNetworkLost(n)
            }
        }

    private fun onCaps(network: Network, caps: NetworkCapabilities) {
        val (prefix, label) = resolve(caps)
        val key = "$prefix-${networkId(network)}"
        val current = _labels.value
        if (current[key] != label) {
            _labels.value = current + (key to label)
        }
    }

    private fun onNetworkLost(network: Network) {
        // Key prefix is unknown at loss time; drop every entry with this
        // network's suffix. Safe because the suffix is a netId, which is
        // unique across transports — with the old `handle and 0xFFF` it was
        // the constant 13 for every network, so losing Wi-Fi silently
        // dropped the cellular label too.
        val suffix = "-${networkId(network)}"
        _labels.value = _labels.value.filterKeys { !it.endsWith(suffix) }
    }

    private fun resolve(caps: NetworkCapabilities): Pair<String, String> = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ->
            "wifi" to (wifiSsid(caps) ?: "Wi-Fi")
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
            "cellular" to (carrierName(caps) ?: "Mobile")
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ->
            "ethernet" to "Ethernet"
        else -> "other" to "Other"
    }

    private fun carrierName(caps: NetworkCapabilities): String? {
        val tm = context.getSystemService(TelephonyManager::class.java) ?: return null
        val scoped = if (Build.VERSION.SDK_INT >= 30) {
            (caps.networkSpecifier as? TelephonyNetworkSpecifier)
                ?.let { tm.createForSubscriptionId(it.subscriptionId) }
        } else {
            null
        }
        return try {
            (scoped ?: tm).networkOperatorName?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "carrierName failed: ${e.message}")
            null
        }
    }

    /**
     * The SSID, from whichever source will actually give it.
     *
     * transportInfo alone is not enough, and the trace proves it: with the
     * location permission granted and location switched on, a Wi-Fi path still
     * came up nameless, while at the same moment — same process, same seconds
     * — the trusted-Wi-Fi watcher read "RT-AX52-5G" and parked the tunnel. The
     * difference between the two was only that the watcher fell back to
     * WifiManager and this did not.
     *
     * WifiManager reports the one Wi-Fi the phone is associated with, so it
     * cannot distinguish between two simultaneously tracked Wi-Fi networks.
     * A phone holds a single association at a time, and the alternative here
     * is no name at all.
     */
    private fun wifiSsid(caps: NetworkCapabilities): String? =
        fromTransportInfo(caps) ?: fromWifiManager()

    private fun fromTransportInfo(caps: NetworkCapabilities): String? {
        if (Build.VERSION.SDK_INT < 29) return null
        return normalize((caps.transportInfo as? WifiInfo)?.ssid)
    }

    private fun fromWifiManager(): String? = try {
        @Suppress("DEPRECATION")
        normalize(context.getSystemService(WifiManager::class.java)?.connectionInfo?.ssid)
    } catch (e: Exception) {
        Log.w(TAG, "wifi ssid read failed: ${e.message}")
        null
    }

    private fun normalize(raw: String?): String? {
        val ssid = raw?.removeSurrounding("\"")?.trim()
        return ssid?.takeIf { it.isNotEmpty() && it != UNKNOWN_SSID }
    }

    private fun loadCustomNames(): Map<String, String> =
        prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()

    companion object {
        private const val TAG = "ProviderDirectory"

        /**
         * The identifying half of a [Network] handle, which Android builds as
         * `(netId shl 32) or 0xcafed00d`. Must stay byte-identical to
         * sdk-network's `NetworkMonitor.networkName`, since these keys are
         * matched against the path names libmqvpn reports — sdk-network is an
         * `implementation` dependency of sdk-core, so it cannot be shared.
         */
        internal fun networkId(network: Network): Long =
            (network.networkHandle ushr 32) % 10000

        // WifiManager.UNKNOWN_SSID (constant added in API 30; same literal before)
        private const val UNKNOWN_SSID = "<unknown ssid>"
    }
}
