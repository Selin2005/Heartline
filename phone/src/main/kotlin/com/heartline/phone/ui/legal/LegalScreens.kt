// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.ui.legal

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.heartline.phone.R
import com.heartline.phone.legal.BundledDoc
import com.heartline.phone.ui.components.CardRow
import com.heartline.phone.ui.components.IconBadge
import com.heartline.phone.ui.components.MarkdownBlock
import com.heartline.phone.ui.components.PillButton
import com.heartline.phone.ui.components.ReachabilityScaffold
import com.heartline.phone.ui.components.RoundedCard
import com.heartline.phone.ui.components.gutter
import com.heartline.phone.ui.theme.HeartlineTheme
import com.heartline.shared.text.Markdown
import com.heartline.shared.text.MdBlock

/**
 * The first screen of the app, and again whenever the terms change: the user must accept the
 * Terms of Use and the Privacy Policy, and confirm Heartline isn't a medical device.
 */
@Composable
fun TermsGate(updated: Boolean, onAccept: () -> Unit, onDecline: () -> Unit) {
    var reading by rememberSaveable { mutableStateOf<String?>(null) }
    val doc = BundledDoc.fromRoute(reading)
    if (doc != null) {
        BackHandler { reading = null }
        LegalDocumentScreen(doc, onBack = { reading = null }, onOpenDoc = { reading = it.route })
    } else {
        TermsScreen(updated, onRead = { reading = it.route }, onAccept = onAccept, onDecline = onDecline)
    }
}

@Composable
fun TermsScreen(
    updated: Boolean,
    onRead: (BundledDoc) -> Unit = {},
    onAccept: () -> Unit = {},
    onDecline: () -> Unit = {},
    initiallyChecked: Boolean = false,
) {
    val colors = HeartlineTheme.colors
    var terms by rememberSaveable { mutableStateOf(initiallyChecked) }
    var privacy by rememberSaveable { mutableStateOf(initiallyChecked) }
    var medical by rememberSaveable { mutableStateOf(initiallyChecked) }
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(32.dp))
            Text(
                stringResource(if (updated) R.string.terms_updated_title else R.string.terms_title),
                style = MaterialTheme.typography.displaySmall,
                color = colors.onBackground,
            )
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.terms_body), style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))
            RoundedCard(contentPadding = 0.dp) {
                CardRow(
                    stringResource(R.string.legal_terms),
                    subtitle = stringResource(R.string.terms_read),
                    leading = { IconBadge(Icons.AutoMirrored.Rounded.Article, colors.primary) },
                    showDivider = true,
                    onClick = { onRead(BundledDoc.TERMS) },
                )
                CardRow(
                    stringResource(R.string.legal_privacy),
                    subtitle = stringResource(R.string.terms_read),
                    leading = { IconBadge(Icons.Rounded.PrivacyTip, colors.primary) },
                    onClick = { onRead(BundledDoc.PRIVACY) },
                )
            }
            Spacer(Modifier.height(16.dp))
            Check(terms, stringResource(R.string.terms_check_terms)) { terms = it }
            Check(privacy, stringResource(R.string.terms_check_privacy)) { privacy = it }
            Check(medical, stringResource(R.string.terms_check_medical)) { medical = it }
            Spacer(Modifier.height(16.dp))
        }
        PillButton(
            stringResource(R.string.terms_accept),
            onClick = { if (terms && privacy && medical) onAccept() },
            color = if (terms && privacy && medical) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.4f),
        )
        TextButton(onClick = onDecline, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.terms_decline), color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun Check(checked: Boolean, text: String, onChange: (Boolean) -> Unit) {
    val colors = HeartlineTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Checkbox) { onChange(!checked) }
            .padding(vertical = 6.dp),
    ) {
        Checkbox(checked, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = colors.primary))
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = colors.onBackground)
    }
}

/** A bundled document (terms, privacy policy, disclaimer, changelog) rendered in the app. */
@Composable
fun LegalDocumentScreen(doc: BundledDoc, onBack: (() -> Unit)?, onOpenDoc: (BundledDoc) -> Unit = {}, text: String? = null) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val blocks = remember(doc, text) {
        Markdown.parse(text ?: doc.read(context)).let { if (it.firstOrNull() is MdBlock.Heading) it.drop(1) else it }
    }
    val onLink: (String) -> Unit = { href ->
        val target = BundledDoc.fromLink(href)
        if (target != null && !href.startsWith("http")) onOpenDoc(target) else runCatching { uri.openUri(Markdown.resolve(href, doc.repoDir)) }
    }
    ReachabilityScaffold(title = stringResource(doc.title), onBack = onBack) {
        blocks.forEachIndexed { i, block ->
            item {
                Column(Modifier.gutter().padding(horizontal = 8.dp, vertical = if (block is MdBlock.Heading && i > 0) 8.dp else 4.dp)) {
                    MarkdownBlock(block, onLink)
                }
            }
        }
    }
}
