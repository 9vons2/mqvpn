// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 mp0rta and mqvpn contributors

package com.mqvpn.app.service

import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Append-only diagnostic trace that survives the UI, the process and a reboot.
 *
 * The in-app event list only exists while a ViewModel does — that is, while
 * someone is looking at the screen. A tunnel that wedges while the phone is in
 * a pocket on the road leaves nothing behind, which is exactly the case worth
 * diagnosing. So this writes from the service instead, to a file, and keeps
 * the tail across restarts.
 *
 * Deliberately plain text: it gets shared out of the app and read by a human,
 * often on a phone, so it has to be legible without tooling.
 *
 * File I/O only — no Android types — so the rotation logic is testable on the
 * JVM. Every write is synchronized: the tunnel executor thread, the network
 * callback and the main thread all log.
 */
class DiagnosticsLog(private val file: File, private val maxBytes: Long = MAX_BYTES) {

    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    /** Appends one timestamped line. Never throws — diagnostics must not break the tunnel. */
    fun log(line: String) {
        synchronized(this) {
            try {
                file.parentFile?.mkdirs()
                file.appendText("${stamp.format(Date())}  $line\n")
                if (file.length() > maxBytes) rotate()
            } catch (_: IOException) {
                // A full or unwritable disk must not take the VPN down with it.
            } catch (_: SecurityException) {
            }
        }
    }

    /** Whole trace, oldest first; empty string when nothing has been recorded. */
    fun read(): String = synchronized(this) {
        try {
            if (file.exists()) file.readText() else ""
        } catch (_: IOException) {
            ""
        }
    }

    fun clear() {
        synchronized(this) {
            try {
                file.delete()
            } catch (_: SecurityException) {
            }
        }
    }

    /**
     * Drops the oldest half. Halving rather than trimming line-by-line keeps
     * rotation O(1) per overflow instead of running on every subsequent write
     * once the file is full.
     */
    private fun rotate() {
        try {
            val text = file.readText()
            // Cut at a line boundary so the trace never starts mid-record.
            val cut = text.indexOf('\n', text.length / 2)
            val kept = if (cut >= 0 && cut < text.length - 1) text.substring(cut + 1) else ""
            file.writeText("--- earlier entries dropped ---\n$kept")
        } catch (_: IOException) {
        }
    }

    companion object {
        /** Small enough to share as intent text, big enough for hours of driving. */
        const val MAX_BYTES = 64L * 1024
        const val FILE_NAME = "mqvpn-diagnostics.log"
    }
}
