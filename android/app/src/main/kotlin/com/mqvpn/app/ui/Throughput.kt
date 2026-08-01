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
)

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

    // Long.MIN_VALUE = "no previous sample yet"; 0 is a legitimate timestamp.
    private var lastAtMs = Long.MIN_VALUE
    private val prev = HashMap<String, Prev>()
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

        val out = ArrayList<PathThroughput>(paths.size)
        val frame = LinkedHashMap<String, ThroughputFrame.Sample>()
        val seen = HashSet<String>()
        var aggDown = 0.0
        var aggUp = 0.0

        for (p in paths) {
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
            )
        }
        prev.keys.retainAll(seen)

        history.addLast(ThroughputFrame(frame, ThroughputFrame.Sample(aggDown, aggUp)))
        while (history.size > maxSamples) history.removeFirst()

        return ThroughputUi(out, aggDown, aggUp, history.toList())
    }

    fun reset() {
        lastAtMs = Long.MIN_VALUE
        prev.clear()
        history.clear()
        // keep slots: a reconnected provider keeps its color
    }

    companion object {
        const val MAX_SAMPLES = 60

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
