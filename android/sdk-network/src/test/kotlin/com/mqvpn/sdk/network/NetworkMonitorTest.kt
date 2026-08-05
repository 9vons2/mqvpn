// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 mp0rta and mqvpn contributors

package com.mqvpn.sdk.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowConnectivityManager
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities

@RunWith(RobolectricTestRunner::class)
class NetworkMonitorTest {

    @Test
    fun `classifyTransport returns WIFI for wifi transport`() {
        val caps = ShadowNetworkCapabilities.newInstance()
        Shadows.shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        assertEquals(PathType.WIFI, NetworkMonitor.classifyTransport(caps))
    }

    @Test
    fun `classifyTransport returns CELLULAR for cellular transport`() {
        val caps = ShadowNetworkCapabilities.newInstance()
        Shadows.shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
        assertEquals(PathType.CELLULAR, NetworkMonitor.classifyTransport(caps))
    }

    @Test
    fun `classifyTransport returns ETHERNET for ethernet transport`() {
        val caps = ShadowNetworkCapabilities.newInstance()
        Shadows.shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
        assertEquals(PathType.ETHERNET, NetworkMonitor.classifyTransport(caps))
    }

    @Test
    fun `classifyTransport returns OTHER for unknown transport`() {
        val caps = ShadowNetworkCapabilities.newInstance()
        Shadows.shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_BLUETOOTH)
        assertEquals(PathType.OTHER, NetworkMonitor.classifyTransport(caps))
    }

    @Test
    fun `networkName includes type and network id`() {
        val network = ShadowNetwork.newInstance(42)
        assertEquals("wifi-42", NetworkMonitor.networkName(network, PathType.WIFI))
    }

    /**
     * The name has to identify the network, not just its transport: two
     * successive Wi-Fi networks are two different links, and libmqvpn keys
     * paths by this string.
     */
    @Test
    fun `two networks never share a name`() {
        val a = NetworkMonitor.networkName(ShadowNetwork.newInstance(105), PathType.WIFI)
        val b = NetworkMonitor.networkName(ShadowNetwork.newInstance(106), PathType.WIFI)
        assertEquals("wifi-105", a)
        assertEquals("wifi-106", b)
    }

    /**
     * Regression: the id used to be `handle and 0xFFF`. Android builds the
     * handle as `(netId shl 32) or 0xcafed00d`, so that mask returned
     * `0xd00d and 0xFFF` — a constant 13 — for every network ever seen, and a
     * whole field trace named five different Wi-Fi networks "wifi-13".
     */
    @Test
    fun `the identity is not in the low bits of the handle`() {
        for (netId in listOf(1L, 42L, 105L, 999L)) {
            val handle = (netId shl 32) or 0xcafed00dL
            assertEquals(13L, handle and 0xFFFL) // what the old code read
            assertEquals(netId, NetworkMonitor.networkIdOf(handle))
        }
    }

    /** Names must fit libmqvpn's `char iface[16]`. */
    @Test
    fun `the longest name still fits the native iface field`() {
        val handle = (99999L shl 32) or 0xcafed00dL
        val name = "cellular-${NetworkMonitor.networkIdOf(handle)}"
        assertTrue("too long for char iface[16]: $name", name.length <= 15)
    }

    @Test
    fun `activeNetworks is empty before start`() {
        val context = RuntimeEnvironment.getApplication()
        val monitor = NetworkMonitor(context)
        assertTrue(monitor.activeNetworks.isEmpty())
    }

    @Test
    fun `stop clears activeNetworks`() {
        val context = RuntimeEnvironment.getApplication()
        val monitor = NetworkMonitor(context)
        val events = mutableListOf<NetworkEvent>()
        monitor.start { events.add(it) }
        monitor.stop()
        assertTrue(monitor.activeNetworks.isEmpty())
    }
}
