// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.text

/** One version in CHANGELOG.md: `## 1.2.0-beta.1 — 2026-09-30`, followed by its notes. */
data class ChangelogEntry(val version: String, val date: String?, val notes: String)

object Changelog {
    private val header = Regex("^##\\s+\\[?v?([0-9][0-9A-Za-z.+-]*)]?\\s*(?:[-—–(]\\s*([0-9]{4}-[0-9]{2}-[0-9]{2})\\)?)?.*$")

    fun parse(markdown: String): List<ChangelogEntry> {
        val entries = mutableListOf<ChangelogEntry>()
        var version: String? = null
        var date: String? = null
        val body = StringBuilder()
        fun flush() {
            version?.let { entries += ChangelogEntry(it, date, body.toString().trim()) }
            body.clear()
        }
        for (line in markdown.replace("\r\n", "\n").lines()) {
            val m = header.find(line)
            if (m != null) {
                flush()
                version = m.groupValues[1]
                date = m.groupValues[2].ifEmpty { null }
            } else if (version != null) {
                body.appendLine(line)
            }
        }
        flush()
        return entries
    }

    /** The notes for [versionName] (a "-dev.N" build shows the notes of its base version, if any). */
    fun entryFor(markdown: String, versionName: String): ChangelogEntry? {
        val entries = parse(markdown)
        return entries.firstOrNull { it.version == versionName }
            ?: entries.firstOrNull { it.version == versionName.substringBefore("-dev") }
    }
}
