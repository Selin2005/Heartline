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
        assertFalse(watch.reached)
        assertTrue(watch.text().contains("The watch did not answer"))

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

    @Test
    fun betaOnByDefaultStableAskedAndSettingsFollow() = runTest {
        val settings = SettingsRepository(context)
        val sent = mutableListOf<MonitorSettings>()
        val stable = DiagnosticsRepository(context, settings, UpdateRepository(context, "1.0.0"), "1.0.0", RemoteLogs(send = { true }), sendSettings = { sent += it })
        stable.current().let {
            assertFalse(it.enabled)
            assertTrue(it.shouldAsk)
        }
        stable.start(backgroundScope)
        testScheduler.runCurrent()
        assertFalse(settings.current().diagnosticLogs)

        stable.setChoice(true)
        // DataStore writes on a real thread: wait (in real time) for the synced settings to follow.
        withContext(Dispatchers.Default) { withTimeout(10_000) { settings.monitor.first { it.diagnosticLogs } } }
        assertTrue(sent.last().diagnosticLogs)
        assertFalse(stable.current().shouldAsk)

        // A beta build keeps logs without asking, unless the user said no.
        val beta = DiagnosticsRepository(context, settings, UpdateRepository(context, "1.1.0-beta.1"), "1.1.0-beta.1", RemoteLogs(send = { true }), sendSettings = {})
        beta.setChoice(false)
        assertFalse(beta.current().enabled)
    }
}
