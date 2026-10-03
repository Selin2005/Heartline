// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.ui.update

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.heartline.phone.R
import com.heartline.phone.ui.components.CardRow
import com.heartline.phone.ui.components.CardTitle
import com.heartline.phone.ui.components.IconBadge
import com.heartline.phone.ui.components.MarkdownBlocks
import com.heartline.phone.ui.components.PillButton
import com.heartline.phone.ui.components.ReachabilityScaffold
import com.heartline.phone.ui.components.RoundedCard
import com.heartline.phone.ui.components.SectionHeader
import com.heartline.phone.ui.components.gutter
import com.heartline.phone.ui.settings.OneUiSwitch
import com.heartline.phone.ui.theme.HeartlineTheme
import com.heartline.phone.update.UpdateDownloadWorker
import com.heartline.phone.update.UpdatesUi
import com.heartline.shared.AppInfo
import com.heartline.shared.text.Markdown
import com.heartline.shared.update.AppVersion
import com.heartline.phone.ui.settings.ChoiceDialog
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

private val AppVersion.Channel.title: Int get() = when (this) {
    AppVersion.Channel.STABLE -> R.string.updates_channel_stable
    AppVersion.Channel.BETA -> R.string.updates_channel_beta
    AppVersion.Channel.DEV -> R.string.updates_channel_dev
}

private val AppVersion.Channel.description: Int get() = when (this) {
    AppVersion.Channel.STABLE -> R.string.updates_channel_stable_sub
    AppVersion.Channel.BETA -> R.string.updates_channel_beta_sub
    AppVersion.Channel.DEV -> R.string.updates_channel_dev_sub
}

private const val INSTALL_GUIDE = "${AppInfo.REPO_URL}/blob/main/docs/DEVICE_TESTING.md#2-install"

