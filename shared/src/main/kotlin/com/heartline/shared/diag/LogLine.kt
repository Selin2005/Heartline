// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.diag

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One log entry, formatted like `logcat -v threadtime`: "09-26 14:03:07.123  I Heartline/ECG: message". */
object LogLine {
    const val MAX_MESSAGE = 4_000
    private val time = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")

    fun format(
        timeMs: Long,
        level: Char,
        tag: String,
        message: String,
        error: Throwable? = null,
        zone: ZoneId = ZoneId.systemDefault()
    ): String {
        val stamp = time.format(Instant.ofEpochMilli(timeMs).atZone(zone))
        val text = if (message.length >
            MAX_MESSAGE
        ) {
            message.take(MAX_MESSAGE) + " …(${message.length - MAX_MESSAGE} more chars)"
        } else {
            message
        }
        val trace = error?.let { "\n" + it.stackTraceToString().trimEnd() }.orEmpty()
        return "$stamp  $level $tag: $text$trace\n"
    }
}
