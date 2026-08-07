// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 mp0rta and mqvpn contributors

package com.mqvpn.app.ui

import com.mqvpn.sdk.core.model.PathInfo

/** Live per-provider throughput for one VPN path. */
data class PathThroughput(
    val key: String,        // stable path key from libmqvpn, e.g. "wifi-291"
    val label: String,      // display name: custom > auto (SSID/carrier) > transport
    val colorSlot: Int,     // stable series color slot for charts/legend
    val downBps: Double,    // instantaneous receive rate, bits/s
    val upBps: Double,      // instantaneous send rate, bits/s
    val srttMs: Long,
    val status: Int,
    val totalTx: Long,
    val totalRx: Long,
    /**
     * How long we have been sending on this path without a single byte
     * coming back, in ms; 0 when the path is answering (or when nothing is
     * being sent, which is idle rather than broken).
     *
     * This is the honest liveness signal. [srttMs] is a *smoothed* RTT and
     * can only be recomputed when a reply arrives, so a path that dies keeps
     * displaying whatever RTT it last measured while alive — the "Starlink is
     * dead but the UI still says 80 ms" case. libmqvpn increments bytes_tx on
     * every successful sendto() and bytes_rx only on a packet that actually
     * arrived, so "tx moving, rx frozen" is exactly a link the OS still
     * believes in but that carries nothing.
     */
    val noReplyMs: Long = 0,
    /** Share of the current aggregate download, 0f..1f. */
    val downShare: Float = 0f,
) {
    /** True once the silence is long enough to report instead of the stale RTT. */
    val isStale: Boolean get() = noReplyMs >= SpeedTracker.NO_REPLY_WARN_MS
}

/** One chart sample: per-path rates plus the aggregate at that instant. */
data class ThroughputFrame(
    val perPath: Map<String, Sample>,
    val aggregate: Sample,
) {
    data class Sample(val downBps: Double, val upBps: Double)
}

data class ThroughputUi(
    val paths: List<PathThroughput> = emptyList(),
    val aggregateDownBps: Double = 0.0,
    val aggregateUpBps: Double = 0.0,
    val history: List<ThroughputFrame> = emptyList(),
)

/**
 * Converts cumulative per-path byte counters into rates and a rolling
 * history window. Not thread-safe; call from a single coroutine.
 *
 * Rate rules:
 * - first sighting of a path (or a path whose handle changed — i.e. it was
 *   torn down and re-added, resetting counters) reports 0 until the next tick
 * - counters running backwards clamp to 0 rather than spiking negative
 */
class SpeedTracker(private val maxSamples: Int = MAX_SAMPLES) {

    private data class Prev(val handle: Long, val tx: Long, val rx: Long)

    /** Last tick at which this path's tx / rx counters actually moved. */
    private data class Liveness(var lastTxMs: Long, var lastRxMs: Long)

    // Long.MIN_VALUE = "no previous sample yet"; 0 is a legitimate timestamp.
    private var lastAtMs = Long.MIN_VALUE
    private val prev = HashMap<String, Prev>()
    private val live = HashMap<String, Liveness>()
    private val slots = HashMap<String, Int>()
    private var nextSlot = 0
    private val history = ArrayDeque<ThroughputFrame>()

