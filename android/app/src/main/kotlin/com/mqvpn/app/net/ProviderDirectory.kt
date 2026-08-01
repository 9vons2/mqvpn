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
        val key = "$prefix-${network.networkHandle and 0xFFF}"
        val current = _labels.value
        if (current[key] != label) {
            _labels.value = current + (key to label)
        }
    }

    private fun onNetworkLost(network: Network) {
        // Key prefix is unknown at loss time; drop every entry with this
        // handle suffix (collisions across transports are harmless — the
        // entry re-appears on the next onCapabilitiesChanged).
        val suffix = "-${network.networkHandle and 0xFFF}"
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

    private fun wifiSsid(caps: NetworkCapabilities): String? {
        val raw = if (Build.VERSION.SDK_INT >= 29) {
            (caps.transportInfo as? WifiInfo)?.ssid
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(WifiManager::class.java)?.connectionInfo?.ssid
        }
        val ssid = raw?.removeSurrounding("\"")?.trim()
        return ssid?.takeIf { it.isNotEmpty() && it != UNKNOWN_SSID }
    }

    private fun loadCustomNames(): Map<String, String> =
        prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()

    companion object {
        private const val TAG = "ProviderDirectory"

        // WifiManager.UNKNOWN_SSID (constant added in API 30; same literal before)
        private const val UNKNOWN_SSID = "<unknown ssid>"
    }
}
