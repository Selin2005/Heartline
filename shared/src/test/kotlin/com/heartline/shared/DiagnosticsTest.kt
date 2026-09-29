// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared

import com.heartline.shared.diag.DiagnosticsPolicy
import com.heartline.shared.diag.LogBundle
import com.heartline.shared.diag.LogLine
import com.heartline.shared.diag.Redactor
import com.heartline.shared.diag.RemoteLogs
import com.heartline.shared.diag.SegmentedLog
import com.heartline.shared.hr.MonitorSettings
import com.heartline.shared.sync.InMemoryTransport
import com.heartline.shared.sync.LogManifest
import com.heartline.shared.sync.LogRequest
import com.heartline.shared.sync.LogSegment
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
    private fun lines(n: IntRange) = n.joinToString("") { "line %05d\n".format(it) } // 11 bytes per line

    @Test
    fun segmentedLogCompressesSegmentsInOrder() {
        val dir = Files.createTempDirectory("logs").toFile()
        val log = SegmentedLog(dir, budgetBytes = Long.MAX_VALUE, segmentBytes = 1_100, freeBytes = { Long.MAX_VALUE })
        lines(0 until 1000).lines().filter { it.isNotEmpty() }.forEach { log.append("$it\n") }
        val segments = log.segments()
        assertEquals(10, segments.size) // 100 lines of 11 bytes per segment
        assertTrue(segments.all { SegmentedLog.isSegment(it.name) })
        // Compressed, and each segment opens as the text written, in order.
        assertTrue(segments.sumOf { it.length() } < 11_000 / 3)
        assertEquals(lines(0 until 100), SegmentedLog.open(segments.first()).use { it.readBytes().decodeToString() })
        assertEquals(lines(0 until 1000), log.readAll())
        assertEquals(lines(900 until 1000), log.readRecent(1_100))
        // Sealing closes the current file into a segment.
        log.append("tail\n")
        log.seal()
        assertEquals(11, log.segments().size)
        assertTrue(log.readAll().endsWith("tail\n"))
        log.clear()
        assertEquals(0L, log.sizeBytes())
        assertEquals("", log.readAll())
    }

    @Test
    fun segmentedLogKeepsItsBudgetAndFreeSpace() {
        val dir = Files.createTempDirectory("logs").toFile()
        var free = Long.MAX_VALUE
        val log = SegmentedLog(dir, budgetBytes = 3 * 200L, segmentBytes = 1_100, minFreeBytes = 1_000, freeBytes = { free })
        lines(0 until 2000).lines().filter { it.isNotEmpty() }.forEach { log.append("$it\n") }
        assertTrue(log.segments().sumOf { it.length() } <= 600)
        // The newest lines are kept; the oldest went first.
        assertTrue(log.readAll().endsWith(lines(1990 until 2000)))
        assertFalse(log.readAll().contains("line 00000"))
        // Low on free space: old segments go, the newest one stays.
        free = 10
        lines(2000 until 2100).lines().filter { it.isNotEmpty() }.forEach { log.append("$it\n") }
        assertEquals(1, log.segments().size)
        assertEquals(lines(2000 until 2100), log.readAll())
    }

    @Test
    fun segmentedLogKeepsTheEarlierTwoFileLog() {
        val dir = Files.createTempDirectory("logs").toFile()
        java.io.File(dir, "previous.log").writeText("older\n")
        java.io.File(dir, "current.log").writeText("newer\n")
        val log = SegmentedLog(dir, budgetBytes = Long.MAX_VALUE, freeBytes = { Long.MAX_VALUE })
        log.migrate()
        assertFalse(java.io.File(dir, "previous.log").exists())
        assertEquals("older\nnewer\n", log.readAll())
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
    fun phoneFetchesTheWatchLogPieceByPiece() = runTest {
        val (phoneSide, watchSide) = InMemoryTransport.pair()
        lateinit var watch: WatchSyncEngine
        lateinit var remote: RemoteLogs
        val files = mapOf(
            "seg-00000001.log.gz" to "first part".encodeToByteArray(),
            "seg-00000002.log.gz" to ByteArray(300_000) { it.toByte() }
        )
        var failFirstTry = true
        var deleted = false
        val phone = PhoneSyncEngine(
            phoneSide,
            NoRecords(),
            onLogs = { id, text -> remote.onLogs(id, text) },
            onLogSegment = { id, input -> remote.onSegment(id, input) }
        )
        remote = RemoteLogs(send = phone::requestLogs, idleMs = 5_000, io = kotlin.coroutines.EmptyCoroutineContext)
        watch = WatchSyncEngine(watchSide, EmptyOutbox(), onLogRequest = { req: LogRequest ->
            val segment = req.segment
            when {
                req.delete -> deleted = true
                req.manifest -> watch.sendLogs(
                    req.requestId,
                    Protocol.json.encodeToString(
                        LogManifest(
                            "head\n",
                            files.map { (name, bytes) ->
                                LogSegment(name, bytes.size.toLong())
                            } + LogSegment("gone.log.gz", 5)
                        )
                    ).encodeToByteArray()
                )
                // The big segment is cut short on its first try; it's asked for again.
                segment == "seg-00000002.log.gz" && failFirstTry -> {
                    failFirstTry = false
                    watch.sendLogSegment(req.requestId, files.getValue(segment).copyOf(1000).inputStream())
                }
                segment != null -> watch.sendLogSegment(req.requestId, (files[segment] ?: ByteArray(0)).inputStream())
            }
        })
        watchSide.incoming.onEach { watch.handle(it) }.launchIn(backgroundScope)
        phoneSide.incoming.onEach { phone.handle(it) }.launchIn(backgroundScope)
        testScheduler.runCurrent() // subscribe before anything is sent

        val manifest = (remote.manifest() as RemoteLogs.Answer.Manifest).manifest
        assertEquals("head\n", manifest.header)
        assertEquals(3, manifest.segments.size)
        val dir = Files.createTempDirectory("export").toFile()
        val received = manifest.segments.map { segment -> java.io.File(dir, segment.name).takeIf { remote.download(segment, it) } }
        assertEquals("first part", received[0]!!.readText())
        assertTrue(files.getValue("seg-00000002.log.gz").contentEquals(received[1]!!.readBytes()))
        assertFalse(failFirstTry)
        assertNull(received[2]) // gone on the watch: empty after every try, so marked missing

        assertTrue(remote.delete())
        testScheduler.runCurrent() // backgroundScope work only runs with runCurrent
        assertTrue(deleted)

        watchSide.connected = false
        assertNull(remote.manifest(timeoutMs = 1_000))
    }

    @Test
    fun anOlderWatchSendsItsWholeLog() = runTest {
        val (phoneSide, watchSide) = InMemoryTransport.pair()
        lateinit var watch: WatchSyncEngine
        lateinit var remote: RemoteLogs
        val phone = PhoneSyncEngine(phoneSide, NoRecords(), onLogs = { id, text -> remote.onLogs(id, text) })
        remote = RemoteLogs(send = phone::requestLogs)
        // A watch from before segmented export ignores 'manifest' and answers with its whole log.
        watch =
            WatchSyncEngine(watchSide, EmptyOutbox(), onLogRequest = { req ->
                watch.sendLogs(req.requestId, "Heartline diagnostic log\nold".encodeToByteArray())
            })
        watchSide.incoming.onEach { watch.handle(it) }.launchIn(backgroundScope)
        phoneSide.incoming.onEach { phone.handle(it) }.launchIn(backgroundScope)
        testScheduler.runCurrent()

        val answer = remote.manifest() as RemoteLogs.Answer.WholeLog
        assertEquals("Heartline diagnostic log\nold", answer.text.decodeToString())
    }

    @Test
    fun bundleLayout() {
        val text = LogBundle.build(linkedMapOf("App" to "phone 1.0.0", "Device" to "SM-S931B"), "a\n", "", note = "Watch not reachable")
        assertTrue(text.contains("App    : phone 1.0.0"))
        assertTrue(text.contains("NOTE: Watch not reachable"))
        assertTrue(text.indexOf("=== Heartline log") < text.indexOf("=== logcat"))
    }
}
