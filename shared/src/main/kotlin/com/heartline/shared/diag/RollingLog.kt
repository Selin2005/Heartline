// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.diag

import java.io.File

/**
 * A log kept in two files in [dir]: `current.log` grows to [maxFileBytes], then becomes
 * `previous.log` and a new `current.log` starts. At most 2 × [maxFileBytes] is kept on disk.
 * Not thread-safe: callers write from one thread.
 */
class RollingLog(private val dir: File, private val maxFileBytes: Long = 4L * 1024 * 1024) {
    private val current = File(dir, "current.log")
    private val previous = File(dir, "previous.log")

    fun append(text: String) {
        dir.mkdirs()
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (current.exists() && current.length() + bytes.size > maxFileBytes) rotate()
        current.appendBytes(bytes)
    }

    private fun rotate() {
        previous.delete()
        current.renameTo(previous)
    }

    /** Everything kept, oldest first. */
    fun readAll(): String = buildString {
        for (file in listOf(previous, current)) if (file.exists()) append(file.readText(Charsets.UTF_8))
    }

    fun sizeBytes(): Long = listOf(previous, current).filter { it.exists() }.sumOf { it.length() }

    fun clear() {
        previous.delete()
        current.delete()
    }
}
