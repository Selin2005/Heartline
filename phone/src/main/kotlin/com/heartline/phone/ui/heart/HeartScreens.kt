// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.ui.heart

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.NorthEast
import androidx.compose.material.icons.rounded.SouthEast
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.heartline.phone.R
import com.heartline.phone.ui.components.ShareAction
import androidx.compose.foundation.layout.Arrangement
import com.heartline.phone.ui.components.Chip
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import com.heartline.phone.ui.components.TonalPillButton
import com.heartline.phone.ui.components.CardRow
import com.heartline.phone.ui.components.CardTitle
import com.heartline.phone.ui.components.RangeBarChart
import com.heartline.phone.ui.components.IconBadge
import com.heartline.phone.ui.components.MetricValue
import com.heartline.phone.ui.components.ReachabilityScaffold
import com.heartline.phone.ui.components.RoundedCard
import com.heartline.phone.ui.components.SectionHeader
import com.heartline.phone.ui.components.StatColumn
import com.heartline.phone.ui.components.WeekBars
import com.heartline.phone.ui.components.gutter
import com.heartline.phone.ui.ecg.EmptyCard
import com.heartline.phone.ui.model.AlertUi
import com.heartline.phone.ui.model.HeartRateUi
import com.heartline.phone.ui.theme.HeartlineTheme
import com.heartline.shared.hr.AlertKind

enum class HrPeriod(val label: Int) { DAY(R.string.period_day), WEEK(R.string.period_week), MONTH(R.string.period_month) }

