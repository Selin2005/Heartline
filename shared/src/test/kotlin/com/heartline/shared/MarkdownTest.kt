// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared

import com.heartline.shared.text.Changelog
import com.heartline.shared.text.Markdown
import com.heartline.shared.text.MdBlock
import com.heartline.shared.text.Span
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {
    @Test
    fun blocks() {
        val md = """
            # Title

            First line
            continues here.

            - one
            - **two** item
              wrapped
            1. first
            ---
            | A | B |
            |---|:-:|
            | x | [y](https://e.org) |
        """.trimIndent()
        val blocks = Markdown.parse(md)
        assertEquals(MdBlock.Heading(1, listOf(Span("Title"))), blocks[0])
        assertEquals(MdBlock.Paragraph(listOf(Span("First line continues here."))), blocks[1])
        assertEquals(MdBlock.Item("•", listOf(Span("one"))), blocks[2])
        assertEquals(MdBlock.Item("•", listOf(Span("two", bold = true), Span(" item wrapped"))), blocks[3])
        assertEquals("1.", (blocks[4] as MdBlock.Item).marker)
        val table = blocks[5] as MdBlock.Table
        assertEquals(listOf(listOf(Span("A")), listOf(Span("B"))), table.header)
        assertEquals(Span("y", link = "https://e.org"), table.rows.single()[1].single())
    }

    @Test
    fun inlineLinks() {
        val spans = Markdown.spans("See `code`, <https://a.io> and https://b.io/x.")
        assertEquals(
            listOf(
                Span("See "),
                Span("code", code = true),
                Span(", "),
                Span("https://a.io", link = "https://a.io"),
                Span(" and "),
                Span("https://b.io/x", link = "https://b.io/x"),
                Span(".")
            ),
            spans
        )
    }

    @Test
    fun relativeLinksPointAtTheRepository() {
        assertEquals("${AppInfo.REPO_URL}/blob/main/LICENSE", Markdown.resolve("../LICENSE", "legal"))
        assertEquals("${AppInfo.REPO_URL}/blob/main/legal/PRIVACY_POLICY.md#3", Markdown.resolve("PRIVACY_POLICY.md#3", "legal"))
        assertEquals("https://x.org", Markdown.resolve("https://x.org", "legal"))
    }

    @Test
    fun legalDocumentsParse() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
        for (name in listOf("TERMS_OF_USE.md", "PRIVACY_POLICY.md", "MEDICAL_DISCLAIMER.md")) {
            val blocks = Markdown.parse(File(root, "legal/$name").readText())
            assertTrue(name, blocks.first() is MdBlock.Heading)
            assertTrue(name, blocks.size > 5)
        }
    }

    @Test
    fun changelogEntries() {
        val md = """
            # Changelog

            ## 1.1.0-beta.2 — 2026-10-02
            ### Fixed
            - Thing

            ## [1.0.0] - 2026-09-30
            First release.
        """.trimIndent()
        val entries = Changelog.parse(md)
        assertEquals(listOf("1.1.0-beta.2", "1.0.0"), entries.map { it.version })
        assertEquals("2026-10-02", entries[0].date)
        assertEquals("### Fixed\n- Thing", entries[0].notes)
        assertEquals("First release.", Changelog.entryFor(md, "1.0.0")!!.notes)
        assertEquals("1.0.0", Changelog.entryFor(md, "1.0.0-dev.7")!!.version)
        assertNull(Changelog.entryFor(md, "2.0.0"))
    }

    @Test
    fun releaseNotesHideTheWorkflowMarkersAndShowQuotesAndItalics() {
        // As GitHub shows a release body: comments hidden, the quote and italics rendered.
        val body = """
            > **Development build:** the newest code, for early testers. Update from the app: *Settings → Updates*, on the *Development* update channel.

            <!-- notes -->
            ## Changes
            - Fix what a real watch log showed
            - Stress monitoring
            <!-- /notes -->

            SHA-256 checksums: SHA256SUMS. <!-- heartline-run: 37141321052 --> <!-- heartline-telegram: no -->
            <!--
            several lines
            -->
        """.trimIndent()
        val blocks = Markdown.parse(body)
        val text = blocks.joinToString("\n") { b ->
            when (b) {
                is MdBlock.Paragraph -> Markdown.plain(b.spans)
                is MdBlock.Heading -> Markdown.plain(b.spans)
                is MdBlock.Item -> Markdown.plain(b.spans)
                else -> ""
            }
        }
        assertTrue(text, "<!--" !in text && "-->" !in text && "heartline-run" !in text && "several lines" !in text)
        val intro = blocks.first() as MdBlock.Paragraph
        assertTrue(Markdown.plain(intro.spans).startsWith("Development build:"))
        assertEquals(listOf("Settings → Updates", "Development"), intro.spans.filter { it.italic }.map { it.text })
        assertTrue(intro.spans.first().bold)
        assertEquals("SHA-256 checksums: SHA256SUMS.", Markdown.plain((blocks.last() as MdBlock.Paragraph).spans).trim())
        assertEquals(2, blocks.count { it is MdBlock.Item })
        // A list marker, a multiplication and snake_case stay as they are.
        assertEquals("2 * 3 = 6 and heart_rate_bpm", Markdown.plain(Markdown.spans("2 * 3 = 6 and heart_rate_bpm")))
        assertEquals(listOf("emphasis"), Markdown.spans("an _emphasis_ here").filter { it.italic }.map { it.text })
    }
}