    fun update(
        paths: List<PathInfo>,
        autoLabels: Map<String, String>,
        customNames: Map<String, String>,
        nowMs: Long,
    ): ThroughputUi {
        val dtSec = if (lastAtMs == Long.MIN_VALUE) 0.0 else (nowMs - lastAtMs) / 1000.0
        lastAtMs = nowMs

        // libmqvpn keeps closed paths in get_paths(), and a re-created path
        // reuses its iface name — so a dead entry and its live replacement can
        // both be present under one key, and the dead one would overwrite the
        // live one's rates. A closed path carries nothing; drop it here and let
        // the diagnostics log (keyed by handle) be what records its fate.
        val active = paths.filter { it.status != STATUS_CLOSED }

        val out = ArrayList<PathThroughput>(active.size)
        val frame = LinkedHashMap<String, ThroughputFrame.Sample>()
        val seen = HashSet<String>()
        var aggDown = 0.0
        var aggUp = 0.0

        for (p in active) {
            val key = p.iface
            seen += key
            val slot = slots.getOrPut(key) { nextSlot++ }
            val prior = prev[key]
            var down = 0.0
            var up = 0.0
            if (prior != null && prior.handle == p.handle && dtSec > 0) {
                down = (p.bytesRx - prior.rx).coerceAtLeast(0) * 8 / dtSec
                up = (p.bytesTx - prior.tx).coerceAtLeast(0) * 8 / dtSec
            }

            // A path re-created under a new handle resets its counters, so its
            // silence history has to start over too rather than carry the dead
            // incarnation's timestamps forward.
            val fresh = prior == null || prior.handle != p.handle
            val l = if (fresh) {
                Liveness(nowMs, nowMs).also { live[key] = it }
            } else {
                live.getOrPut(key) { Liveness(nowMs, nowMs) }
            }
            if (!fresh) {
                if (p.bytesTx > prior.tx) l.lastTxMs = nowMs
                if (p.bytesRx > prior.rx) l.lastRxMs = nowMs
            }
            // Only sending-without-answer counts. Both counters idle means the
            // tunnel simply has nothing to carry, which is not a fault.
            val noReply = if (l.lastTxMs > l.lastRxMs) l.lastTxMs - l.lastRxMs else 0L

            prev[key] = Prev(p.handle, p.bytesTx, p.bytesRx)

            val auto = autoLabels[key] ?: fallbackLabel(key)
            val label = customNames[auto.lowercase()] ?: auto
            aggDown += down
            aggUp += up
            frame[key] = ThroughputFrame.Sample(down, up)
            out += PathThroughput(
                key = key, label = label, colorSlot = slot,
                downBps = down, upBps = up,
                srttMs = p.srttMs, status = p.status,
                totalTx = p.bytesTx, totalRx = p.bytesRx,
                noReplyMs = noReply,
            )
        }
        prev.keys.retainAll(seen)
        live.keys.retainAll(seen)

        history.addLast(ThroughputFrame(frame, ThroughputFrame.Sample(aggDown, aggUp)))
        while (history.size > maxSamples) history.removeFirst()

        // Share is only meaningful once something is actually flowing; at zero
        // aggregate every path would otherwise read as 0% and look broken.
        val withShare = if (aggDown > 0) {
            out.map { it.copy(downShare = (it.downBps / aggDown).toFloat()) }
        } else {
            out
        }

        return ThroughputUi(withShare, aggDown, aggUp, history.toList())
    }

    fun reset() {
        lastAtMs = Long.MIN_VALUE
        prev.clear()
        live.clear()
        history.clear()
        // keep slots: a reconnected provider keeps its color
    }

    companion object {
        const val MAX_SAMPLES = 60

        /** MQVPN_PATH_CLOSED — mirrors mqvpn_path_status_t in libmqvpn.h. */
        const val STATUS_CLOSED = 4

        /**
         * Silence after which a path is reported as not answering instead of
         * showing its last-measured RTT. Four ticks: long enough to ride out a
         * single lost round trip or a scheduler that briefly favours the other
         * link, short enough to catch a dying link while still in the car.
         */
        const val NO_REPLY_WARN_MS = 4_000L

        fun fallbackLabel(key: String): String = when {
            key.startsWith("wifi") || key.startsWith("wlan") -> "Wi-Fi"
            key.startsWith("cellular") || key.startsWith("rmnet") ||
                key.startsWith("ccmni") -> "Mobile"
            key.startsWith("ethernet") || key.startsWith("eth") -> "Ethernet"
            else -> key
        }
    }
}

fun formatBps(bps: Double): String = when {
    bps >= 1_000_000_000 -> "%.2f Gbps".format(bps / 1e9)
    bps >= 1_000_000 -> "%.1f Mbps".format(bps / 1e6)
    bps >= 1_000 -> "%.0f kbps".format(bps / 1e3)
    else -> "%.0f bps".format(bps)
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.1f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
}
