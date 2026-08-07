// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 mp0rta and mqvpn contributors

package com.mqvpn.app.service

import android.content.Context
import android.net.LinkProperties
import android.util.Log

/**
 * Remembers what each Wi-Fi looks like from the inside, so it can be
 * recognised later when Android will not say its name.
 *
 * The name is learnt opportunistically — any moment the SSID does resolve,
 * which is whenever the app is on screen with location on. From then on the
 * addressing alone is enough, and that keeps arriving in the background with
 * location switched off entirely.
 *
 * Stored in plain SharedPreferences rather than the encrypted config: a
 * gateway address is not a secret, and this has to be readable during Direct
 * Boot alongside the rest of the service's state.
 */
class TrustedNetworks(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("network_fingerprints", Context.MODE_PRIVATE)

    /**
     * Records what [ssid] looks like. Overwrites, because a router that gets
     * reconfigured should be re-learnt rather than accumulate stale entries.
     * Returns true when this is new or changed information, so the caller can
     * say so in the trace exactly once.
     */
    fun learn(ssid: String, lp: LinkProperties): Boolean {
        val fp = NetFingerprint.of(lp) ?: return false
        val serialized = fp.serialize()
        if (prefs.getString(ssid, null) == serialized) return false
        prefs.edit().putString(ssid, serialized).apply()
        return true
    }

    /** The SSID whose fingerprint this network matches, or null. */
    fun identify(lp: LinkProperties): String? {
        val fp = NetFingerprint.of(lp) ?: return null
        val matches = prefs.all.keys.filter { ssid ->
            val stored = prefs.getString(ssid, null)?.let { NetFingerprint.parse(it) }
            stored != null && fp.matches(stored)
        }
        return when (matches.size) {
            1 -> matches.first()
            0 -> null
            else -> {
                // Two networks that look identical from inside — most likely
                // two routers on the factory-default 192.168.1.1. Guessing
                // would park the tunnel on a network that needs it.
                Log.w(TAG, "fingerprint matches ${matches.size} networks; refusing to guess")
                null
            }
        }
    }

    /** Drops entries for SSIDs no longer on the trusted list. */
    fun retain(ssids: Collection<String>) {
        val stale = prefs.all.keys - ssids.toSet()
        if (stale.isEmpty()) return
        prefs.edit().apply { stale.forEach { remove(it) } }.apply()
    }

    companion object {
        private const val TAG = "TrustedNetworks"
    }
}
