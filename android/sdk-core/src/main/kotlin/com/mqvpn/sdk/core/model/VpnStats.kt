// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 mp0rta and mqvpn contributors

package com.mqvpn.sdk.core.model

/**
 * Aggregate VPN statistics from libmqvpn.
 *
 * Lane counters ([pktsLaneTcp]..[rawMarkersActive]) stay 0 unless hybrid
 * mode's classifier is active; tcp/dgram/raw partition every classified
 * packet exactly once.
 */
data class VpnStats(
    val bytesTx: Long = 0,
    val bytesRx: Long = 0,
    val dgramSent: Long = 0,
    val dgramRecv: Long = 0,
    val dgramLost: Long = 0,
    val dgramAcked: Long = 0,
    val srttMs: Int = 0,
    val pktsLaneTcp: Long = 0,
    val pktsLaneDgram: Long = 0,
    val pktsLaneRaw: Long = 0,
    val tcpFlowsActive: Long = 0,
    val tcpFlowsTotal: Long = 0,
    val tcpFlowsRejected: Long = 0,
    val pktsLaneTcpDropped: Long = 0,
    val rawMarkersActive: Long = 0,
)
