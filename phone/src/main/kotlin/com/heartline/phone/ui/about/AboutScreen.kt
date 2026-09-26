// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.ui.about

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.heartline.phone.R
import com.heartline.phone.legal.BundledDoc
import com.heartline.phone.ui.components.CardRow
import com.heartline.phone.ui.components.CardTitle
import com.heartline.phone.ui.components.IconBadge
import com.heartline.phone.ui.components.ReachabilityScaffold
import com.heartline.phone.ui.components.RoundedCard
import com.heartline.phone.ui.components.gutter
import com.heartline.phone.ui.theme.HeartlineTheme
import com.heartline.shared.AppInfo

/** Intended use, disclaimer, the legal documents, source code and open-source notices. */
@Composable
fun AboutScreen(versionName: String, onBack: (() -> Unit)? = null, onOpenDoc: (BundledDoc) -> Unit = {}) {
    val colors = HeartlineTheme.colors
    val uri = LocalUriHandler.current
    fun open(url: String) {
        runCatching { uri.openUri(url) }
    }
    ReachabilityScaffold(title = stringResource(R.string.settings_about), subtitle = stringResource(R.string.settings_version, versionName), onBack = onBack) {
        listOf(
            R.string.about_use_title to R.string.about_use_body,
            R.string.settings_disclaimer to R.string.about_disclaimer_body,
            R.string.about_privacy_title to R.string.about_privacy_body,
        ).forEach { (title, body) ->
            item {
                RoundedCard(Modifier.gutter()) {
                    CardTitle(stringResource(title))
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = colors.onBackground)
                }
            }
        }
        item {
            RoundedCard(Modifier.gutter(), contentPadding = 0.dp) {
                val rows: List<Triple<String, ImageVector, () -> Unit>> = listOf(
                    Triple(stringResource(R.string.whats_new_title), Icons.Rounded.NewReleases) { onOpenDoc(BundledDoc.CHANGELOG) },
                    Triple(stringResource(R.string.legal_terms), Icons.AutoMirrored.Rounded.Article) { onOpenDoc(BundledDoc.TERMS) },
                    Triple(stringResource(R.string.legal_privacy), Icons.Rounded.PrivacyTip) { onOpenDoc(BundledDoc.PRIVACY) },
                    Triple(stringResource(R.string.settings_disclaimer), Icons.Rounded.HealthAndSafety) { onOpenDoc(BundledDoc.DISCLAIMER) },
                )
                rows.forEachIndexed { i, (title, icon, action) ->
                    CardRow(title, leading = { IconBadge(icon, colors.onSurfaceVariant) }, showDivider = i < rows.lastIndex, onClick = action)
                }
            }
        }
        item {
            RoundedCard(Modifier.gutter(), contentPadding = 0.dp) {
                CardRow(
                    stringResource(R.string.about_source_code),
                    subtitle = AppInfo.REPO_URL.removePrefix("https://"),
                    leading = { IconBadge(Icons.Rounded.Code, colors.primary) },
                    showDivider = true,
                    onClick = { open(AppInfo.REPO_URL) },
                )
                CardRow(
                    stringResource(R.string.about_report_issue),
                    leading = { IconBadge(Icons.Rounded.BugReport, colors.onSurfaceVariant) },
                    showDivider = true,
                    onClick = { open(AppInfo.ISSUES_URL) },
                )
                CardRow(
                    stringResource(R.string.about_license, AppInfo.LICENSE_NAME),
                    leading = { IconBadge(Icons.Rounded.Gavel, colors.onSurfaceVariant) },
                    onClick = { open("${AppInfo.REPO_URL}/blob/main/LICENSE") },
                )
            }
        }
        item {
            RoundedCard(Modifier.gutter()) {
                CardTitle(stringResource(R.string.settings_licenses))
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.about_licenses_body), style = MaterialTheme.typography.bodyMedium, color = colors.onBackground)
            }
        }
    }
}
