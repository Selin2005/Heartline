// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.ui.heart

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.NorthEast
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material.icons.rounded.WaterDrop
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
import com.heartline.shared.hr.HeartTrend
import com.heartline.shared.hr.HrContext

private val HrContext.label: Int get() = when (this) {
    HrContext.REST -> R.string.hr_context_rest
    HrContext.ACTIVE -> R.string.hr_context_active
    HrContext.EXERCISE -> R.string.hr_context_exercise
    HrContext.SLEEP -> R.string.hr_context_sleep
}

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
            item { TonalPillButton(stringResource(R.string.action_measure_now), onClick = measure, modifier = Modifier.gutter(), color = colors.heartRate) }
        }
        state.lastCheck?.let { check ->
            item(key = "last-check") {
                RoundedCard(Modifier.gutter()) {
                    CardTitle(stringResource(R.string.hr_last_check))
                    Spacer(Modifier.height(8.dp))
                    MetricValue("${check.bpm}", stringResource(R.string.unit_bpm))
                    Text(
                        listOfNotNull(stringResource(R.string.measure_hr_range, check.minBpm, check.maxBpm), state.lastCheckTime).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        }
        if (state.latestBpm == null && state.lastCheck == null) {
            item { EmptyCard(stringResource(R.string.hr_empty_title), stringResource(R.string.hr_empty_body)) }
        } else if (state.latestBpm == null) {
            Unit
        } else {
            item {
                RoundedCard(Modifier.gutter()) {
                    CardTitle(stringResource(R.string.hr_today))
                    Spacer(Modifier.height(8.dp))
                    MetricValue("${state.latestBpm}", stringResource(R.string.unit_bpm), large = true)
                    Spacer(Modifier.height(12.dp))
                    var period by rememberSaveable { mutableStateOf(HrPeriod.DAY) }
                    var filter by rememberSaveable { mutableStateOf<HrContext?>(null) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HrPeriod.entries.forEach { p -> Chip(stringResource(p.label), period == p) { period = p } }
                    }
                    if (period == HrPeriod.DAY && state.dayByActivity.size > 1) {
                        Spacer(Modifier.height(8.dp))
                        // Today's chart for one kind of minute: resting, asleep, moving or exercise.
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Chip(stringResource(R.string.hr_filter_all), filter == null) { filter = null }
                            HrContext.entries.filter { it in state.dayByActivity }.forEach { c ->
                                Chip(stringResource(c.label), filter == c) { filter = c }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    when (period) {
                        HrPeriod.DAY -> RangeBarChart(
                            filter?.let { state.dayByActivity[it] } ?: state.day,
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
            state.limits?.let { limits ->
                item {
                    RoundedCard(Modifier.gutter()) {
                        CardTitle(stringResource(R.string.hr_normal_title))
                        Text(
                            stringResource(if (limits.learning) R.string.hr_normal_learning else R.string.hr_normal_caption),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        Row {
                            StatColumn(
                                stringResource(R.string.hr_awake_still),
                                limits.restLow?.let { lo -> limits.restHigh?.let { hi -> stringResource(R.string.hr_normal_range, limits.restNormal, lo, hi) } }
                                    ?: "${limits.restNormal}",
                                Modifier.weight(1f),
                            )
                            StatColumn(stringResource(R.string.hr_sleep), "${limits.sleepNormal}", Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.hr_normal_limits, limits.high, limits.low, limits.sleepLow, limits.exerciseMax),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                        if (state.nights.any { it != null }) {
                            Spacer(Modifier.height(16.dp))
                            Text(stringResource(R.string.hr_nights_title), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            Spacer(Modifier.height(8.dp))
                            WeekBars(
                                state.nights,
                                state.nights.map { "" },
                                colors.heartRate,
                                contentDescription = stringResource(R.string.hr_nights_title),
                            )
                            // Under the bars, not in their narrow columns ("−14" broke into "– / 1 / 4").
                            Row(Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.vitals_nights_start), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
                                Text(stringResource(R.string.vitals_last_night), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            item {
                RoundedCard(Modifier.gutter()) {
                    CardTitle(stringResource(R.string.hr_activity_title))
                    Spacer(Modifier.height(12.dp))
                    Row {
                        StatColumn(
                            stringResource(R.string.hr_sleep),
                            state.sleepAvgBpm?.let { stringResource(R.string.hr_sleep_value, it, state.sleepMinBpm ?: it) } ?: "–",
                            Modifier.weight(1f),
                        )
                        StatColumn(
                            stringResource(R.string.hr_exercise),
                            if (state.exerciseMinutes > 0) stringResource(R.string.hr_exercise_value, state.exerciseMinutes, state.exercisePeakBpm ?: 0) else "–",
                            Modifier.weight(1f),
                        )
                    }
                    if (state.restingWeek.any { it != null }) {
                        Spacer(Modifier.height(16.dp))
                        Text(stringResource(R.string.hr_resting_week), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        WeekBars(state.restingWeek, state.weekLabels, colors.heartRate, contentDescription = stringResource(R.string.hr_resting_week))
                    }
                }
            }
            if (state.zoneBounds.isNotEmpty()) {
                item {
                    RoundedCard(Modifier.gutter()) {
                        CardTitle(stringResource(R.string.hr_zones_title))
                        Text(stringResource(R.string.hr_zones_caption, state.zoneBounds.first(), state.maxHr), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        if (state.zoneMinutes.none { it > 0 }) {
                            // Five zero columns said nothing; most days without exercise look like this.
                            Text(stringResource(R.string.hr_zones_none), style = MaterialTheme.typography.bodyMedium, color = colors.onBackground)
                            return@RoundedCard
                        }
                        WeekBars(
                            state.zoneMinutes.map { it.toFloat() },
                            state.zoneBounds.map { "$it+" },
                            colors.heartRate,
                            contentDescription = stringResource(R.string.hr_zones_title),
                        )
                        Spacer(Modifier.height(8.dp))
                        Row {
                            state.zoneMinutes.forEachIndexed { i, m ->
                                StatColumn(stringResource(R.string.hr_zone, i + 1), stringResource(R.string.hr_minutes_short, m), Modifier.weight(1f))
                            }
                        }
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
                    val vital = alert.vital
                    val (title, icon, tint) = if (vital != null) {
                        Triple(
                            com.heartline.phone.notify.PhoneNotifier.alertTitle(com.heartline.shared.hr.HealthAlert(alert.id, alert.kind, 0, alert.bpm, vital = vital)),
                            when (vital) {
                                com.heartline.shared.hr.VitalAlert.SPO2_LOW, com.heartline.shared.hr.VitalAlert.SPO2_NIGHTS -> Icons.Rounded.WaterDrop
                                com.heartline.shared.hr.VitalAlert.STRESS -> Icons.Rounded.SelfImprovement
                                else -> Icons.Rounded.Thermostat
                            },
                            colors.statusWarn,
                        )
                    } else if (alert.trend != null) {
                        Triple(if (alert.trend == HeartTrend.HIGH_NORMAL) R.string.alert_high_normal_title else R.string.alert_trend_title, Icons.Rounded.NorthEast, colors.statusWarn)
                    } else when (alert.kind) {
                        AlertKind.IRREGULAR_RHYTHM -> Triple(R.string.alert_irn_title, Icons.Rounded.MonitorHeart, colors.statusAlert)
                        AlertKind.HIGH_HEART_RATE -> Triple(R.string.alert_high_title, Icons.Rounded.NorthEast, colors.statusWarn)
                        AlertKind.LOW_HEART_RATE -> Triple(R.string.alert_low_title, Icons.Rounded.SouthEast, colors.statusWarn)
                    }
                    val detail = when {
                        vital != null -> alert.value?.let { v ->
                            when (vital) {
                                com.heartline.shared.hr.VitalAlert.SPO2_LOW, com.heartline.shared.hr.VitalAlert.SPO2_NIGHTS -> "${v.toInt()} %"
                                com.heartline.shared.hr.VitalAlert.STRESS -> stringResource(R.string.alert_stress_score, v.toInt())
                                else -> "%+.1f °C".format(v)
                            }
                        } ?: ""
                        alert.kind == AlertKind.IRREGULAR_RHYTHM -> listOfNotNull(
                            stringResource(R.string.alert_irn_detail, alert.windows),
                            when (alert.ecgRegular) {
                                true -> stringResource(R.string.alert_ecg_regular)
                                false -> stringResource(R.string.alert_ecg_other)
                                null -> null
                            },
                        ).joinToString(" · ")
                        else -> listOfNotNull(
                            alert.bpm?.let { stringResource(R.string.ecg_bpm_value, it) },
                            alert.normal?.let { stringResource(R.string.alert_usual, it) },
                            alert.threshold?.let {
                                stringResource(
                                    if (alert.kind == AlertKind.HIGH_HEART_RATE) R.string.alert_limit_above else R.string.alert_limit_below,
                                    it,
                                )
                            },
                            alert.context?.let { stringResource(it.label) },
                        ).joinToString(" · ")
                    }
                    val origin = alert.otherWatchVersion?.let { " · " + stringResource(R.string.alert_other_watch_version, it) }.orEmpty()
                    CardRow(
                        stringResource(title),
                        subtitle = "${alert.date} · ${alert.time}${if (detail.isNotEmpty()) " · $detail" else ""}$origin",
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
