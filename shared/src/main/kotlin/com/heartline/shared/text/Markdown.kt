// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.text

import com.heartline.shared.AppInfo

/** A run of text inside a block: plain, bold, italic, inline code, or a link. */
data class Span(
    val text: String,
    val bold: Boolean = false,
    val code: Boolean = false,
    val link: String? = null,
    val italic: Boolean = false
)

sealed interface MdBlock {
    data class Heading(val level: Int, val spans: List<Span>) : MdBlock

    data class Paragraph(val spans: List<Span>) : MdBlock

    /** [marker] is "•" for bullets or "3." for numbered items; [depth] 0 for top-level items. */
    data class Item(val marker: String, val spans: List<Span>, val depth: Int = 0) : MdBlock

    data class Table(val header: List<List<Span>>, val rows: List<List<List<Span>>>) : MdBlock

    data class Code(val text: String) : MdBlock
}

/**
 * The small Markdown subset the app shows: the legal documents, the changelog and the release
 * notes. Headings, paragraphs, bullet and numbered lists, tables, fenced code, **bold**, *italic*,
 * `code` and [links](url). Like GitHub, it hides HTML comments (the release workflow's markers,
 * such as `<!-- notes -->`) and shows a > quote as its text.
 */
object Markdown {
    private val heading = Regex("^(#{1,6})\\s+(.*)$")
    private val bullet = Regex("^(\\s*)[-*+]\\s+(.*)$")
    private val numbered = Regex("^(\\s*)(\\d+)[.)]\\s+(.*)$")
    private val rule = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")
    private val tableSeparator = Regex("^\\s*\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?\\s*$")
    private val inline = Regex(
        "\\*\\*(.+?)\\*\\*|`([^`]+)`|\\[([^\\]]+)]\\(([^)\\s]+)\\)|<(https?://[^>]+)>|(https?://[^\\s)]+[^\\s).,;])" +
            "|(?<![*\\w])\\*(?![\\s*])([^*]+?)(?<!\\s)\\*(?![*\\w])|(?<![_\\w])_(?![\\s_])([^_]+?)(?<!\\s)_(?![_\\w])"
    )
    private val comment = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    private val quote = Regex("^\\s{0,3}>\\s?")

    fun parse(markdown: String): List<MdBlock> {
        val blocks = mutableListOf<MdBlock>()
        // HTML comments are hidden, as on GitHub (also across lines); a quote shows as its text.
        val lines = comment.replace(markdown.replace("\r\n", "\n"), "").lines().map { quote.replaceFirst(it, "") }
        val paragraph = mutableListOf<String>()
        fun flush() {
            if (paragraph.isNotEmpty()) blocks += MdBlock.Paragraph(spans(paragraph.joinToString(" ") { it.trim() }))
            paragraph.clear()
        }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.isBlank() || rule.matches(line) -> flush()
                line.trimStart().startsWith("```") -> {
                    flush()
                    val code = mutableListOf<String>()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith("```")) code += lines[i++]
                    blocks += MdBlock.Code(code.joinToString("\n"))
                }
                heading.matches(line) -> {
                    flush()
                    val m = heading.find(line)!!
                    blocks += MdBlock.Heading(m.groupValues[1].length, spans(m.groupValues[2].trim()))
                }
                line.trimStart().startsWith("|") && i + 1 < lines.size && tableSeparator.matches(lines[i + 1]) -> {
                    flush()
                    val header = cells(line)
                    val rows = mutableListOf<List<List<Span>>>()
                    i += 2
                    while (i < lines.size && lines[i].trimStart().startsWith("|")) rows += cells(lines[i++])
                    blocks += MdBlock.Table(header, rows)
                    continue
                }
                bullet.matches(line) || numbered.matches(line) -> {
                    flush()
                    val b = bullet.find(line)
                    val n = numbered.find(line)
                    val indent = b?.groupValues?.get(1) ?: n!!.groupValues[1]
                    val marker = if (b != null) "•" else "${n!!.groupValues[2]}."
                    val (text, end) = continued(lines, i, b?.groupValues?.get(2) ?: n!!.groupValues[3])
                    blocks += MdBlock.Item(marker, spans(text), depth(indent))
                    i = end
                }
                else -> paragraph += line
            }
            i++
        }
        flush()
        return blocks
    }

    /** Inline formatting of one line or paragraph. */
    fun spans(text: String): List<Span> {
        val out = mutableListOf<Span>()
        var last = 0
        for (m in inline.findAll(text)) {
            if (m.range.first > last) out += Span(text.substring(last, m.range.first))
            val (bold, code, label, href, angle, bare) = m.destructured
            val italic = m.groupValues[7].ifEmpty { m.groupValues[8] }
            out += when {
                italic.isNotEmpty() -> Span(italic, italic = true)
                bold.isNotEmpty() -> Span(bold, bold = true)
                code.isNotEmpty() -> Span(code, code = true)
                label.isNotEmpty() -> Span(label.replace("**", ""), link = href)
                angle.isNotEmpty() -> Span(angle, link = angle)
                else -> Span(bare, link = bare)
            }
            last = m.range.last + 1
        }
        if (last < text.length) out += Span(text.substring(last))
        return out.filter { it.text.isNotEmpty() }
    }

    /** Plain text of [spans], for accessibility and tests. */
    fun plain(spans: List<Span>) = spans.joinToString("") { it.text }

    /**
     * Makes a link from a document in [baseDir] of the repository absolute: relative links point at
     * the file on GitHub; web links are kept.
     */
    fun resolve(href: String, baseDir: String): String {
        if (href.startsWith("http://") || href.startsWith("https://") || href.startsWith("mailto:")) return href
        val parts = (baseDir.split('/') + href.substringBefore('#').split('/')).filter { it.isNotEmpty() && it != "." }
        val path = ArrayDeque<String>()
        for (p in parts) if (p == "..") path.removeLastOrNull() else path.addLast(p)
        val anchor = href.substringAfter('#', "").let { if (it.isEmpty()) "" else "#$it" }
        return "${AppInfo.REPO_URL}/blob/main/${path.joinToString("/")}$anchor"
    }

    private fun cells(line: String): List<List<Span>> = line.trim().removePrefix("|").removeSuffix("|").split("|").map { spans(it.trim()) }

    private fun depth(indent: String) = indent.replace("\t", "    ").length / 2

    /** A list item's text plus any indented continuation lines; returns the text and the last line used. */
    private fun continued(lines: List<String>, start: Int, first: String): Pair<String, Int> {
        var text = first
        var i = start
        while (i + 1 < lines.size) {
            val next = lines[i + 1]
            if (next.isBlank() || !next.startsWith("  ") || bullet.matches(next) || numbered.matches(next)) break
            text += " " + next.trim()
            i++
        }
        return text to i
    }
}