/** Settings → Updates: channel, automatic checks, the available release and the watch's version. */
@Composable
fun UpdatesScreen(
    ui: UpdatesUi,
    onBack: (() -> Unit)? = null,
    onCheck: () -> Unit = {},
    onInstall: () -> Unit = {},
    onCancelDownload: () -> Unit = {},
    onAutoCheck: (Boolean) -> Unit = {},
    onTrack: (AppVersion.Channel) -> Unit = {},
) {
    var choosing by remember { mutableStateOf(false) }
    if (choosing) {
        val tracks = listOf(AppVersion.Channel.STABLE, AppVersion.Channel.BETA, AppVersion.Channel.DEV)
        ChoiceDialog(
            stringResource(R.string.updates_channel),
            tracks.map { stringResource(it.title) to it },
            ui.prefs.track,
            onDismiss = { choosing = false },
            onSelect = onTrack,
        )
    }
    val colors = HeartlineTheme.colors
    val uri = LocalUriHandler.current
    fun open(url: String) {
        runCatching { uri.openUri(url) }
    }
    ReachabilityScaffold(title = stringResource(R.string.updates_title), subtitle = stringResource(R.string.settings_version, ui.installed), onBack = onBack) {
        if (!ui.enabled) {
            item {
                RoundedCard(Modifier.gutter()) {
                    Text(stringResource(R.string.updates_store), style = MaterialTheme.typography.bodyMedium, color = colors.onBackground)
                }
            }
            return@ReachabilityScaffold
        }
        item {
            val release = ui.available
            RoundedCard(Modifier.gutter()) {
                val ready = ui.ready
                val download = ui.downloading
                val (icon, tint, title) = when {
                    ready != null -> Triple(Icons.Rounded.DownloadDone, colors.primary, stringResource(R.string.updates_ready, ready))
                    download is UpdateDownloadWorker.State.Failed -> Triple(Icons.Rounded.ErrorOutline, colors.statusWarn, stringResource(R.string.updates_download_failed))
                    ui.checking -> Triple(Icons.Rounded.Sync, colors.onSurfaceVariant, stringResource(R.string.updates_checking))
                    release != null -> Triple(
                        Icons.Rounded.NewReleases,
                        colors.primary,
                        stringResource(if (release.isBeta) R.string.updates_available_beta else R.string.updates_available, release.version.toString()),
                    )
                    ui.error != null -> Triple(Icons.Rounded.ErrorOutline, colors.statusWarn, stringResource(R.string.updates_failed))
                    ui.upToDate -> Triple(Icons.Rounded.CheckCircle, colors.statusNormal, stringResource(R.string.updates_up_to_date))
                    else -> Triple(Icons.Rounded.Sync, colors.onSurfaceVariant, stringResource(R.string.updates_not_checked))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(icon, tint)
                    Spacer(Modifier.width(16.dp))
                    CardTitle(title)
                }
                (ui.error ?: (download as? UpdateDownloadWorker.State.Failed)?.message?.takeIf { it.isNotBlank() })?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                if (ready != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.updates_ready_sub), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                if (release != null && release.notes.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    val blocks = remember(release.notes) { Markdown.parse(release.notes) }
                    MarkdownBlocks(blocks, onLink = { open(it) })
                }
                Spacer(Modifier.height(16.dp))
                when {
                    ready != null -> PillButton(stringResource(R.string.updates_install_now), onClick = onInstall)
                    download is UpdateDownloadWorker.State.Running || download is UpdateDownloadWorker.State.Waiting -> {
                        DownloadProgress(download)
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = onCancelDownload, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.updates_cancel))
                        }
                    }
                    release != null -> {
                        PillButton(stringResource(if (download is UpdateDownloadWorker.State.Failed) R.string.updates_retry else R.string.updates_install), onClick = onInstall)
                        TextButton(onClick = { open(release.pageUrl.ifBlank { AppInfo.RELEASES_URL }) }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.updates_release_page))
                        }
                    }
                    else -> PillButton(stringResource(R.string.updates_check_now), onClick = onCheck)
                }
            }
        }
        item { SectionHeader(stringResource(R.string.updates_settings)) }
        item {
            RoundedCard(Modifier.gutter(), contentPadding = 0.dp) {
                CardRow(
                    stringResource(R.string.updates_auto),
                    subtitle = stringResource(R.string.updates_auto_sub),
                    leading = { IconBadge(Icons.Rounded.Sync, colors.onSurfaceVariant) },
                    trailing = { OneUiSwitch(ui.prefs.autoCheck, onAutoCheck) },
                    showDivider = ui.showsChannel,
                    onClick = { onAutoCheck(!ui.prefs.autoCheck) },
                )
                if (ui.showsChannel) {
                    CardRow(
                        stringResource(R.string.updates_channel),
                        subtitle = stringResource(ui.prefs.track.description),
                        leading = { IconBadge(Icons.Rounded.Science, colors.onSurfaceVariant) },
                        onClick = { choosing = true },
                    )
                }
            }
        }
        item { SectionHeader(stringResource(R.string.updates_watch)) }
        item {
            val watch = ui.prefs.watchVersion
            val target = ui.available ?: ui.latest
            RoundedCard(Modifier.gutter(), contentPadding = 0.dp) {
                CardRow(
                    stringResource(R.string.updates_watch_version, watch ?: stringResource(R.string.updates_watch_unknown)),
                    subtitle = when {
                        ui.watchBehind && target != null -> stringResource(R.string.updates_watch_behind, target.version.toString())
                        watch != null && target != null -> stringResource(R.string.updates_watch_current)
                        else -> stringResource(R.string.updates_watch_hint)
                    },
                    leading = { IconBadge(Icons.Rounded.Watch, if (ui.watchBehind) colors.primary else colors.onSurfaceVariant) },
                    showDivider = ui.watchBehind,
                )
                if (ui.watchBehind && target != null) {
                    target.watchApk?.let { apk ->
                        CardRow(stringResource(R.string.updates_watch_download), subtitle = apk.name, showDivider = true, onClick = { open(apk.url) })
                    }
                    CardRow(stringResource(R.string.updates_watch_guide), onClick = { open(INSTALL_GUIDE) })
                }
            }
        }
    }
}

/** The background download: progress and size, or waiting for a connection. */
@Composable
private fun DownloadProgress(download: UpdateDownloadWorker.State) {
    val colors = HeartlineTheme.colors
    val running = download as? UpdateDownloadWorker.State.Running
    val fraction = running?.fraction
    if (fraction != null) {
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth(), color = colors.primary)
    } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = colors.primary)
    }
    Spacer(Modifier.height(8.dp))
    val text = when {
        running != null && running.total > 0 -> stringResource(R.string.updates_downloading_size, megabytes(running.done), megabytes(running.total))
        running != null -> stringResource(R.string.updates_downloading)
        (download as UpdateDownloadWorker.State.Waiting).retrying -> stringResource(R.string.updates_waiting_network)
        else -> stringResource(R.string.updates_starting)
    }
    Text(text, style = MaterialTheme.typography.bodyMedium, color = colors.onBackground)
    Spacer(Modifier.height(4.dp))
    Text(stringResource(R.string.updates_downloading_hint), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
}

private fun megabytes(bytes: Long) = "%.1f".format(bytes / 1_048_576.0)
