// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 mp0rta and mqvpn contributors

package com.mqvpn.app

import com.mqvpn.app.service.NetFingerprint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetFingerprintTest {

    /** The OMR router, as the 08-07 trace recorded it. */
    private val omr = NetFingerprint(
        gateways = setOf("192.168.100.1"),
        subnets = setOf("192.168.100.0/24"),
        dns = setOf("192.168.100.1"),
        linkLocal = "fe80::2c1d:acff:feb0:6825",
    )

    /** The other router in the same house, same trace. */
    private val netis = NetFingerprint(
        gateways = setOf("192.168.1.1"),
        subnets = setOf("192.168.1.0/24"),
        dns = setOf("192.168.200.1", "8.8.8.8", "192.168.1.1"),
        linkLocal = "fe80::88dc:caff:fe92:31ca",
    )

    @Test
    fun `a network matches itself`() {
        assertTrue(omr.matches(omr))
    }

    @Test
    fun `the two routers in the trace never match each other`() {
        assertFalse(omr.matches(netis))
        assertFalse(netis.matches(omr))
    }

    /**
     * The address the phone gets from DHCP changes; the network does not.
     * Matching on the subnet rather than the host address is what makes the
     * fingerprint survive a lease renewal.
     */
    @Test
    fun `a different DHCP lease on the same network still matches`() {
        assertTrue(omr.matches(omr.copy(dns = setOf("192.168.100.1", "1.1.1.1"))))
    }

    /**
     * Android randomises the MAC per saved network and keeps it, so the
     * link-local address alone identifies the network — which is what carries
     * recognition when a router hands out a different subnet than last time.
     */
    @Test
    fun `link-local alone is enough`() {
        val moved = omr.copy(
            gateways = setOf("10.0.0.1"),
            subnets = setOf("10.0.0.0/24"),
        )
        assertTrue(omr.matches(moved))
    }

    /**
     * Half the routers ever sold are 192.168.1.1. Matching on that alone
     * would park the tunnel on a network that needs it.
     */
    @Test
    fun `a shared gateway on a different subnet is not a match`() {
        val a = NetFingerprint(setOf("192.168.1.1"), setOf("192.168.1.0/24"), emptySet(), null)
        val b = NetFingerprint(setOf("192.168.1.1"), setOf("192.168.1.0/25"), emptySet(), null)
        assertFalse(a.matches(b))
    }

    /** Nothing to go on must not become "matches anything". */
    @Test
    fun `two empty fingerprints do not match`() {
        val empty = NetFingerprint(emptySet(), emptySet(), emptySet(), null)
        assertFalse(empty.matches(empty))
    }

    /**
     * The collision this rule exists for. Both of these are real: the Starlink
     * router in the car and the plain router in the house hand out the same
     * subnet behind the same gateway, and only their DNS differs. Treating
     * them as one network would park the tunnel on the road.
     */
    @Test
    fun `same subnet and gateway but different DNS is not a match`() {
        val lede = NetFingerprint(
            gateways = setOf("192.168.1.1"),
            subnets = setOf("192.168.1.0/24"),
            dns = setOf("192.168.1.1"),
            linkLocal = null,
        )
        assertFalse(netis.matches(lede))
        assertFalse(lede.matches(netis))
    }

    /**
     * Android keeps a MAC per saved network, so two different link-locals
     * are evidence of two different networks — not merely a failure to
     * confirm one. The addressing must not be able to override that.
     */
    @Test
    fun `two different link-locals never match, whatever the addressing says`() {
        val twin = omr.copy(linkLocal = "fe80::dead:beef:dead:beef")
        assertFalse(omr.matches(twin))
    }

    @Test
    fun `serialize round-trips`() {
        assertEquals(omr, NetFingerprint.parse(omr.serialize()))
        assertEquals(netis, NetFingerprint.parse(netis.serialize()))
    }

    @Test
    fun `a fingerprint with no link-local round-trips too`() {
        val fp = omr.copy(linkLocal = null)
        assertEquals(fp, NetFingerprint.parse(fp.serialize()))
    }

    @Test
    fun `garbage does not parse`() {
        assertNull(NetFingerprint.parse("nonsense"))
    }

    // -- subnet arithmetic ----------------------------------------------------

    @Test
    fun `host address is masked down to its network`() {
        assertEquals("192.168.1.0/24", NetFingerprint.networkOf("192.168.1.42", 24))
        assertEquals("192.168.100.0/24", NetFingerprint.networkOf("192.168.100.220", 24))
        assertEquals("10.0.0.0/8", NetFingerprint.networkOf("10.1.2.3", 8))
        assertEquals("192.168.1.64/26", NetFingerprint.networkOf("192.168.1.100", 26))
    }

    @Test
    fun `a full-length prefix keeps the whole address`() {
        assertEquals("192.168.1.42/32", NetFingerprint.networkOf("192.168.1.42", 32))
    }

    @Test
    fun `malformed input yields nothing rather than a wrong network`() {
        assertNull(NetFingerprint.networkOf("not-an-address", 24))
        assertNull(NetFingerprint.networkOf("192.168.1.42", 33))
    }
}
