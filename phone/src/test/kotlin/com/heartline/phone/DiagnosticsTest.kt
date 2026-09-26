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
import com.heartline.shared.hr.MonitorSettings
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

    @Test
    fun keptLogIsRedactedAndRawOnlyWhenDetailed() = runTest {
        HLog.init(context)
        HLog.setRedactor(Redactor(listOf("Sara")))
        HLog.configure(diagnosticLogs = true, detailedLogsUntilMs = 0)
        HLog.i("Heartline/Test", "hello from Sara")
        HLog.i("Heartline/EcgRaw", "raw batch 1")
        HLog.configure(diagnosticLogs = true, detailedLogsUntilMs = System.currentTimeMillis() + 60_000)
        HLog.i("Heartline/EcgRaw", "raw batch 2")

        val exporter = PhoneLogExporter(context, SettingsRepository(context), UpdateRepository(context, "1.0.0"), RemoteLogs(send = { false }))
        val phone = exporter.phoneLog()
        assertTrue(phone.contains("Heartline phone"))
        assertTrue(phone.contains("hello from <name>"))
        assertFalse(phone.contains("Sara"))
        assertFalse(phone.contains("raw batch 1"))
        assertTrue(phone.contains("raw batch 2"))

        assertTrue(exporter.watchLog(null).contains("The watch did not answer"))
        assertEquals("watch file", exporter.watchLog("watch file".encodeToByteArray()))

        HLog.configure(diagnosticLogs = false, detailedLogsUntilMs = 0)
        HLog.flush()
        assertEquals(0L, HLog.sizeBytes())
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

        stable.setDetailed(true)
        assertTrue(settings.current().detailedLogsUntilMs > System.currentTimeMillis())

        // A beta build keeps logs without asking, unless the user said no.
        val beta = DiagnosticsRepository(context, settings, UpdateRepository(context, "1.1.0-beta.1"), "1.1.0-beta.1", RemoteLogs(send = { true }), sendSettings = {})
        beta.setChoice(false)
        assertFalse(beta.current().enabled)
    }
}
