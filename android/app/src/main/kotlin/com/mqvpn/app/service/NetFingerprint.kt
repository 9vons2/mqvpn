// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 mp0rta and mqvpn contributors

package com.mqvpn.app.service

import android.net.LinkProperties
import java.net.Inet6Address

/**
 * A Wi-Fi network's own addressing, used to recognise it when its name is
 * unavailable.
 *
 * Android treats an SSID as location data and withholds it from an app that
 * is backgrounded or running with location switched off — which is exactly
 * when Trusted Wi-Fi needs it, since the user is in the Wi-Fi settings at the
 * time. A field trace with location off shows every network resolving
 * "no SSID (LOCATION-OFF)" while its addresses came through in full:
 *
 *     gw=192.168.100.1 dns=192.168.100.1 domains=lan
 *     gw=192.168.1.1   dns=192.168.200.1,8.8.8.8,192.168.1.1
 *
 * Two routers, plainly distinguishable, with no permission involved.
 */
data class NetFingerprint(
    val gateways: Set<String>,
    val subnets: Set<String>,
    val dns: Set<String>,
    /**
     * The phone's own IPv6 link-local address on this network.
     *
     * Android derives it from a MAC it randomises *per saved network* and
     * keeps, so it is stable across reconnections and unique per network —
     * the same trace shows fe80::2c1d:acff:feb0:6825 returning for one router
     * and fe80::88dc:caff:fe92:31ca for the other, every time. That makes it
     * the single strongest identifier available without a permission.
     */
    val linkLocal: String?,
) {
    /**
     * Whether this is the same network as [other].
     *
     * A false match is the expensive direction: it parks the tunnel on a
     * network that needs it. So every rule here is written to refuse rather
     * than guess.
     */
    fun matches(other: NetFingerprint): Boolean {
        // When both sides have one it settles the question outright, in both
        // directions: a per-network MAC that differs is positive evidence of
        // a different network, not merely absence of evidence.
        if (linkLocal != null && other.linkLocal != null) return linkLocal == other.linkLocal

        // Otherwise the addressing has to carry it — and gateway plus subnet
        // is not enough on its own. The 08-07 trace has two of this user's
        // networks, LEDE and netis_82EFBC, both handing out 192.168.1.0/24
        // behind 192.168.1.1; only their DNS tells them apart. Matching on
        // the first two alone would have parked the tunnel in the car.
        return gateways.isNotEmpty() &&
            gateways == other.gateways &&
            subnets.isNotEmpty() &&
            subnets == other.subnets &&
            dns == other.dns
    }

    /** Round-trips through [parse]; stored in SharedPreferences as one string. */
    fun serialize(): String = listOf(
        gateways.sorted().joinToString(","),
        subnets.sorted().joinToString(","),
        dns.sorted().joinToString(","),
        linkLocal.orEmpty(),
    ).joinToString("|")

    companion object {
        fun parse(raw: String): NetFingerprint? {
            val parts = raw.split("|")
            if (parts.size != 4) return null
            fun set(s: String) = s.split(",").filter { it.isNotBlank() }.toSet()
            return NetFingerprint(
                gateways = set(parts[0]),
                subnets = set(parts[1]),
                dns = set(parts[2]),
                linkLocal = parts[3].ifBlank { null },
            )
        }

        fun of(lp: LinkProperties): NetFingerprint? {
            val gateways = lp.routes
                .filter { it.isDefaultRoute }
                .mapNotNull { it.gateway?.hostAddress }
                .toSet()
            val subnets = lp.linkAddresses
                .filter { it.address !is Inet6Address }
                .mapNotNull { la -> la.address.hostAddress?.let { networkOf(it, la.prefixLength) } }
                .toSet()
            val linkLocal = lp.linkAddresses
                .map { it.address }
                .filterIsInstance<Inet6Address>()
                .firstOrNull { it.isLinkLocalAddress }
                ?.hostAddress
                // Some devices append a scope ("%wlan0"); the address alone
                // is the stable part.
                ?.substringBefore('%')
            val dns = lp.dnsServers.mapNotNull { it.hostAddress }.toSet()
            // Nothing identifying at all — better to admit it than to match
            // every nameless network against every other one.
            if (gateways.isEmpty() && linkLocal == null) return null
            return NetFingerprint(gateways, subnets, dns, linkLocal)
        }

        /** "192.168.1.42" + 24 → "192.168.1.0/24". IPv4 only. */
        internal fun networkOf(address: String, prefixLength: Int): String? {
            val octets = address.split(".").mapNotNull { it.toIntOrNull() }
            if (octets.size != 4 || prefixLength !in 0..32) return null
            var bits = octets.fold(0L) { acc, o -> (acc shl 8) or (o.toLong() and 0xFF) }
            val mask = if (prefixLength == 0) 0L else (-1L shl (32 - prefixLength)) and 0xFFFFFFFFL
            bits = bits and mask
            val masked = (0..3).map { (bits shr (24 - it * 8)) and 0xFF }
            return "${masked.joinToString(".")}/$prefixLength"
        }
    }
}
