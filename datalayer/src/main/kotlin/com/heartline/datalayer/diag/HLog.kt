// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.datalayer.diag

import android.content.Context
import android.os.Process
import android.util.Log
import com.heartline.shared.diag.DiagnosticsPolicy
import com.heartline.shared.diag.LogLine
import com.heartline.shared.diag.Redactor
import com.heartline.shared.diag.RollingLog
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The apps' logger. Every call goes to logcat as before, and, while diagnostic logs are on
 * ([configure]), also to a small rolling file on the device that the user can export from
 * Settings. Raw sensor tags only reach the file in detailed mode. Writing happens on one
 * background thread in batches, so it costs next to nothing on the watch.
 */
object HLog {
    /** Tags with raw sensor values: in the file only while detailed logging is on. */
    private val RAW_TAGS = setOf("Heartline/EcgRaw", "Heartline/EcgRec", "Heartline/BiaRaw", "Heartline/QuickRaw")
    private const val FLUSH_MS = 2_000L
    private const val MAX_BUFFER = 32 * 1024

    private val writer = Executors.newSingleThreadScheduledExecutor { Thread(it, "HLog").apply { isDaemon = true } }
    private val buffer = StringBuilder()

    @Volatile private var file: RollingLog? = null

    @Volatile private var persistent = false

    @Volatile private var detailedUntilMs = 0L

    @Volatile private var redactor: Redactor = Redactor.NONE

    /** Called once from Application.onCreate. Logging to logcat works before and without it. */
    fun init(context: Context) {
        if (file != null) return
        file = RollingLog(File(context.filesDir, "logs"))
        writer.scheduleWithFixedDelay(::flushNow, FLUSH_MS, FLUSH_MS, TimeUnit.MILLISECONDS)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            e("Heartline/Crash", "FATAL EXCEPTION in thread ${thread.name}", error)
            flush()
            previous?.uncaughtException(thread, error)
        }
    }

    /** From the synced settings: keep a log file at all, and until when to include raw sensor values. */
    fun configure(diagnosticLogs: Boolean, detailedLogsUntilMs: Long) {
        detailedUntilMs = detailedLogsUntilMs
        if (!diagnosticLogs && (persistent || sizeBytes() > 0)) clear() // turning it off frees the space right away
        persistent = diagnosticLogs
    }

    /** The user's names and birth date are replaced before anything is written or exported. */
    fun setRedactor(value: Redactor) {
        redactor = value
    }

    val isKeeping: Boolean get() = persistent

    fun d(tag: String, message: String, error: Throwable? = null): Int = Log.d(tag, message, error).also { record('D', tag, message, error) }

    fun v(tag: String, message: String, error: Throwable? = null): Int = Log.v(tag, message, error).also { record('V', tag, message, error) }

    fun i(tag: String, message: String, error: Throwable? = null): Int = Log.i(tag, message, error).also { record('I', tag, message, error) }

    fun w(tag: String, message: String, error: Throwable? = null): Int = Log.w(tag, message, error).also { record('W', tag, message, error) }

    fun e(tag: String, message: String, error: Throwable? = null): Int = Log.e(tag, message, error).also { record('E', tag, message, error) }

    private fun record(level: Char, tag: String, message: String, error: Throwable?) {
        if (!persistent || file == null) return
        val now = System.currentTimeMillis()
        if (tag in RAW_TAGS && !DiagnosticsPolicy.detailed(true, detailedUntilMs, now)) return
        val line = LogLine.format(now, level, tag, message, error)
        writer.execute {
            buffer.append(redactor.redact(line))
            if (level == 'W' || level == 'E' || buffer.length > MAX_BUFFER) flushNow()
        }
    }

    private fun flushNow() {
        if (buffer.isEmpty()) return
        val text = buffer.toString()
        buffer.setLength(0)
        runCatching { file?.append(text) }.onFailure { Log.w("Heartline/Log", "log write failed", it) }
    }

    /** Waits (briefly) until everything logged so far is on disk. */
    fun flush() {
        runCatching { writer.submit(::flushNow).get(2, TimeUnit.SECONDS) }
    }

    /** The kept log, oldest first. */
    fun read(): String {
        flush()
        return runCatching { writer.submit<String> { file?.readAll().orEmpty() }.get(10, TimeUnit.SECONDS) }.getOrDefault("")
    }

    fun sizeBytes(): Long = file?.sizeBytes() ?: 0

    fun clear() {
        writer.execute {
            buffer.setLength(0)
            file?.clear()
        }
    }

    /** This process's logcat buffer (apps may only read their own), redacted. */
    fun processLogcat(): String = runCatching {
        val process = ProcessBuilder("logcat", "-d", "-v", "threadtime", "--pid=${Process.myPid()}").redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor(5, TimeUnit.SECONDS)
        redactor.redact(text)
    }.getOrElse { "(logcat unavailable: ${it.message})" }
}
