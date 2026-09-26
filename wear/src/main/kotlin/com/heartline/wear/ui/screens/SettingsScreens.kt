// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DeveloperMode
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.heartline.wear.R
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.SwitchButtonDefaults
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnState
import androidx.compose.material.icons.rounded.ShowChart
import androidx.compose.material.icons.rounded.Vibration
import androidx.wear.compose.material3.SwitchButton
import com.heartline.wear.ui.theme.WearColors

data class WatchSettingsUi(
    val irregularRhythm: Boolean,
    val heartRateAlerts: Boolean,
    val serviceVersion: String?,
    val trackers: List<String>,
    val appVersion: String,
    val backgroundHeartRate: Boolean = true,
    val haptics: Boolean = true,
    val liveWave: Boolean = true,
    val highBpm: Int = 120,
    val lowBpm: Int = 40,
    val diagnosticLogs: Boolean = false,
    val detailedLogs: Boolean = false,
    val logKb: Long = 0,
)

/** One setting toggled on the watch. */
enum class WatchToggle { IRREGULAR_RHYTHM, HEART_RATE_ALERTS, BACKGROUND_HR, HAPTICS, LIVE_WAVE }

@Composable
private fun Row(icon: ImageVector, label: String, secondary: String?, onClick: () -> Unit = {}) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.filledTonalButtonColors(),
        icon = { Icon(icon, contentDescription = null, tint = WearColors.primary, modifier = Modifier.size(22.dp)) },
        label = { Text(label, maxLines = 2) },
        secondaryLabel = secondary?.let { { Text(it, maxLines = 2, color = WearColors.onSurfaceVariant) } },
    )
}

@Composable
private fun Toggle(icon: ImageVector, label: String, checked: Boolean, secondary: String? = null, onChange: (Boolean) -> Unit) {
    SwitchButton(
        checked = checked,
        onCheckedChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        // One UI Watch style: the card stays dark either way; only the switch turns blue.
        colors = SwitchButtonDefaults.switchButtonColors(
            checkedContainerColor = WearColors.surface,
            checkedContentColor = WearColors.onSurface,
            checkedSecondaryContentColor = WearColors.onSurfaceVariant,
            checkedIconColor = WearColors.primary,
            checkedThumbColor = Color.White,
            checkedTrackColor = WearColors.primary,
            uncheckedContainerColor = WearColors.surface,
            uncheckedContentColor = WearColors.onSurface,
            uncheckedSecondaryContentColor = WearColors.onSurfaceVariant,
            uncheckedIconColor = WearColors.primary,
        ),
        icon = { Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp)) },
        label = { Text(label, maxLines = 2) },
        secondaryLabel = secondary?.let { { Text(it, maxLines = 2) } },
    )
}

/** Settings shared with the phone: every toggle here syncs there too (the newer change wins). */
@Composable
fun WatchSettingsScreen(
    state: WatchSettingsUi,
    onToggle: (WatchToggle, Boolean) -> Unit = { _, _ -> },
    onDevMode: () -> Unit = {},
    onDiagnostics: () -> Unit = {},
    onSourceCode: () -> Unit = {},
    listState: TransformingLazyColumnState = rememberTransformingLazyColumnState(),
) {
    val list = listState
    ScreenScaffold(scrollState = list) { padding ->
        TransformingLazyColumn(state = list, contentPadding = padding) {
            item { ListHeader { Text(stringResource(R.string.settings)) } }
            item { Toggle(Icons.Rounded.MonitorHeart, stringResource(R.string.settings_background_hr), state.backgroundHeartRate) { onToggle(WatchToggle.BACKGROUND_HR, it) } }
            item { Toggle(Icons.Rounded.NotificationsActive, stringResource(R.string.settings_irn), state.irregularRhythm) { onToggle(WatchToggle.IRREGULAR_RHYTHM, it) } }
            item {
                Toggle(
                    Icons.Rounded.NotificationsActive,
                    stringResource(R.string.settings_hr_alerts),
                    state.heartRateAlerts,
                    secondary = stringResource(R.string.settings_hr_alerts_range, state.highBpm, state.lowBpm),
                ) { onToggle(WatchToggle.HEART_RATE_ALERTS, it) }
            }
            item { Toggle(Icons.Rounded.Vibration, stringResource(R.string.settings_haptics), state.haptics) { onToggle(WatchToggle.HAPTICS, it) } }
            item { Toggle(Icons.Rounded.ShowChart, stringResource(R.string.settings_live_wave), state.liveWave) { onToggle(WatchToggle.LIVE_WAVE, it) } }
            item {
                Text(
                    stringResource(R.string.settings_synced_with_phone),
                    style = MaterialTheme.typography.bodySmall,
                    color = WearColors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            item { Row(Icons.Rounded.DeveloperMode, stringResource(R.string.dev_mode_title), null, onDevMode) }
            item { Row(Icons.Rounded.Info, stringResource(R.string.diagnostics), state.serviceVersion ?: "–", onDiagnostics) }
            item { Row(Icons.Rounded.Info, stringResource(R.string.version), state.appVersion) }
            // AGPL: the source is one tap away; it opens on the phone.
            item { Row(Icons.Rounded.Code, stringResource(R.string.source_code), stringResource(R.string.source_code_sub), onSourceCode) }
        }
    }
}

@Composable
fun DiagnosticsScreen(state: WatchSettingsUi) {
    val list = rememberTransformingLazyColumnState()
    ScreenScaffold(scrollState = list) { padding ->
        TransformingLazyColumn(state = list, contentPadding = padding) {
            item { ListHeader { Text(stringResource(R.string.diagnostics)) } }
            item { Centered(stringResource(R.string.diag_service, state.serviceVersion ?: "–")) }
            item { Centered(stringResource(R.string.diag_sdk)) }
            item { ListHeader { Text(stringResource(R.string.diag_trackers), textAlign = TextAlign.Center) } }
            state.trackers.forEach { tracker ->
                item { Centered(tracker, small = true) }
            }
            item { ListHeader { Text(stringResource(R.string.diag_logs), textAlign = TextAlign.Center) } }
            item {
                Centered(
                    when {
                        !state.diagnosticLogs -> stringResource(R.string.diag_logs_off)
                        state.detailedLogs -> stringResource(R.string.diag_logs_detailed, state.logKb)
                        else -> stringResource(R.string.diag_logs_on, state.logKb)
                    },
                    small = true,
                )
            }
            item { Centered(stringResource(R.string.diag_logs_hint), small = true) }
        }
    }
}

/** Body text for round screens: centred, inset from the curved edge. */
@Composable
private fun Centered(text: String, small: Boolean = false) = Text(
    text,
    style = if (small) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
    color = if (small) WearColors.onSurfaceVariant else WearColors.onSurface,
    textAlign = TextAlign.Center,
    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 3.dp),
)
