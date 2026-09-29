// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared

import com.heartline.shared.bp.BpSessionLog
import com.heartline.shared.bp.SensorStream
import com.heartline.shared.diag.LogFiles
import com.heartline.shared.diag.LogOffload
import com.heartline.shared.diag.RawSessions
import com.heartline.shared.diag.SegmentedLog
import com.heartline.shared.diag.SessionCsv
import com.heartline.shared.diag.WatchLogArchive
import com.heartline.shared.sync.ArchiveAck
import com.heartline.shared.sync.InMemoryTransport
import com.heartline.shared.sync.Outbox
import com.heartline.shared.sync.OutboxItem
import com.heartline.shared.sync.PhoneSyncEngine
import com.heartline.shared.sync.RecordSink
import com.heartline.shared.sync.WatchSyncEngine
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawLogsTest {
    private fun dir() = Files.createTempDirectory("raw").toFile()

    private fun sessions(
        dir: File,
        budget: Long = Long.MAX_VALUE,
        clock: () -> Long = {
            1_790_000_000_000
        },
        saved: MutableList<File> = mutableListOf()
    ) = RawSessions(dir, budget, device = "SM-R960", appVersion = "1.0", freeBytes = { Long.MAX_VALUE }, clockMs = clock, onSaved = {
        saved +=
            it
    })
        .apply { enabled = true }

    private fun decode(f: File) = BpSessionLog.decode(f.readBytes())

    @Test
    fun aMeasurementKeepsEverySampleOfEverySensor() {
        val saved = mutableListOf<File>()
        val s = sessions(dir(), saved = saved)
        val id = s.begin("ecg", mapOf("fs" to "500"))
        s.rows(
            "ECG_ON_DEMAND",
            listOf("ECG_MV", "LEAD_OFF", "SEQUENCE"),
            longArrayOf(1, 2, 3),
            List(3) {
                floatArrayOf(it * 0.1f, 0f, it.toFloat())
            }
        )
        s.rows("android.accelerometer", listOf("v0", "v1", "v2", "accuracy"), longArrayOf(5), listOf(floatArrayOf(0f, 9.8f, 0f, 3f)))
        // The same stream with other columns (a tracker that lost a channel) continues under a new name.
        s.rows("ECG_ON_DEMAND", listOf("ECG_MV"), longArrayOf(4), listOf(floatArrayOf(0.5f)))
        s.event("phase", "recording")
        s.end(id, mapOf("bpm" to 61.0), mapOf("result" to "sinus"))
        s.awaitWritten()

        val file = s.files().single()
        assertEquals(saved, listOf(file))
        assertTrue(RawSessions.isSession(file.name))
        assertEquals("ecg", RawSessions.kindOf(file.name))
        val log = decode(file)
        assertEquals("ecg", log.header.kind)
        assertEquals("SM-R960", log.header.device)
        assertEquals(61.0, log.header.values["bpm"])
        assertEquals("sinus", log.header.notes["result"])
        assertEquals("500", log.header.notes["fs"])
        assertEquals(listOf("ECG_ON_DEMAND", "android.accelerometer", "ECG_ON_DEMAND#2"), log.streams.map { it.name })
        assertEquals(3, log.stream("ECG_ON_DEMAND")!!.size)
        assertEquals(2f, log.stream("ECG_ON_DEMAND")!!.column("SEQUENCE")!![2])
        assertEquals("phase", log.header.events.single().type)
    }

    @Test
    fun valuesOutsideAMeasurementGoToOtherSessions() {
        var now = 1_790_000_000_000
        val s = sessions(dir(), clock = { now })
        s.rows("HEART_RATE_CONTINUOUS", listOf("HEART_RATE"), longArrayOf(1), listOf(floatArrayOf(60f)))
        // A background window doesn't interrupt a running measurement…
        val measure = s.begin("spo2")
        assertEquals("", s.begin("irn_window", background = true))
        s.rows("SPO2_ON_DEMAND", listOf("SPO2"), longArrayOf(2), listOf(floatArrayOf(97f)))
        s.end(measure)
        // …and the "other" session closes after its time, so it never grows without end.
        s.rows("HEART_RATE_CONTINUOUS", listOf("HEART_RATE"), longArrayOf(3), listOf(floatArrayOf(61f)))
        now += 11 * 60_000
        s.rows("HEART_RATE_CONTINUOUS", listOf("HEART_RATE"), longArrayOf(4), listOf(floatArrayOf(62f)))
        s.flush()
        s.awaitWritten()
        // Before the measurement, the measurement, then two background sessions 11 minutes apart.
        val kinds = s.files().map { RawSessions.kindOf(it.name) }
        assertEquals(3, kinds.count { it == RawSessions.OTHER })
        assertEquals(1, kinds.count { it == "spo2" })
        val spo2 = decode(s.files().first { RawSessions.kindOf(it.name) == "spo2" })
        assertEquals(97f, spo2.stream("SPO2_ON_DEMAND")!!.column("SPO2")!!.single())
    }

    @Test
    fun budgetDiscardAndOff() {
        val d = dir()
        val s = sessions(d, budget = 1)
        repeat(3) {
            val id = s.begin("quick")
            s.rows("SPO2_ON_DEMAND", listOf("SPO2"), longArrayOf(1), listOf(floatArrayOf(97f)))
            s.end(id)
            s.awaitWritten()
        }
        assertEquals(1, s.files().size) // the newest one stays
        val bp = s.begin("bp")
        s.rows("PPG_ON_DEMAND", listOf("PPG_GREEN"), longArrayOf(1), listOf(floatArrayOf(1f)))
        s.discard(bp)
        s.flush()
        s.awaitWritten()
        assertEquals(1, s.files().size)
        s.enabled = false
        s.begin("ecg").also { s.end(it) }
        s.rows("ECG_ON_DEMAND", listOf("ECG_MV"), longArrayOf(1), listOf(floatArrayOf(1f)))
        s.flush()
        s.clear()
        assertEquals(0, s.files().size)
    }

    @Test
    fun csvHasEveryRowAndEmptyCellsForMissingValues() {
        val stream =
            SensorStream(
                "PPG_ON_DEMAND",
                listOf("PPG_GREEN", "PPG_IR"),
                longArrayOf(1_790_000_000_000_000_000, 1_790_000_000_010_000_000),
                floatArrayOf(1200f, Float.NaN, 1210f, 815.5f)
            )
        val out = ByteArrayOutputStream().also { SessionCsv.write(stream, it) }.toString()
        val lines = out.trim().lines()
        assertEquals("timestampNs,time,PPG_GREEN,PPG_IR", lines[0])
        assertEquals(3, lines.size)
        assertTrue(lines[1], lines[1].startsWith("1790000000000000000,") && lines[1].endsWith(",1200,"))
        assertTrue(lines[2], lines[2].endsWith(",1210,815.500"))
        assertEquals("PPG_ON_DEMAND.csv", SessionCsv.fileName(stream))
    }

    @Test
    fun segmentNumbersNeverRepeatAfterTheyMoved() {
        val d = dir()
        val sealed = mutableListOf<String>()
        val log = SegmentedLog(d, Long.MAX_VALUE, segmentBytes = 10, freeBytes = { Long.MAX_VALUE }, onSealed = { sealed += it.name })
        log.append("0123456789\n")
        log.segments().forEach { it.delete() } // moved to the phone
        log.append("abcdefghij\n")
        assertEquals(listOf("seg-00000001.log.gz", "seg-00000002.log.gz"), sealed)
        assertEquals(2L, SegmentedLog.number(sealed[1]))
    }

    @Test
    fun archiveKeepsOnlyLogFilesWithinItsBudget() {
        val a = WatchLogArchive(dir(), budgetBytes = 25, freeBytes = { Long.MAX_VALUE })
        assertEquals(null, a.receive(LogFiles.LOG, "../evil", "x".byteInputStream()))
        assertEquals(10L, a.receive(LogFiles.LOG, "seg-00000002.log.gz", "0123456789".byteInputStream()))
        Thread.sleep(20)
        assertEquals(10L, a.receive(LogFiles.LOG, "seg-00000001.log.gz", "0123456789".byteInputStream()))
        Thread.sleep(20)
        a.receive(LogFiles.RAW, "20260929-173755-123-ecg-abcd1234.hlbp", "0123456789".byteInputStream())
        // Over 25 bytes: the oldest received went.
        assertEquals(listOf("seg-00000001.log.gz"), a.logSegments().map { it.name })
        assertEquals(1, a.rawSessions().size)
        a.clear()
        assertEquals(0L, a.sizeBytes())
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
    fun finishedFilesMoveToThePhoneAndLeaveTheWatch() = runTest {
        val watchDir = dir()
        val segment = File(watchDir, "seg-00000001.log.gz").apply { writeText("segment") }
        val raw = File(watchDir, "20260929-173755-123-ecg-abcd1234.hlbp").apply { writeText("raw session") }
        val archive = WatchLogArchive(dir(), Long.MAX_VALUE, freeBytes = { Long.MAX_VALUE })
        val (phoneSide, watchSide) = InMemoryTransport.pair()
        lateinit var phone: PhoneSyncEngine
        lateinit var offload: LogOffload
        phone = PhoneSyncEngine(phoneSide, NoRecords(), onArchive = { type, name, input ->
            archive.receive(type, name, input)?.let { phone.ackArchive(ArchiveAck(type, name, it)) }
        })
        val watch = WatchSyncEngine(watchSide, EmptyOutbox(), onArchiveAck = { offload.onAck(it) })
        offload = LogOffload(
            pending = { listOf(LogFiles.LOG to segment, LogFiles.RAW to raw).filter { it.second.exists() } },
            send = { type, file -> watch.sendArchive(type, file.name, file.inputStream()) }
        )
        watchSide.incoming.onEach { watch.handle(it) }.launchIn(backgroundScope)
        phoneSide.incoming.onEach { phone.handle(it) }.launchIn(backgroundScope)
        testScheduler.runCurrent()

        // The phone is away: nothing goes, nothing is lost.
        watchSide.connected = false
        assertEquals(0, offload.run())
        assertTrue(offload.stalled)
        assertTrue(segment.exists() && raw.exists())

        watchSide.connected = true
        assertEquals(2, offload.run())
        testScheduler.runCurrent()
        assertFalse(offload.stalled)
        assertEquals("segment", archive.logSegments().single().readText())
        assertEquals("raw session", archive.rawSessions().single().readText())
        // Acked: the watch's copies are gone.
        assertFalse(segment.exists())
        assertFalse(raw.exists())
    }

    @Test
    fun aWrongSizeAckKeepsTheFile() {
        val f = Files.createTempFile("seg-00000001", ".log.gz").toFile().apply { writeText("12345") }
        val offload = LogOffload(pending = { listOf(LogFiles.LOG to f) }, send = { _, _ -> true })
        offload.onAck(ArchiveAck(LogFiles.LOG, f.name, 3))
        assertTrue(f.exists())
        offload.onAck(ArchiveAck(LogFiles.LOG, f.name, 5))
        assertFalse(f.exists())
    }
}
