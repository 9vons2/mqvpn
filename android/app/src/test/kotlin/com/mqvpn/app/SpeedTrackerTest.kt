// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 mp0rta and mqvpn contributors

package com.mqvpn.app

import com.mqvpn.app.ui.SpeedTracker
import com.mqvpn.sdk.core.model.PathInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedTrackerTest {

    private fun path(
        iface: String,
        tx: Long,
        rx: Long,
        handle: Long = 1L,
    ) = PathInfo(handle = handle, status = 1, iface = iface, bytesTx = tx, bytesRx = rx, srttMs = 10)

    @Test
    fun `first sample reports zero rate`() {
        val t = SpeedTracker()
        val ui = t.update(listOf(path("wifi-1", tx = 1000, rx = 2000)), emptyMap(), emptyMap(), 1000)
        assertEquals(0.0, ui.paths.single().downBps, 0.0)
        assertEquals(0.0, ui.paths.single().upBps, 0.0)
    }

    @Test
    fun `second sample computes bits per second from byte deltas`() {
        val t = SpeedTracker()
        t.update(listOf(path("wifi-1", tx = 0, rx = 0)), emptyMap(), emptyMap(), 0)
        val ui = t.update(listOf(path("wifi-1", tx = 1_000, rx = 2_000)), emptyMap(), emptyMap(), 1000)
        val p = ui.paths.single()
        assertEquals(8_000.0, p.upBps, 0.01)     // 1000 B over 1 s
        assertEquals(16_000.0, p.downBps, 0.01)  // 2000 B over 1 s
        assertEquals(16_000.0, ui.aggregateDownBps, 0.01)
    }

    @Test
    fun `aggregate sums all paths`() {
        val t = SpeedTracker()
        t.update(
            listOf(path("wifi-1", 0, 0), path("cellular-2", 0, 0, handle = 2)),
            emptyMap(), emptyMap(), 0,
        )
        val ui = t.update(
            listOf(path("wifi-1", 500, 1000), path("cellular-2", 250, 750, handle = 2)),
            emptyMap(), emptyMap(), 1000,
        )
        assertEquals((1000 + 750) * 8.0, ui.aggregateDownBps, 0.01)
        assertEquals((500 + 250) * 8.0, ui.aggregateUpBps, 0.01)
    }

    @Test
    fun `handle change resets the rate instead of spiking`() {
        val t = SpeedTracker()
        t.update(listOf(path("wifi-1", 1_000_000, 1_000_000, handle = 1)), emptyMap(), emptyMap(), 0)
        // Path torn down and re-added: new handle, counters restarted
        val ui = t.update(listOf(path("wifi-1", 10, 10, handle = 2)), emptyMap(), emptyMap(), 1000)
        assertEquals(0.0, ui.paths.single().downBps, 0.0)
    }

    @Test
    fun `counter going backwards clamps to zero`() {
        val t = SpeedTracker()
        t.update(listOf(path("wifi-1", 1000, 1000)), emptyMap(), emptyMap(), 0)
        val ui = t.update(listOf(path("wifi-1", 900, 900)), emptyMap(), emptyMap(), 1000)
        assertEquals(0.0, ui.paths.single().downBps, 0.0)
        assertEquals(0.0, ui.paths.single().upBps, 0.0)
    }

    @Test
    fun `custom name wins over auto label which wins over fallback`() {
        val t = SpeedTracker()
        val auto = mapOf("cellular-2" to "Kyivstar")
        val custom = mapOf("kyivstar" to "Київстар")

        var ui = t.update(listOf(path("cellular-2", 0, 0)), emptyMap(), emptyMap(), 0)
        assertEquals("Mobile", ui.paths.single().label)

        ui = t.update(listOf(path("cellular-2", 0, 0)), auto, emptyMap(), 1000)
        assertEquals("Kyivstar", ui.paths.single().label)

        ui = t.update(listOf(path("cellular-2", 0, 0)), auto, custom, 2000)
        assertEquals("Київстар", ui.paths.single().label)
    }

    @Test
    fun `color slot is stable across updates and path churn`() {
        val t = SpeedTracker()
        var ui = t.update(
            listOf(path("wifi-1", 0, 0), path("cellular-2", 0, 0, handle = 2)),
            emptyMap(), emptyMap(), 0,
        )
        val wifiSlot = ui.paths.first { it.key == "wifi-1" }.colorSlot
        val cellSlot = ui.paths.first { it.key == "cellular-2" }.colorSlot

        // wifi path drops, then returns: slot must be unchanged
        t.update(listOf(path("cellular-2", 0, 0, handle = 2)), emptyMap(), emptyMap(), 1000)
        ui = t.update(
            listOf(path("wifi-1", 0, 0, handle = 3), path("cellular-2", 0, 0, handle = 2)),
            emptyMap(), emptyMap(), 2000,
        )
        assertEquals(wifiSlot, ui.paths.first { it.key == "wifi-1" }.colorSlot)
        assertEquals(cellSlot, ui.paths.first { it.key == "cellular-2" }.colorSlot)
    }

    @Test
    fun `history window is bounded`() {
        val t = SpeedTracker(maxSamples = 3)
        var ui = t.update(listOf(path("wifi-1", 0, 0)), emptyMap(), emptyMap(), 0)
        repeat(5) { i ->
            ui = t.update(listOf(path("wifi-1", 0, 0)), emptyMap(), emptyMap(), (i + 1) * 1000L)
        }
        assertEquals(3, ui.history.size)
    }

    @Test
    fun `history frames carry per path and aggregate samples`() {
        val t = SpeedTracker()
        t.update(listOf(path("wifi-1", 0, 0)), emptyMap(), emptyMap(), 0)
        val ui = t.update(listOf(path("wifi-1", 1000, 1000)), emptyMap(), emptyMap(), 1000)
        val frame = ui.history.last()
        assertTrue(frame.perPath.containsKey("wifi-1"))
        assertEquals(8_000.0, frame.aggregate.downBps, 0.01)
    }

    // -- liveness: "sending but nothing comes back" ---------------------------

    /**
     * The Starlink-in-motion case: the OS still reports the interface as up,
     * libmqvpn keeps getting successful sendto() calls (so tx climbs), but not
     * one byte comes back. srtt cannot fall here because a smoothed RTT is only
     * recomputed on a reply, which is exactly why the UI must not trust it.
     */
    @Test
    fun `tx moving with rx frozen is reported as no reply`() {
        val t = SpeedTracker()
        t.update(listOf(path("wifi-1", tx = 0, rx = 0)), emptyMap(), emptyMap(), 0)
        var ui = t.update(listOf(path("wifi-1", tx = 1_000, rx = 0)), emptyMap(), emptyMap(), 1000)
        assertEquals(1000L, ui.paths.single().noReplyMs)
        assertTrue(!ui.paths.single().isStale) // one second is not yet a verdict

        ui = t.update(listOf(path("wifi-1", tx = 5_000, rx = 0)), emptyMap(), emptyMap(), 5000)
        assertEquals(5000L, ui.paths.single().noReplyMs)
        assertTrue(ui.paths.single().isStale)
    }

    /** Idle tunnel: neither counter moves, which is not a fault. */
    @Test
    fun `both counters idle is not reported as no reply`() {
        val t = SpeedTracker()
        t.update(listOf(path("wifi-1", tx = 100, rx = 100)), emptyMap(), emptyMap(), 0)
        val ui = t.update(listOf(path("wifi-1", tx = 100, rx = 100)), emptyMap(), emptyMap(), 30_000)
        assertEquals(0L, ui.paths.single().noReplyMs)
        assertTrue(!ui.paths.single().isStale)
    }

    @Test
    fun `a reply clears the no-reply timer`() {
        val t = SpeedTracker()
        t.update(listOf(path("wifi-1", tx = 0, rx = 0)), emptyMap(), emptyMap(), 0)
        t.update(listOf(path("wifi-1", tx = 9_000, rx = 0)), emptyMap(), emptyMap(), 9000)
        val ui = t.update(listOf(path("wifi-1", tx = 10_000, rx = 1)), emptyMap(), emptyMap(), 10_000)
        assertEquals(0L, ui.paths.single().noReplyMs)
    }

    /** A re-created path resets counters; the dead incarnation's silence must not carry over. */
    @Test
    fun `handle change restarts the silence history`() {
        val t = SpeedTracker()
        t.update(listOf(path("wifi-1", tx = 0, rx = 0)), emptyMap(), emptyMap(), 0)
        t.update(listOf(path("wifi-1", tx = 8_000, rx = 0)), emptyMap(), emptyMap(), 8000)
        val ui = t.update(
            listOf(path("wifi-1", tx = 0, rx = 0, handle = 2L)), emptyMap(), emptyMap(), 9000,
        )
        assertEquals(0L, ui.paths.single().noReplyMs)
    }

    // -- share of the aggregate ----------------------------------------------

    @Test
    fun `download share splits between paths and is zero when idle`() {
        val t = SpeedTracker()
        val a = { rx: Long, h: Long, i: String -> path(i, tx = 0, rx = rx, handle = h) }
        t.update(listOf(a(0, 1, "wifi-1"), a(0, 2, "cellular-2")), emptyMap(), emptyMap(), 0)
        val ui = t.update(
            listOf(a(3_000, 1, "wifi-1"), a(1_000, 2, "cellular-2")),
            emptyMap(), emptyMap(), 1000,
        )
        val wifi = ui.paths.first { it.key == "wifi-1" }
        val cell = ui.paths.first { it.key == "cellular-2" }
        assertEquals(0.75f, wifi.downShare, 0.001f)
        assertEquals(0.25f, cell.downShare, 0.001f)

        val idle = t.update(
            listOf(a(3_000, 1, "wifi-1"), a(1_000, 2, "cellular-2")),
            emptyMap(), emptyMap(), 2000,
        )
        assertEquals(0f, idle.paths.first().downShare, 0.0f)
    }
}
