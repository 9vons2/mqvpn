// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 mp0rta and mqvpn contributors

package com.mqvpn.app

import com.mqvpn.app.service.DiagnosticsLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DiagnosticsLogTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newLog(maxBytes: Long = DiagnosticsLog.MAX_BYTES) =
        DiagnosticsLog(File(tmp.root, "diag.log"), maxBytes)

    @Test
    fun `reading before anything is written yields empty`() {
        assertEquals("", newLog().read())
    }

    @Test
    fun `entries are appended in order and timestamped`() {
        val log = newLog()
        log.log("first")
        log.log("second")

        val lines = log.read().trim().lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].endsWith("first"))
        assertTrue(lines[1].endsWith("second"))
        // "MM-dd HH:mm:ss  " prefix, so the message is not at column 0.
        assertTrue(lines[0].length > "first".length)
    }

    @Test
    fun `survives a new instance over the same file`() {
        newLog().log("before restart")
        assertTrue(newLog().read().contains("before restart"))
    }

    @Test
    fun `clear empties the log`() {
        val log = newLog()
        log.log("something")
        log.clear()
        assertEquals("", log.read())
    }

    /**
     * The trace is a ring: a long drive must not grow without bound, but the
     * recent entries — the ones covering whatever just went wrong — have to
     * survive. Losing the newest half instead of the oldest would defeat it.
     */
    @Test
    fun `rotation drops oldest entries and keeps the newest`() {
        val log = newLog(maxBytes = 2_000)
        repeat(200) { log.log("entry number $it padded to make the file grow faster") }

        val text = log.read()
        assertTrue("should stay near the cap", text.length < 4_000)
        assertTrue("newest entry must survive", text.contains("entry number 199"))
        assertTrue("oldest entry must be gone", !text.contains("entry number 0 "))
        assertTrue("truncation should be visible", text.contains("earlier entries dropped"))
    }

    @Test
    fun `rotation cuts on a line boundary`() {
        val log = newLog(maxBytes = 1_000)
        repeat(120) { log.log("line $it") }

        // Every retained line keeps the "MM-dd HH:mm:ss  line N" shape; a cut
        // mid-record would leave a fragment that starts with something else.
        val body = log.read().lines().drop(1).filter { it.isNotBlank() }
        assertTrue(body.isNotEmpty())
        assertTrue(body.all { Regex("""^\d\d-\d\d \d\d:\d\d:\d\d {2}line \d+$""").matches(it) })
    }
}
