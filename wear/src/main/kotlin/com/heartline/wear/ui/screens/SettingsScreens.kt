// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.DeveloperMode
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.WaterDrop
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
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.rounded.Vibration
import androidx.wear.compose.material3.SwitchButton
import com.heartline.wear.ui.theme.WearColors
import com.heartline.shared.diag.formatLogSize
import com.heartline.shared.hr.AlertSensitivity
import com.heartline.shared.hr.HeartLimits
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

data class WatchSettingsUi(
    val heartMonitoring: Boolean,
    val sensitivity: AlertSensitivity,
    val serviceVersion: String?,
    val trackers: List<String>,
    val appVersion: String,
    val haptics: Boolean = true,
    val liveWave: Boolean = true,
    /** All-day heart rate recording (its own switch; the heart part needs it). */
    val allDayHeartRate: Boolean = true,
    /** The parts of health monitoring, under the master switch ([heartMonitoring]). */
    val heartPart: Boolean = true,
    val spo2Part: Boolean = true,
    val tempPart: Boolean = true,
    /** The personal limits in use (null before the first background minute). */
    val limits: HeartLimits? = null,
    val diagnosticLogs: Boolean = false,
    /** The kept log, compressed. */
    val logBytes: Long = 0,
)

/** One setting toggled on the watch. */
enum class WatchToggle { ALL_DAY_HEART_RATE, HEART_MONITORING, HEART_PART, SPO2_PART, TEMP_PART, HAPTICS, LIVE_WAVE }

/** What a turn-off confirmation is for: the master and all-day ask twice, the heart part once. */
enum class OffTarget { MASTER, ALL_DAY, HEART }

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
    onSensitivity: (AlertSensitivity) -> Unit = {},
    onDevMode: () -> Unit = {},
    onDiagnostics: () -> Unit = {},
    onSourceCode: () -> Unit = {},
    onCommunity: () -> Unit = {},
    listState: TransformingLazyColumnState = rememberTransformingLazyColumnState(),
    /** Turning heart monitoring off asks twice: 1 lists what stops, 2 asks again. */
    initialConfirmStep: Int = 0,
    /** The confirmation is for all-day heart rate (off while monitoring is on stops monitoring too). */
    initialConfirmAllDay: Boolean = false,
) {
    var confirm by rememberSaveable { mutableIntStateOf(initialConfirmStep) }
    var target by rememberSaveable { mutableStateOf(if (initialConfirmAllDay) OffTarget.ALL_DAY else OffTarget.MASTER) }
    if (confirm > 0) {
        MonitoringOffConfirm(
            step = confirm,
            target = target,
            onNext = {
                if (confirm == 1) {
                    confirm = 2
                } else {
                    confirm = 0
                    val toggle = when (target) {
                        OffTarget.MASTER -> WatchToggle.HEART_MONITORING
                        OffTarget.ALL_DAY -> WatchToggle.ALL_DAY_HEART_RATE
                        OffTarget.HEART -> WatchToggle.HEART_PART
                    }
                    onToggle(toggle, false)
                }
            },
            onCancel = { confirm = 0 },
        )
        return
    }
    val list = listState
    ScreenScaffold(scrollState = list) { padding ->
        TransformingLazyColumn(state = list, contentPadding = padding) {
            item { ListHeader { Text(stringResource(R.string.settings)) } }
            item {
                Toggle(Icons.Rounded.MonitorHeart, stringResource(R.string.settings_background_hr), state.allDayHeartRate) { on ->
                    when {
                        on -> onToggle(WatchToggle.ALL_DAY_HEART_RATE, true)
                        state.heartMonitoring && state.heartPart -> {
                            target = OffTarget.ALL_DAY
                            confirm = 1
                        }
                        else -> onToggle(WatchToggle.ALL_DAY_HEART_RATE, false)
                    }
                }
            }
            item {
                Toggle(
                    Icons.Rounded.NotificationsActive,
                    stringResource(R.string.settings_heart_monitoring),
                    state.heartMonitoring,
                    secondary = when {
                        !state.heartMonitoring -> stringResource(R.string.settings_heart_monitoring_off)
                        state.limits == null || state.limits.learning -> stringResource(R.string.settings_heart_monitoring_learning)
                        else -> stringResource(R.string.settings_heart_monitoring_normal, state.limits.restNormal, state.limits.high, state.limits.low)
                    },
                ) { on ->
                    if (on) {
                        onToggle(WatchToggle.HEART_MONITORING, true)
                    } else {
                        target = OffTarget.MASTER
                        confirm = 1
                    }
                }
            }
            if (state.heartMonitoring) {
                item {
                    Toggle(Icons.Rounded.MonitorHeart, stringResource(R.string.settings_part_heart), state.heartPart) { on ->
                        if (on) {
                            onToggle(WatchToggle.HEART_PART, true)
                        } else {
                            // One confirmation: the heart part's notifications matter most.
                            target = OffTarget.HEART
                            confirm = 2
                        }
                    }
                }
                item { Toggle(Icons.Rounded.WaterDrop, stringResource(R.string.settings_part_spo2), state.spo2Part) { onToggle(WatchToggle.SPO2_PART, it) } }
                item { Toggle(Icons.Rounded.Thermostat, stringResource(R.string.settings_part_temp), state.tempPart) { onToggle(WatchToggle.TEMP_PART, it) } }
                item {
                    Row(Icons.Rounded.NotificationsActive, stringResource(R.string.settings_sensitivity), stringResource(state.sensitivity.label)) {
                        val all = AlertSensitivity.entries
                        onSensitivity(all[(state.sensitivity.ordinal + 1) % all.size])
                    }
                }
            }
            item { Toggle(Icons.Rounded.Vibration, stringResource(R.string.settings_haptics), state.haptics) { onToggle(WatchToggle.HAPTICS, it) } }
            item { Toggle(Icons.AutoMirrored.Rounded.ShowChart, stringResource(R.string.settings_live_wave), state.liveWave) { onToggle(WatchToggle.LIVE_WAVE, it) } }
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
            item { Row(Icons.Rounded.Forum, stringResource(R.string.community), stringResource(R.string.community_sub), onCommunity) }
        }
    }
}