@Composable
fun HeartRateScreen(
    state: HeartRateUi,
    onBack: (() -> Unit)? = null,
    onOpenAlerts: () -> Unit = {},
    onMeasureOnWatch: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
) {
    val colors = HeartlineTheme.colors
    ReachabilityScaffold(
        title = stringResource(R.string.metric_hr),
        subtitle = state.latestTime?.let { stringResource(R.string.hr_last_measured, it) },
        onBack = onBack,
        actions = { if (onShare != null && state.latestBpm != null) ShareAction(onShare) },
    ) {
        onMeasureOnWatch?.let { measure ->
            item { TonalPillButton(stringResource(R.string.action_measure_on_watch), onClick = measure, modifier = Modifier.gutter(), color = colors.heartRate) }
        }
        if (state.latestBpm == null) {
            item { EmptyCard(stringResource(R.string.hr_empty_title), stringResource(R.string.hr_empty_body)) }
        } else {
            item {
                RoundedCard(Modifier.gutter()) {
                    CardTitle(stringResource(R.string.hr_today))
                    Spacer(Modifier.height(8.dp))
                    MetricValue("${state.latestBpm}", stringResource(R.string.unit_bpm), large = true)
                    Spacer(Modifier.height(12.dp))
                    var period by rememberSaveable { mutableStateOf(HrPeriod.DAY) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HrPeriod.entries.forEach { p -> Chip(stringResource(p.label), period == p) { period = p } }
                    }
                    Spacer(Modifier.height(12.dp))
                    when (period) {
                        HrPeriod.DAY -> RangeBarChart(
                            state.day,
                            slots = 48,
                            color = colors.heartRate,
                            xLabels = listOf("00", "06", "12", "18", "24"),
                            label = { i -> "%02d:%02d–%02d:%02d".format(i * 30 / 60, i * 30 % 60, (i + 1) * 30 / 60 % 24, (i + 1) * 30 % 60) },
                            resting = state.restingBpm,
                            contentDescription = stringResource(R.string.a11y_hr_chart, state.minBpm ?: 0, state.maxBpm ?: 0),
                        )
                        HrPeriod.WEEK -> RangeBarChart(
                            state.week,
                            slots = 7,
                            color = colors.heartRate,
                            xLabels = state.weekLabels,
                            label = { i -> state.weekDates.getOrElse(i) { "" } },
                            resting = state.restingBpm,
                        )
                        HrPeriod.MONTH -> RangeBarChart(
                            state.month,
                            slots = 30,
                            color = colors.heartRate,
                            xLabels = state.monthDates.filterIndexed { i, _ -> i % 7 == 0 }.map { it.substringAfter(' ') },
                            label = { i -> state.monthDates.getOrElse(i) { "" } },
                            resting = state.restingBpm,
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Row {
                        StatColumn(stringResource(R.string.hr_resting), state.restingBpm?.let { "$it" } ?: "–", Modifier.weight(1f))
                        StatColumn(stringResource(R.string.hr_min), state.minBpm?.let { "$it" } ?: "–", Modifier.weight(1f))
                        StatColumn(stringResource(R.string.hr_max), state.maxBpm?.let { "$it" } ?: "–", Modifier.weight(1f))
                    }
                }
            }
            item {
                RoundedCard(Modifier.gutter()) {
                    CardTitle(stringResource(R.string.hrv_title))
                    Text(stringResource(R.string.hrv_caption), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    MetricValue(state.hrvTodayMs?.let { "$it" } ?: "–", stringResource(R.string.unit_ms))
                    Spacer(Modifier.height(12.dp))
                    WeekBars(state.hrvWeek, state.weekLabels, colors.heartRate, contentDescription = stringResource(R.string.a11y_hrv_chart))
                }
            }
        }
        item { SectionHeader(stringResource(R.string.alerts_title)) }
        item {
            RoundedCard(Modifier.gutter(), contentPadding = 0.dp) {
                CardRow(
                    stringResource(R.string.alerts_history),
                    subtitle = if (state.unreadAlerts > 0) {
                        stringResource(R.string.alerts_unread, state.unreadAlerts)
                    } else {
                        stringResource(R.string.alerts_none_new)
                    },
                    leading = { IconBadge(Icons.Rounded.MonitorHeart, colors.ecg) },
                    trailing = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = colors.onSurfaceVariant) },
                    onClick = onOpenAlerts,
                )
            }
        }
    }
}

@Composable
fun AlertsScreen(alerts: List<AlertUi>, onBack: (() -> Unit)? = null) {
    val colors = HeartlineTheme.colors
    ReachabilityScaffold(title = stringResource(R.string.alerts_history), onBack = onBack) {
        if (alerts.isEmpty()) {
            item { EmptyCard(stringResource(R.string.alerts_empty_title), stringResource(R.string.alerts_empty_body)) }
            return@ReachabilityScaffold
        }
        item {
            RoundedCard(Modifier.gutter(), contentPadding = 0.dp) {
                alerts.forEachIndexed { i, alert ->
                    val (title, icon, tint) = when (alert.kind) {
                        AlertKind.IRREGULAR_RHYTHM -> Triple(R.string.alert_irn_title, Icons.Rounded.MonitorHeart, colors.statusAlert)
                        AlertKind.HIGH_HEART_RATE -> Triple(R.string.alert_high_title, Icons.Rounded.NorthEast, colors.statusWarn)
                        AlertKind.LOW_HEART_RATE -> Triple(R.string.alert_low_title, Icons.Rounded.SouthEast, colors.statusWarn)
                    }
                    val detail = when (alert.kind) {
                        AlertKind.IRREGULAR_RHYTHM -> stringResource(R.string.alert_irn_detail, alert.windows)
                        else -> alert.bpm?.let { stringResource(R.string.ecg_bpm_value, it) }.orEmpty()
                    }
                    CardRow(
                        stringResource(title),
                        subtitle = "${alert.date} · ${alert.time}${if (detail.isNotEmpty()) " · $detail" else ""}",
                        leading = { IconBadge(icon, tint) },
                        showDivider = i < alerts.lastIndex,
                    )
                }
            }
        }
        item {
            Text(
                stringResource(R.string.alerts_footer),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 28.dp),
            )
        }
    }
}
