// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.heartline.phone.ui.theme.HeartlineTheme
import com.heartline.shared.text.MdBlock
import com.heartline.shared.text.Span

/** Renders parsed Markdown (legal documents, release notes); links go to [onLink]. */
@Composable
fun MarkdownBlocks(blocks: List<MdBlock>, modifier: Modifier = Modifier, onLink: (String) -> Unit = {}) {
    Column(modifier) {
        blocks.forEachIndexed { i, block ->
            if (i > 0) Spacer(Modifier.padding(top = if (block is MdBlock.Heading) 16.dp else 8.dp))
            MarkdownBlock(block, onLink)
        }
    }
}

@Composable
fun MarkdownBlock(block: MdBlock, onLink: (String) -> Unit) {
    val colors = HeartlineTheme.colors
    val type = MaterialTheme.typography
    when (block) {
        is MdBlock.Heading -> Text(
            annotated(block.spans, onLink),
            style = when (block.level) {
                1 -> type.headlineSmall
                2 -> type.titleLarge
                else -> type.titleMedium
            },
            color = colors.onBackground,
        )
        is MdBlock.Paragraph -> Text(annotated(block.spans, onLink), style = type.bodyMedium, color = colors.onBackground)
        is MdBlock.Item -> Row(Modifier.padding(start = (block.depth * 16).dp)) {
            Text(block.marker, style = type.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.width(24.dp))
            Text(annotated(block.spans, onLink), style = type.bodyMedium, color = colors.onBackground)
        }
        is MdBlock.Table -> Column(
            Modifier
                .fillMaxWidth()
                .background(colors.surfaceVariant, RoundedCornerShape(12.dp))
                .padding(12.dp),
        ) {
            // Narrow screens: each row as "header: value" lines instead of columns.
            block.rows.forEachIndexed { r, row ->
                if (r > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp), color = colors.divider)
                row.forEachIndexed { c, cell ->
                    val label = block.header.getOrNull(c)?.let { h -> h.joinToString("") { it.text } }.orEmpty()
                    Text(
                        buildAnnotatedString {
                            if (c > 0 && label.isNotBlank()) withStyle(SpanStyle(color = colors.onSurfaceVariant)) { append("$label: ") }
                            append(annotated(cell, onLink, bold = c == 0))
                        },
                        style = type.bodySmall,
                        color = colors.onBackground,
                    )
                }
            }
        }
        is MdBlock.Code -> Text(
            block.text,
            style = type.bodySmall.merge(TextStyle(fontFamily = FontFamily.Monospace)),
            color = colors.onBackground,
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surfaceVariant, RoundedCornerShape(8.dp))
                .padding(12.dp),
        )
    }
}

@Composable
private fun annotated(spans: List<Span>, onLink: (String) -> Unit, bold: Boolean = false): AnnotatedString {
    val link = TextLinkStyles(SpanStyle(color = HeartlineTheme.colors.primary, textDecoration = TextDecoration.Underline))
    return buildAnnotatedString {
        for (span in spans) {
            val style = SpanStyle(
                fontWeight = if (span.bold || bold) FontWeight.SemiBold else null,
                fontFamily = if (span.code) FontFamily.Monospace else null,
            )
            val href = span.link
            if (href != null) {
                withLink(LinkAnnotation.Clickable(href, link) { onLink(href) }) { withStyle(style) { append(span.text) } }
            } else {
                withStyle(style) { append(span.text) }
            }
        }
    }
}
