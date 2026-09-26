// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared

import com.heartline.shared.diag.DiagnosticsPolicy
import com.heartline.shared.diag.LogBundle
import com.heartline.shared.diag.LogLine
import com.heartline.shared.diag.Redactor
import com.heartline.shared.diag.RemoteLogs
import com.heartline.shared.diag.RollingLog
import com.heartline.shared.hr.MonitorSettings
import com.heartline.shared.sync.InMemoryTransport
import com.heartline.shared.sync.LogRequest
import com.heartline.shared.sync.Outbox
import com.heartline.shared.sync.OutboxItem
import com.heartline.shared.sync.PhoneSyncEngine
import com.heartline.shared.sync.Protocol
import com.heartline.shared.sync.RecordSink
import com.heartline.shared.sync.WatchSyncEngine
import java.nio.file.Files
import java.time.ZoneOffset
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsTest {
    @Test
    fun rollingLogKeepsTwoFilesInOrder() {
        val dir = Files.createTempDirectory("logs").toFile()
        val log = RollingLog(dir, maxFileBytes = 100)
        repeat(30) { log.append("line %02d\n".format(it)) } // 8 bytes per line
        assertTrue(log.sizeBytes() <= 200)
        val lines = log.readAll().lines().filter { it.isNotEmpty() }
        assertEquals("line 29", lines.last())
        assertEquals(lines.sorted(), lines) // oldest first, no gaps between the files
        assertEquals((lines.first().takeLast(2).toInt()..29).map { "line %02d".format(it) }, lines)
        log.clear()
        assertEquals(0, log.sizeBytes())
        assertEquals("", log.readAll())
    }

    @Test
    fun lineFormat() {
        val line = LogLine.format(0, 'I', "Heartline/ECG", "x".repeat(LogLine.MAX_MESSAGE + 10), zone = ZoneOffset.UTC)
        assertTrue(line.startsWith("01-01 00:00:00.000  I Heartline/ECG: xxx"))
        assertTrue(line.trimEnd().endsWith("…(10 more chars)"))
        val withError = LogLine.format(0, 'E', "T", "boom", IllegalStateException("bad"), ZoneOffset.UTC)
        assertTrue(withError.contains("java.lang.IllegalStateException: bad"))
    }

    @Test
    fun redactsNamesAndBirthDate() {
        val r = Redactor(listOf("Sara", "Sara Karimi", "Al"), birthDate = "1990-04-12")
        assertEquals(
            "status: displayName=<name>, full=<name>, born <birthdate>, Alert Saratoga",
            r.redact("status: displayName=Sara, full=Sara Karimi, born 1990-04-12, Alert Saratoga")
        )
    }

    @Test
    fun betaUsersOnByDefaultStableUsersAsked() {
        assertTrue(DiagnosticsPolicy.enabled(choice = null, betaUser = true))
        assertFalse(DiagnosticsPolicy.shouldAsk(choice = null, betaUser = true))
        assertFalse(DiagnosticsPolicy.enabled(choice = null, betaUser = false))
        assertTrue(DiagnosticsPolicy.shouldAsk(choice = null, betaUser = false))
        // An explicit answer always wins, and is never asked again.
        assertFalse(DiagnosticsPolicy.enabled(choice = false, betaUser = true))
        assertTrue(DiagnosticsPolicy.enabled(choice = true, betaUser = false))
        assertFalse(DiagnosticsPolicy.shouldAsk(choice = false, betaUser = false))
        assertTrue(DiagnosticsPolicy.detailed(enabled = true, untilMs = 2, nowMs = 1))
        assertFalse(DiagnosticsPolicy.detailed(enabled = true, untilMs = 1, nowMs = 1))
        assertFalse(DiagnosticsPolicy.detailed(enabled = false, untilMs = 2, nowMs = 1))
    }

    @Test
    fun settingsCarryDiagnosticsAndOldCopiesDecode() {
        val s = MonitorSettings(diagnosticLogs = true, detailedLogsUntilMs = 42)
        assertEquals(s, Protocol.json.decodeFromString<MonitorSettings>(Protocol.json.encodeToString(s)))
        val old = Protocol.json.decodeFromString<MonitorSettings>("""{"irregularRhythmEnabled":false}""")
        assertFalse(old.diagnosticLogs)
        assertEquals(0L, old.detailedLogsUntilMs)
    }

    private class NoRecords : RecordSink {
        override suspend fun contains(id: String) = false
        override suspend fun save(meta: com.heartline.shared.model.RecordMeta, wave: FloatArray?) {}

        override suspend fun delete(id: String) {}
    }

    private class EmptyOutbox : Outbox {
        override suspend fun pending(): List<OutboxItem> = emptyList()
        override suspend fun markDelivered(id: String) {}
    }

    @Test
    fun phoneFetchesTheWatchLog() = runTest {
        val (phoneSide, watchSide) = InMemoryTransport.pair()
        lateinit var watch: WatchSyncEngine
        lateinit var remote: RemoteLogs
        var deleted = false
        val phone = PhoneSyncEngine(phoneSide, NoRecords(), onLogs = { id, text -> remote.onLogs(id, text) })
        remote = RemoteLogs(send = phone::requestLogs)
        watch = WatchSyncEngine(watchSide, EmptyOutbox(), onLogRequest = { req: LogRequest ->
            if (req.delete) deleted = true else watch.sendLogs(req.requestId, "watch log".encodeToByteArray())
        })
        watchSide.incoming.onEach { watch.handle(it) }.launchIn(backgroundScope)
        phoneSide.incoming.onEach { phone.handle(it) }.launchIn(backgroundScope)
        testScheduler.runCurrent() // subscribe before anything is sent

        assertEquals("watch log", remote.fetch()!!.decodeToString())
        assertTrue(remote.delete())
        testScheduler.runCurrent() // backgroundScope work only runs with runCurrent
        assertTrue(deleted)

        watchSide.connected = false
        assertNull(remote.fetch(timeoutMs = 1_000))
    }

    @Test
    fun bundleLayout() {
        val text = LogBundle.build(linkedMapOf("App" to "phone 1.0.0", "Device" to "SM-S931B"), "a\n", "", note = "Watch not reachable")
        assertTrue(text.contains("App    : phone 1.0.0"))
        assertTrue(text.contains("NOTE: Watch not reachable"))
        assertTrue(text.indexOf("=== Heartline log") < text.indexOf("=== logcat"))
    }
}