private val AlertSensitivity.label: Int get() = when (this) {
    AlertSensitivity.LOW -> R.string.sensitivity_low
    AlertSensitivity.STANDARD -> R.string.sensitivity_standard
    AlertSensitivity.HIGH -> R.string.sensitivity_high
}

/** The two confirmations before heart monitoring goes off: what stops, then "are you sure?". */
@Composable
fun MonitoringOffConfirm(step: Int, onNext: () -> Unit, onCancel: () -> Unit, target: OffTarget = OffTarget.MASTER) {
    val allDay = target == OffTarget.ALL_DAY
    val list = rememberTransformingLazyColumnState()
    ScreenScaffold(scrollState = list) { padding ->
        TransformingLazyColumn(state = list, contentPadding = padding) {
            item {
                ListHeader {
                    Text(
                        stringResource(
                            when {
                                target == OffTarget.HEART -> R.string.heart_part_off_title
                                step == 2 -> R.string.monitoring_off_sure_title
                                allDay -> R.string.all_day_off_title
                                else -> R.string.monitoring_off_title
                            },
                        ),
                        textAlign = TextAlign.Center,
                    )
                }
            }
            item {
                Centered(
                    stringResource(
                        when {
                            target == OffTarget.HEART -> R.string.heart_part_off_text
                            step == 1 && allDay -> R.string.all_day_off_list
                            step == 1 -> R.string.monitoring_off_list
                            allDay -> R.string.all_day_off_sure_text
                            else -> R.string.monitoring_off_sure_text
                        },
                    ),
                )
            }
            item {
                Button(
                    onClick = onNext,
                    modifier = Modifier.fillMaxWidth(),
                    colors = if (step == 2) ButtonDefaults.buttonColors(containerColor = WearColors.severity(com.heartline.shared.model.Severity.ALERT)) else ButtonDefaults.filledTonalButtonColors(),
                    label = { Text(stringResource(if (step == 1) R.string.action_continue else R.string.action_turn_off)) },
                )
            }
            item {
                Button(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(if (step == 1) R.string.action_cancel else R.string.action_keep_on)) },
                )
            }
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
                        else -> stringResource(R.string.diag_logs_on, formatLogSize(state.logBytes))
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
