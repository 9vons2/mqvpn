// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 mp0rta and mqvpn contributors

package com.mqvpn.app.service

import com.mqvpn.app.ui.formatBps

/** One provider row as the notification sees it. */
data class NotifPath(
    val label: String,
    val downBps: Double,
    val upBps: Double,
    val srttMs: Long,
    val noReplyMs: Long,
    val status: Int,
    /** Share of the aggregate download, 0f..1f. */
    val share: Float,
)

/** Rendered notification text plus the tint that summarises overall health. */
data class NotifContent(
    val collapsed: String,
    val expanded: String,
    val subText: String,
    val health: Health,
)

/**
 * Worst state across all paths, used to tint the whole notification so the
 * lock screen answers "is my link OK" before a single word is read.
 */
enum class Health { OK, WARN, BAD }

/**
 * Renders the ongoing notification.
 *
 * The notification is the only view of the tunnel available while driving —
 * the app itself is not open. So it carries what the dashboard carries: which
 * link is actually pulling the traffic, how the load is split, and which link
 * has gone quiet. Android gives no styled per-row widgets in a standard
 * notification, so the layout is built from characters that survive every
 * launcher and font: a status dot, a proportional bar, and a sparkline.
 */
object NotificationRender {

    private const val MQVPN_PATH_CLOSED = 4
    private const val MQVPN_PATH_STANDBY = 3
    private const val MQVPN_PATH_DEGRADED = 2

    /** Bar width in cells. Eight keeps the row inside one line on a phone. */
    private const val BAR_CELLS = 8

    private const val BAR_FULL = '█'
    private const val BAR_EMPTY = '░'

    /** Sparkline ramp, low to high. */
    private val SPARK = charArrayOf('▁', '▂', '▃', '▄', '▅', '▆', '▇', '█')

    fun render(
        paths: List<NotifPath>,
        aggDownBps: Double,
        aggUpBps: Double,
        history: List<Double>,
        schedulerLabel: String,
    ): NotifContent {
        val health = health(paths)

        val agg = "↓ ${formatBps(aggDownBps)}   ↑ ${formatBps(aggUpBps)}"

        // Collapsed line: the aggregate, plus a terse flag naming the link
        // that is in trouble — the one thing worth glancing at.
        val trouble = paths.firstOrNull { it.noReplyMs > 0 || it.status == MQVPN_PATH_CLOSED }
        val collapsed = if (trouble != null) {
            "$agg  ·  ${dot(trouble)} ${trouble.label}"
        } else {
            val carrying = paths.maxByOrNull { it.downBps }
            if (carrying != null && paths.size > 1) {
                "$agg  ·  ${carrying.label} ${pct(carrying.share)}"
            } else {
                agg
            }
        }

        val rows = StringBuilder(agg)
        if (history.size >= 2) {
            rows.append('\n').append(sparkline(history))
        }
        for (p in paths) {
            rows.append('\n').append(row(p))
        }

        val active = paths.count { it.status != MQVPN_PATH_CLOSED && it.noReplyMs == 0L }
        val sub = "$schedulerLabel · $active/${paths.size} link" +
            if (paths.size == 1) "" else "s"

        return NotifContent(collapsed, rows.toString(), sub, health)
    }

    /**
     * A path is only healthy when it is answering. A stale RTT reads as a
     * working link, so silence outranks every other signal here.
     */
    private fun dot(p: NotifPath): String = when {
        p.status == MQVPN_PATH_CLOSED -> "⚫"
        p.noReplyMs > 0 -> "🔴"
        p.status == MQVPN_PATH_DEGRADED -> "🟠"
        p.status == MQVPN_PATH_STANDBY -> "🟡"
        else -> "🟢"
    }

    private fun row(p: NotifPath): String {
        val head = "${dot(p)} ${p.label}  ${bar(p.share)} ${pct(p.share)}"
        val detail = when {
            p.status == MQVPN_PATH_CLOSED -> "closed"
            p.noReplyMs > 0 -> "no reply for ${p.noReplyMs / 1000}s"
            else -> "↓ ${formatBps(p.downBps)}  ↑ ${formatBps(p.upBps)} · ${p.srttMs} ms"
        }
        return "$head\n     $detail"
    }

    /** Proportional bar; a carrying path always shows at least one cell. */
    internal fun bar(share: Float): String {
        val filled = when {
            share <= 0f -> 0
            else -> (share * BAR_CELLS).toInt().coerceIn(1, BAR_CELLS)
        }
        return buildString {
            repeat(filled) { append(BAR_FULL) }
            repeat(BAR_CELLS - filled) { append(BAR_EMPTY) }
        }
    }

    private fun pct(share: Float): String = "${(share * 100).toInt()}%"

    /**
     * Aggregate throughput over the recent past, scaled to its own peak.
     * Relative rather than absolute: the point is the shape — steady, ramping,
     * or collapsed — which is what tells you a link just died.
     */
    internal fun sparkline(history: List<Double>): String {
        val peak = history.max()
        if (peak <= 0.0) return SPARK[0].toString().repeat(history.size)
        return history.joinToString("") { v ->
            val idx = ((v / peak) * (SPARK.size - 1)).toInt().coerceIn(0, SPARK.size - 1)
            SPARK[idx].toString()
        }
    }

    private fun health(paths: List<NotifPath>): Health = when {
        paths.isEmpty() -> Health.BAD
        paths.none { it.status != MQVPN_PATH_CLOSED && it.noReplyMs == 0L } -> Health.BAD
        paths.any { it.noReplyMs > 0 || it.status == MQVPN_PATH_CLOSED } -> Health.WARN
        else -> Health.OK
    }
}
