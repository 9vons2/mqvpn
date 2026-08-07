// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 mp0rta and mqvpn contributors

package com.mqvpn.app

import com.mqvpn.app.service.NetSnapshot
import com.mqvpn.app.service.NetworkTrace
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkTraceTest {

    private fun snap(
        validated: Boolean = true,
        metered: Boolean = false,
        down: Int = 20_000,
        up: Int = 5_000,
        signal: Int = -60,
    ) = NetSnapshot("wifi", validated, metered, down, up, signal)

    private fun change(prev: NetSnapshot?, now: NetSnapshot) =
        NetworkTrace.describeChange("wifi-13", "Starlink", prev, now)

    @Test
    fun `a first sighting always reports the network`() {
        val line = change(null, snap())
        assertNotNull(line)
        assertTrue(line!!.contains("net up"))
        assertTrue(line.contains("Starlink"))
        assertTrue(line.contains("validated"))
    }

    /**
     * The whole reason this trace exists: the OS withdrawing validation, or
     * stubbornly keeping it, is what explains a path going silent.
     */
    @Test
    fun `losing validation is always reported`() {
        val line = change(snap(validated = true), snap(validated = false))
        assertNotNull(line)
        assertTrue(line!!.contains("LOST VALIDATION"))
    }

    @Test
    fun `regaining validation is reported`() {
        val line = change(snap(validated = false), snap(validated = true))
        assertTrue(line!!.contains("validated"))
    }

    @Test
    fun `a metering flip is reported`() {
        val line = change(snap(metered = false), snap(metered = true))
        assertTrue(line!!.contains("now metered"))
    }

    /**
     * onCapabilitiesChanged fires several times a second in a moving car. If
     * small wobbles produced lines, the 64 KB ring would hold minutes instead
     * of hours and the events worth reading would be gone.
     */
    @Test
    fun `an unchanged network produces no line`() {
        assertNull(change(snap(), snap()))
    }

    @Test
    fun `a small bandwidth wobble is noise`() {
        assertNull(change(snap(down = 20_000), snap(down = 21_000)))
    }

    @Test
    fun `a bandwidth collapse is reported`() {
        val line = change(snap(down = 20_000), snap(down = 2_000))
        assertTrue(line!!.contains("est ↓2000kbps"))
    }

    @Test
    fun `a small signal drift is noise`() {
        assertNull(change(snap(signal = -60), snap(signal = -63)))
    }

    @Test
    fun `a real signal drop is reported`() {
        val line = change(snap(signal = -60), snap(signal = -95))
        assertTrue(line!!.contains("-60→-95 dBm"))
    }

    /** Devices below API 29 report no signal; that must not read as a change. */
    @Test
    fun `an unknown signal is never a change`() {
        val unknown = NetSnapshot.UNKNOWN_SIGNAL
        assertNull(change(snap(signal = unknown), snap(signal = unknown)))
        assertNull(change(snap(signal = -60), snap(signal = unknown)))
    }
}
