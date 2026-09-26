// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.ui.diagnostics

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.heartline.phone.R
import com.heartline.phone.diag.DiagnosticsUi
import com.heartline.phone.diag.PhoneLogExporter
import com.heartline.phone.ui.components.CardRow
import com.heartline.phone.ui.components.CardTitle
import com.heartline.phone.ui.components.IconBadge
import com.heartline.phone.ui.components.PillButton
import com.heartline.phone.ui.components.ReachabilityScaffold
import com.heartline.phone.ui.components.RoundedCard
import com.heartline.phone.ui.components.SectionHeader
import com.heartline.phone.ui.components.gutter
import com.heartline.phone.ui.settings.OneUiSwitch
import com.heartline.phone.ui.theme.HeartlineTheme
import com.heartline.shared.AppInfo
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Settings → Help & diagnostics: keep logs, detailed mode, export both devices' logs, delete. */

/** Lets the user pick the folder for the two log files (remembering it for next time), then exports. */
@Composable
fun rememberLogFolderPicker(lastFolder: String?, onFolder: (Uri) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { folder ->
        if (folder != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(folder, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            onFolder(folder)
        }
    }
    return { launcher.launch(lastFolder?.let(Uri::parse)) }
}

@Composable
fun DiagnosticsScreen(
    ui: DiagnosticsUi,
    onBack: (() -> Unit)? = null,
    onEnabled: (Boolean) -> Unit = {},
    onDetailed: (Boolean) -> Unit = {},
    onExport: () -> Unit = {},
    onDelete: () -> Unit = {},
) {
    val colors = HeartlineTheme.colors
    val uri = LocalUriHandler.current
    ReachabilityScaffold(title = stringResource(R.string.diag_title), onBack = onBack) {
        item {
            RoundedCard(Modifier.gutter()) {
                Text(stringResource(R.string.diag_intro), style = MaterialTheme.typography.bodyMedium, color = colors.onBackground)
                Spacer(Modifier.height(16.dp))
                val step = ui.step
                val saved = ui.saved
                val error = ui.error
                when {
                    step != null -> {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = colors.primary)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(
                                when (step) {
                                    PhoneLogExporter.Step.PHONE -> R.string.diag_step_phone
                                    PhoneLogExporter.Step.WATCH -> R.string.diag_step_watch
                                    PhoneLogExporter.Step.SAVING -> R.string.diag_step_saving
                                },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    else -> {
                        if (saved != null) {
                            Result(Icons.Rounded.CheckCircle, colors.statusNormal, stringResource(R.string.diag_saved, saved.phoneFile, saved.watchFile))
                            if (!saved.watchReached) Result(Icons.Rounded.ErrorOutline, colors.statusWarn, stringResource(R.string.diag_watch_unreachable))
                            Spacer(Modifier.height(12.dp))
                        }
                        if (error != null) {
                            Result(Icons.Rounded.ErrorOutline, colors.statusWarn, stringResource(R.string.diag_failed, error))
                            Spacer(Modifier.height(12.dp))
                        }
                        PillButton(stringResource(R.string.diag_export), onClick = onExport)
                    }
                }
                if (!ui.enabled) {
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.diag_off_hint), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
        }
        item { SectionHeader(stringResource(R.string.diag_settings)) }
        item {
            RoundedCard(Modifier.gutter(), contentPadding = 0.dp) {
                CardRow(
                    stringResource(R.string.diag_keep),
                    subtitle = if (ui.enabled) stringResource(R.string.diag_keep_on, ui.keptKb) else stringResource(R.string.diag_keep_off),
                    leading = { IconBadge(Icons.Rounded.Description, colors.onSurfaceVariant) },
                    trailing = { OneUiSwitch(ui.enabled, onEnabled) },
                    showDivider = true,
                    onClick = { onEnabled(!ui.enabled) },
                )
                val detailed = ui.detailedUntilMs > 0
                CardRow(
                    stringResource(R.string.diag_detailed),
                    subtitle = when {
                        !ui.enabled -> stringResource(R.string.diag_detailed_needs_logs)
                        detailed -> stringResource(R.string.diag_detailed_until, formatTime(ui.detailedUntilMs))
                        else -> stringResource(R.string.diag_detailed_sub)
                    },
                    leading = { IconBadge(Icons.Rounded.Science, colors.onSurfaceVariant) },
                    trailing = { OneUiSwitch(detailed) { if (ui.enabled) onDetailed(it) } },
                    showDivider = true,
                    onClick = { if (ui.enabled) onDetailed(!detailed) },
                )
                CardRow(
                    stringResource(R.string.diag_delete),
                    leading = { IconBadge(Icons.Rounded.DeleteOutline, colors.onSurfaceVariant) },
                    showDivider = true,
                    onClick = onDelete,
                )
                CardRow(
                    stringResource(R.string.about_report_issue),
                    subtitle = stringResource(R.string.diag_report_sub),
                    leading = { IconBadge(Icons.Rounded.BugReport, colors.onSurfaceVariant) },
                    onClick = { runCatching { uri.openUri("${AppInfo.ISSUES_URL}/new/choose") } },
                )
            }
        }
    }
}

@Composable
private fun Result(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: androidx.compose.ui.graphics.Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        IconBadge(icon, tint, size = 32)
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = HeartlineTheme.colors.onBackground)
    }
}

private fun formatTime(ms: Long): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

/** Asked once of stable users (beta users keep logs by default). */
@Composable
fun DiagnosticsQuestion(onAnswer: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.diag_ask_title)) },
        text = { Text(stringResource(R.string.diag_ask_body)) },
        confirmButton = { TextButton(onClick = { onAnswer(true) }) { Text(stringResource(R.string.diag_ask_yes)) } },
        dismissButton = { TextButton(onClick = { onAnswer(false) }) { Text(stringResource(R.string.diag_ask_no)) } },
    )
}
