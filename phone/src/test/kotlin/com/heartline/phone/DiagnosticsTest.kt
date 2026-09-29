// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.heartline.datalayer.diag.HLog
import com.heartline.phone.data.SettingsRepository
import com.heartline.phone.diag.DiagnosticsRepository
import com.heartline.phone.diag.PhoneLogExporter
import com.heartline.phone.update.UpdateRepository
import com.heartline.shared.bp.BpSessionLog
import com.heartline.shared.bp.BpSessionRecorder
import com.heartline.shared.diag.RawSessions
import com.heartline.shared.diag.Redactor
import com.heartline.shared.diag.RemoteLogs
import com.heartline.shared.diag.SegmentedLog
import com.heartline.shared.hr.MonitorSettings
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiagnosticsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun PhoneLogExporter.LogParts.text() = ByteArrayOutputStream().also { writeTo(it) }.toString(Charsets.UTF_8.name())

    @Test
    fun keptLogIsRedactedWithRawValuesAndExportsWhole() = runTest {
        HLog.init(context, HLog.PHONE_BUDGET_BYTES)
        HLog.setRedactor(Redactor(listOf("Sara")))
        HLog.configure(diagnosticLogs = true)
        HLog.i("Heartline/Test", "hello from Sara")
        HLog.i("Heartline/EcgRaw", "raw batch 1")

        val work = File(context.cacheDir, "test-export").apply { mkdirs() }
        val exporter = PhoneLogExporter(context, SettingsRepository(context), UpdateRepository(context, "1.0.0"), RemoteLogs(send = { false }))
        val phone = exporter.phoneParts(work).text()
        assertTrue(phone.contains("Heartline phone"))
        assertTrue(phone.contains("hello from <name>"))
        assertFalse(phone.contains("Sara"))
        // Raw sensor values are always kept now.
        assertTrue(phone.contains("raw batch 1"))
        assertTrue(phone.indexOf("hello from") < phone.indexOf("=== logcat"))

        // The watch doesn't answer: its file says so.
        val watch = exporter.watchParts(work)
        assertFalse(watch.log.reached)
        assertTrue(watch.log.text().contains("The watch did not answer"))

        HLog.configure(diagnosticLogs = false)
        HLog.flush()
        assertEquals(0L, HLog.sizeBytes())
    }

    @Test
    fun aLongLogIsStreamedWholeAndInOrder() {
        // 20 MB of text in 1 MB segments, written out without holding it in memory.
        val dir = File(context.cacheDir, "long-log").apply { deleteRecursively() }
        val log = SegmentedLog(dir, budgetBytes = Long.MAX_VALUE, freeBytes = { Long.MAX_VALUE })
        val line = "x".repeat(90)
        for (i in 0 until 200_000) log.append("%06d $line\n".format(i)) // 98 bytes per line
        log.seal()
        val logcat = File(dir, "logcat.log.gz").apply { java.util.zip.GZIPOutputStream(outputStream()).use { it.write("logcat line\n".toByteArray()) } }
        val parts = PhoneLogExporter.LogParts("head\n", log.segments(), logcat)
        var size = 0L
        var first = ""
        var last = ""
        val sink = object : java.io.OutputStream() {
            private val current = StringBuilder()

            override fun write(b: Int) {
                size++
                if (b == '\n'.code) {
                    val text = current.toString()
                    if (text.startsWith("000000")) first = text
                    if (text.isNotEmpty() && text[0].isDigit()) last = text
                    current.setLength(0)
                } else if (current.length < 200) {
                    current.append(b.toChar())
                }
            }
        }
        parts.writeTo(sink)
        assertTrue(size > 19_600_000)
        assertTrue(first.startsWith("000000 "))
        assertTrue(last.startsWith("199999 "))
        assertEquals(0, parts.missing)
        dir.deleteRecursively()
    }

    private fun session(kind: String, id: String, startedAtMs: Long): BpSessionLog {
        val r = BpSessionRecorder(id, kind, startedAtMs) { startedAtMs }
        r.stream("PPG_ON_DEMAND", "PPG_GREEN", "PPG_IR").apply {
            append(startedAtMs * 1_000_000, 1200f, 800f)
            append(startedAtMs * 1_000_000 + 10_000_000, 1210f, Float.NaN)
        }
        return r.build()
    }

    @Test
    fun theZipHasEverySessionAsCsvOnce() = runTest {
        val root = File(context.cacheDir, "zip-test").apply {
            deleteRecursively()
            mkdirs()
        }
        val t = 1_790_000_000_000
        // On the watch side (moved to the phone): an ECG and the copy of blood-pressure session A.
        val watchSessions = listOf(session("ecg", "ecg00001-x", t), session("bp", "aaaaaaaa-1111", t + 60_000)).map { log ->
            File(root, RawSessions.fileName(log.header.startedAtMs, log.header.kind, log.header.id)).apply { writeBytes(log.encode()) }
        }
        // The phone's own BP sessions: A again (same id) and B, which only the phone has.
        val bp = File(root, "bp").apply { mkdirs() }
        File(bp, "aaaaaaaa-1111.hlbp").writeBytes(session("measure", "aaaaaaaa-1111", t + 60_000).encode())
        File(bp, "bbbbbbbb-2222.hlbp").writeBytes(session("measure", "bbbbbbbb-2222", t + 120_000).encode())

        val exporter = PhoneLogExporter(context, SettingsRepository(context), UpdateRepository(context, "1.0.0"), RemoteLogs(send = { false }), bpSessions = bp)
        val bytes = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bytes).use { zip ->
            exporter.writeAll(
                zip,
                PhoneLogExporter.LogParts("phone head\n", emptyList(), null),
                PhoneLogExporter.WatchParts(PhoneLogExporter.LogParts("watch head\n", emptyList(), null), watchSessions),
            )
        }
        val entries = linkedMapOf<String, String>()
        java.util.zip.ZipInputStream(bytes.toByteArray().inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entries[it.name] = zip.readBytes().decodeToString() }
        }
        assertTrue(entries.getValue("phone.log").startsWith("phone head"))
        assertTrue(entries.getValue("watch.log").startsWith("watch head"))
        val folders = entries.keys.filter { it.startsWith("sessions/") }.map { it.split('/')[1] }.distinct()
        // ECG, BP session A once, and B from the phone.
        assertEquals(3, folders.size)
        assertEquals(1, folders.count { it.endsWith("-aaaaaaaa") })
        assertTrue(folders.any { it.endsWith("-bbbbbbbb") && it.contains("-bp-") })
        val csv = entries.getValue("sessions/${folders.first { it.contains("-ecg-") }}/PPG_ON_DEMAND.csv").trim().lines()
        assertEquals("timestampNs,time,PPG_GREEN,PPG_IR", csv[0])
        assertEquals(3, csv.size)
        assertTrue(csv[2].endsWith(",1210,"))
        assertTrue(entries.getValue("sessions/${folders.first()}/header.json").contains("\"startedAtMs\""))
    }

    @Test
    fun betaOnByDefaultStableAskedAndSettingsFollow() = runTest {
        val settings = SettingsRepository(context)
        val sent = java.util.concurrent.CopyOnWriteArrayList<MonitorSettings>()
        val stable = DiagnosticsRepository(context, settings, UpdateRepository(context, "1.0.0"), "1.0.0", RemoteLogs(send = { true }), sendSettings = { sent += it })
        stable.current().let {
            assertFalse(it.enabled)
            assertTrue(it.shouldAsk)
        }
        stable.start(backgroundScope)
        testScheduler.runCurrent()
        assertFalse(settings.current().diagnosticLogs)

        stable.setChoice(true)
        // DataStore writes on a real thread: wait (in real time) for the synced settings to follow,
        // and for them to be sent to the watch (that comes a moment after the write).
        withContext(Dispatchers.Default) {
            withTimeout(10_000) {
                settings.monitor.first { it.diagnosticLogs }
                while (sent.none { it.diagnosticLogs }) kotlinx.coroutines.delay(10)
            }
        }
        assertTrue(sent.last().diagnosticLogs)
        assertFalse(stable.current().shouldAsk)

        // A beta build keeps logs without asking, unless the user said no.
        val beta = DiagnosticsRepository(context, settings, UpdateRepository(context, "1.1.0-beta.1"), "1.1.0-beta.1", RemoteLogs(send = { true }), sendSettings = {})
        beta.setChoice(false)
        assertFalse(beta.current().enabled)
    }
}
