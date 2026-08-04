// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 mp0rta and mqvpn contributors

package com.mqvpn.app

import com.mqvpn.app.service.Health
import com.mqvpn.app.service.NotifPath
import com.mqvpn.app.service.NotificationRender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationRenderTest {

    private fun p(
        label: String,
        down: Double = 0.0,
        up: Double = 0.0,
        srtt: Long = 40,
        noReply: Long = 0,
        status: Int = 1,
        share: Float = 0f,
    ) = NotifPath(label, down, up, srtt, noReply, status, share)

    private fun render(vararg paths: NotifPath, history: List<Double> = emptyList()) =
        NotificationRender.render(
            paths.toList(),
            aggDownBps = paths.sumOf { it.downBps },
            aggUpBps = paths.sumOf { it.upBps },
            history = history,
            schedulerLabel = "WLB",
        )

    // -- bar ------------------------------------------------------------------

    @Test
    fun `bar fills proportionally and stays a fixed width`() {
        assertEquals("████████", NotificationRender.bar(1f))
        assertEquals("░░░░░░░░", NotificationRender.bar(0f))
        assertEquals("████░░░░", NotificationRender.bar(0.5f))
        assertEquals(8, NotificationRender.bar(0.37f).length)
    }

    /** A link doing 3% of the work should not read as doing none. */
    @Test
    fun `a carrying path always shows at least one cell`() {
        assertEquals("█░░░░░░░", NotificationRender.bar(0.03f))
    }

    // -- sparkline ------------------------------------------------------------

    @Test
    fun `sparkline scales to its own peak`() {
        assertEquals("▁▄█", NotificationRender.sparkline(listOf(0.0, 50.0, 100.0)))
    }

    /** An idle tunnel must render a flat floor, not divide by zero. */
    @Test
    fun `sparkline survives an all-zero history`() {
        assertEquals("▁▁▁", NotificationRender.sparkline(listOf(0.0, 0.0, 0.0)))
    }

    // -- health ---------------------------------------------------------------

    @Test
    fun `all answering is OK`() {
        assertEquals(Health.OK, render(p("Starlink", down = 1e6), p("Kyivstar", down = 1e6)).health)
    }

    @Test
    fun `one silent path warns while the other still carries`() {
        val c = render(p("Starlink", down = 1e6), p("Kyivstar", noReply = 9_000))
        assertEquals(Health.WARN, c.health)
    }

    @Test
    fun `every path silent is BAD`() {
        val c = render(p("Starlink", noReply = 9_000), p("Kyivstar", noReply = 5_000))
        assertEquals(Health.BAD, c.health)
    }

    // -- rows -----------------------------------------------------------------

    /**
     * The stale-RTT trap again: a silent path must never print the RTT it
     * measured while alive, because that is what made a dead link look fine.
     */
    @Test
    fun `a silent path reports the silence instead of its last RTT`() {
        val c = render(p("Kyivstar", srtt = 80, noReply = 12_000))
        assertTrue(c.expanded.contains("no reply for 12s"))
        assertTrue("must not show the stale RTT", !c.expanded.contains("80 ms"))
        assertTrue(c.expanded.contains("🔴"))
    }

    @Test
    fun `a healthy path shows rates and RTT`() {
        val c = render(p("Starlink", down = 35e6, up = 6e6, srtt = 48, share = 1f))
        assertTrue(c.expanded.contains("48 ms"))
        assertTrue(c.expanded.contains("🟢"))
        assertTrue(c.expanded.contains("████████"))
    }

    @Test
    fun `closed paths are marked and carry no rates`() {
        val c = render(p("Kyivstar", status = 4))
        assertTrue(c.expanded.contains("⚫"))
        assertTrue(c.expanded.contains("closed"))
    }

    // -- collapsed line -------------------------------------------------------

    /** One glance, driving: the aggregate and whatever is wrong. */
    @Test
    fun `collapsed line names the troubled link`() {
        val c = render(p("Starlink", down = 35e6, share = 1f), p("Kyivstar", noReply = 9_000))
        assertTrue(c.collapsed.contains("Kyivstar"))
        assertTrue(c.collapsed.contains("🔴"))
    }

    @Test
    fun `collapsed line names the carrying link when all is well`() {
        val c = render(
            p("Starlink", down = 30e6, share = 0.75f),
            p("Kyivstar", down = 10e6, share = 0.25f),
        )
        assertTrue(c.collapsed.contains("Starlink"))
        assertTrue(c.collapsed.contains("75%"))
    }

    @Test
    fun `a single healthy path needs no breakdown in the collapsed line`() {
        val c = render(p("Starlink", down = 30e6, share = 1f))
        assertTrue(!c.collapsed.contains("Starlink"))
    }

    // -- subtext --------------------------------------------------------------

    @Test
    fun `subtext counts only the links actually answering`() {
        val c = render(p("Starlink", down = 1e6), p("Kyivstar", noReply = 9_000))
        assertEquals("WLB · 1/2 links", c.subText)
    }

    @Test
    fun `subtext is singular for one link`() {
        assertEquals("WLB · 1/1 link", render(p("Starlink", down = 1e6)).subText)
    }
}
