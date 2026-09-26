// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.heartline.phone.R
import com.heartline.phone.link.WatchLinkUi
import com.heartline.phone.ui.theme.HeartlineTheme
import com.heartline.shared.sync.PeerProbe

/** Watch connection status with the one action that fixes it. `null` means still checking. */
@Composable
fun WatchLinkCard(link: WatchLinkUi?, onRetry: () -> Unit, onOpenWatch: () -> Unit, modifier: Modifier = Modifier) {
    val colors = HeartlineTheme.colors
    val (title, body, tint) = when (link?.probe) {
        null -> Triple(stringResource(R.string.link_checking_title), stringResource(R.string.link_checking_body), colors.onSurfaceVariant)
        PeerProbe.REACHABLE -> Triple(
            stringResource(R.string.link_ok_title, link.name ?: stringResource(R.string.link_your_watch)),
            stringResource(R.string.link_ok_body),
            colors.statusNormal
        )
        PeerProbe.APP_MISSING -> Triple(stringResource(R.string.link_app_missing_title), stringResource(R.string.link_app_missing_body), colors.statusWarn)
        PeerProbe.NO_DEVICE -> Triple(stringResource(R.string.link_no_watch_title), stringResource(R.string.link_no_watch_body), colors.statusAlert)
    }
    RoundedCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (link == null) {
                CircularProgressIndicator(Modifier.size(36.dp), color = colors.primary, strokeWidth = 3.dp)
            } else {
                IconBadge(Icons.Rounded.Watch, tint, size = 36)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = colors.onBackground)
                Spacer(Modifier.height(2.dp))
                Text(body, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
        }
        if (link != null) {
            Spacer(Modifier.height(14.dp))
            if (link.probe == PeerProbe.REACHABLE) {
                TonalPillButton(stringResource(R.string.link_open_on_watch), onClick = onOpenWatch, color = colors.primary)
            } else {
                TonalPillButton(stringResource(R.string.action_check_again), onClick = onRetry, color = colors.primary)
            }
        }
    }
}
