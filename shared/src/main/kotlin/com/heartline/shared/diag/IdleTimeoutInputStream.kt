// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.diag

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Closes [input] once nothing arrived for [idleMs], so a stalled channel ends its read with an
 * [IOException] instead of holding the receiver: one raw session whose stream stopped coming
 * kept every later one waiting for 29 minutes, until the app was updated. A blocking read can't
 * be cancelled any other way.
 */
class IdleTimeoutInputStream(
    input: InputStream,
    private val idleMs: Long = 60_000L,
    private val clockMs: () -> Long = System::currentTimeMillis
) : FilterInputStream(input) {
    @Volatile private var lastByteMs = clockMs()

    @Volatile var timedOut = false
        private set

    private val check: ScheduledFuture<*> = WATCHDOG.scheduleWithFixedDelay(::checkIdle, CHECK_MS, CHECK_MS, TimeUnit.MILLISECONDS)

    internal fun checkIdle() {
        if (timedOut || clockMs() - lastByteMs < idleMs) return
        timedOut = true
        runCatching { `in`.close() }
    }

    override fun read(): Int {
        val n = try {
            if (timedOut) throw IOException("closed")
            super.read()
        } catch (e: IOException) {
            throw stalled(e)
        }
        if (n >= 0) lastByteMs = clockMs()
        return n
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = try {
            if (timedOut) throw IOException("closed")
            super.read(b, off, len)
        } catch (e: IOException) {
            throw stalled(e)
        }
        if (n > 0) lastByteMs = clockMs()
        return n
    }

    private fun stalled(e: IOException): IOException = if (timedOut) IOException("no data for $idleMs ms", e) else e

    override fun close() {
        check.cancel(false)
        super.close()
    }

    private companion object {
        const val CHECK_MS = 1_000L
        val WATCHDOG = Executors.newSingleThreadScheduledExecutor { Thread(it, "heartline-stream-watchdog").apply { isDaemon = true } }
    }
}
