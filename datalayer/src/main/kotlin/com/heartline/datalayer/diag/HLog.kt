// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.datalayer.diag

import android.content.Context
import android.os.Process
import android.util.Log
import com.heartline.shared.diag.LogLine
import com.heartline.shared.diag.Redactor
import com.heartline.shared.diag.SegmentedLog
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The apps' logger. Every call goes to logcat as before, and, while diagnostic logs are on
 * ([configure]), also to a compressed, segmented log on the device ([SegmentedLog], raw sensor
 * values included) that the user can export from Settings. Writing happens on one background
 * thread in batches, so it costs next to nothing on the watch.
 */
object HLog {
    /**
     * Log budget on the watch and on the phone (compressed, about ten times as much text). The
     * watch's raw sensor sessions have 30 MB more, and both move to the phone as they're done.
     */
    const val WATCH_BUDGET_BYTES = 20L * 1024 * 1024
    const val PHONE_BUDGET_BYTES = 150L * 1024 * 1024

    private const val FLUSH_MS = 2_000L
    private const val MAX_BUFFER = 32 * 1024

    private val writer = Executors.newSingleThreadScheduledExecutor { Thread(it, "HLog").apply { isDaemon = true } }
    private val buffer = StringBuilder()

    @Volatile private var file: SegmentedLog? = null

    @Volatile private var persistent = false

    @Volatile private var redactor: Redactor = Redactor.NONE

    /**
     * Called once from Application.onCreate. Logging to logcat works before and without it.
     * [onSegment] gets each closed segment (on the logging thread; it must only hand it on).
     */
    fun init(context: Context, budgetBytes: Long, onSegment: (File) -> Unit = {}) {
        if (file != null) return
        file = SegmentedLog(File(context.filesDir, "logs"), budgetBytes, onSealed = onSegment).also { log -> writer.execute { log.migrate() } }
        writer.scheduleWithFixedDelay(::flushNow, FLUSH_MS, FLUSH_MS, TimeUnit.MILLISECONDS)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            e("Heartline/Crash", "FATAL EXCEPTION in thread ${thread.name}", error)
            flush()
            previous?.uncaughtException(thread, error)
        }
    }

    /** From the synced settings: keep a log on the device at all. */
    fun configure(diagnosticLogs: Boolean) {
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
        val line = LogLine.format(System.currentTimeMillis(), level, tag, message, error)
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

    /**
     * Writes out everything logged so far and closes the current segment: the segments then hold
     * the whole log, oldest first, ready to export one by one.
     */
    fun sealedSegments(): List<File> = runCatching {
        writer.submit<List<File>> {
            flushNow()
            file?.seal()
            file?.segments().orEmpty()
        }.get(30, TimeUnit.SECONDS)
    }.getOrDefault(emptyList())

    /** The closed segments now on disk, oldest first (the current one stays open). */
    fun segmentFiles(): List<File> = file?.segments().orEmpty()

    /** The last [maxBytes] of the log as text, for a phone app from before segmented export. */
    fun readRecent(maxBytes: Int): String {
        flush()
        return runCatching { writer.submit<String> { file?.readRecent(maxBytes).orEmpty() }.get(30, TimeUnit.SECONDS) }.getOrDefault("")
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
        val text = process.inputStream.bufferedReader().use { r -> r.lineSequence().filterNot(::isUiNoise).joinToString("\n", postfix = "\n") }
        process.waitFor(5, TimeUnit.SECONDS)
        redactor.redact(text)
    }.getOrElse { "(logcat unavailable: ${it.message})" }

    /** Drawing and input chatter from the UI toolkit, hundreds of lines per screen, never useful here. */
    private val uiNoise = Regex("""^\S+ \S+\s+\d+\s+\d+ [VDI] (View|VRI\[[^\]]*]@\w+|ViewRootImpl|InsetsController|InsetsSourceConsumer|BLASTBufferQueue\w*|InputTransport|InputMethodManager\w*|ImeTracker|ImeFocusController|WindowOnBackDispatcher|HWUI|AdrenoVK-\d+|SurfaceComposerClient|BufferQueueProducer|DecorView|qdgralloc|vulkan|NativeCustomFrequencyManager|HardwareRenderer|IDS_TAG|SnapAlloc|BBA2)\s*:""")

    internal fun isUiNoise(line: String) = uiNoise.containsMatchIn(line)
}
