// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.ui.metric

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.heartline.phone.R
import com.heartline.phone.ui.components.TonalPillButton
import com.heartline.phone.ui.components.CardRow
import com.heartline.phone.ui.components.CardTitle
import com.heartline.phone.ui.components.MetricValue
import com.heartline.phone.ui.components.ReachabilityScaffold
import com.heartline.phone.ui.components.RoundedCard
import com.heartline.phone.ui.components.SectionHeader
import com.heartline.phone.ui.components.StatColumn
import com.heartline.phone.ui.components.WeekBars
import com.heartline.phone.ui.components.gutter
import com.heartline.phone.ui.components.label
import com.heartline.phone.ui.ecg.EmptyCard
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.heartline.phone.ui.model.BackgroundStressUi
import com.heartline.phone.ui.model.BackgroundVitalsUi
import com.heartline.shared.profile.StressIndex
import com.heartline.shared.profile.StressLevel
import com.heartline.phone.ui.model.MetricDetailUi
import com.heartline.phone.ui.model.MetricReadingUi
import com.heartline.phone.ui.theme.HeartlineTheme
import com.heartline.shared.model.Metric

private val Metric.about: Int
    get() = when (this) {
        Metric.SPO2 -> R.string.about_spo2
        Metric.SKIN_TEMPERATURE -> R.string.about_temp
        Metric.BODY_COMPOSITION -> R.string.about_body
        else -> R.string.about_stress
    }

private val Metric.valueLabel: Int
    get() = when (this) {
        Metric.SPO2 -> R.string.value_spo2
        Metric.SKIN_TEMPERATURE -> R.string.value_temp
        Metric.BODY_COMPOSITION -> R.string.value_body
        else -> R.string.value_stress
    }

/** What the watch measured by itself: usual values, last night, 28 nights and today's readings. */
@Composable
private fun BackgroundCard(metric: Metric, bg: BackgroundVitalsUi) {
    val colors = HeartlineTheme.colors
    val color = colors.metric(metric)
    val spo2 = metric == Metric.SPO2
    RoundedCard(Modifier.gutter()) {
        CardTitle(stringResource(R.string.vitals_background_title))
        Text(
            stringResource(if (spo2) R.string.vitals_background_spo2_caption else R.string.vitals_background_temp_caption),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Row {
            if (spo2) {
                StatColumn(stringResource(R.string.vitals_usual_awake), bg.usualDay?.let { "${it.toInt()} %" } ?: "–", Modifier.weight(1f))
                StatColumn(stringResource(R.string.vitals_usual_asleep), bg.usualNight?.let { "${it.toInt()} %" } ?: "–", Modifier.weight(1f))
                StatColumn(
                    stringResource(R.string.vitals_last_night),
                    bg.lastNight?.let { n -> bg.lastNightLow?.let { stringResource(R.string.vitals_spo2_night_value, n.toInt(), it.toInt()) } } ?: "–",
                    Modifier.weight(1f),
                )
            } else {
                StatColumn(stringResource(R.string.vitals_usual_night), bg.usualNight?.let { "%.1f °C".format(it) } ?: "–", Modifier.weight(1f))
                StatColumn(
                    stringResource(R.string.vitals_last_night),
                    if (bg.learning) stringResource(R.string.vitals_learning) else bg.lastNight?.let { "%+.1f °C".format(it) } ?: "–",
                    Modifier.weight(1f),
                )
            }
        }
        if (bg.nights.any { it != null }) {
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.vitals_nights_title), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            val floor = (bg.nights.filterNotNull().minOrNull() ?: 0f) - if (spo2) 2f else 0.3f
            WeekBars(
                bg.nights.map { it?.let { v -> v - floor } },
                bg.nights.map { "" },
                color,
                contentDescription = stringResource(R.string.vitals_nights_title),
            )
            Row(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.vitals_nights_start), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(stringResource(R.string.vitals_last_night), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
        }
        if (bg.recent.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.vitals_recent, bg.recent.joinToString(" · ") { (time, value) -> "$time $value" }),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

/** Stress the watch read by itself: today in 15-minute slots, the week, and sleep HRV. */
@Composable
private fun StressCard(st: BackgroundStressUi) {
    val colors = HeartlineTheme.colors
    val color = colors.metric(Metric.STRESS)
    fun levelText(score: Int?) = when (score?.let { StressIndex.level(it) }) {
        StressLevel.LOW -> R.string.stress_level_low
        StressLevel.MEDIUM -> R.string.stress_level_medium
        StressLevel.HIGH -> R.string.stress_level_high
        null -> null
    }
    RoundedCard(Modifier.gutter()) {
        CardTitle(stringResource(R.string.vitals_background_title))
        Text(stringResource(R.string.stress_background_caption), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Row {
            StatColumn(
                stringResource(R.string.stress_today),
                st.todayAverage?.let { a -> "$a · ${levelText(a)?.let { stringResource(it) } ?: ""}" } ?: "–",
                Modifier.weight(1f),
            )
            StatColumn(stringResource(R.string.stress_high_time), stringResource(R.string.stress_minutes, st.highMinutes), Modifier.weight(1f))
            StatColumn(
                stringResource(R.string.stress_night_hrv),
                st.lastNightRmssd?.let { n -> st.usualNightRmssd?.let { u -> stringResource(R.string.stress_night_value, n, u) } ?: "$n ms" } ?: "–",
                Modifier.weight(1f),
            )
        }
        if (st.today.any { it != null }) {
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.stress_today_chart), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            val track = colors.surfaceVariant
            Canvas(Modifier.fillMaxWidth().height(56.dp)) {
                val slot = size.width / st.today.size
                val w = slot * 0.7f
                st.today.forEachIndexed { i, score ->
                    val left = i * slot + (slot - w) / 2
                    drawRoundRect(track, Offset(left, 0f), Size(w, size.height), CornerRadius(w / 2))
                    if (score != null) {
                        val h = (score.coerceAtLeast(6) / 100f) * size.height
                        val alpha = when (StressIndex.level(score)) {
                            StressLevel.LOW -> 0.35f
                            StressLevel.MEDIUM -> 0.65f
                            StressLevel.HIGH -> 1f
                        }
                        drawRoundRect(color.copy(alpha = alpha), Offset(left, size.height - h), Size(w, h), CornerRadius(w / 2))
                    }
                }
            }
            Row(Modifier.fillMaxWidth()) {
                Text("7:00", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text("15:00", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Text("23:00", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
        }
        if (st.week.any { it != null }) {
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.stress_week), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            WeekBars(
                st.week.map { it?.toFloat() },
                st.weekDays,
                color,
                contentDescription = stringResource(R.string.stress_week),
            )
        }
        st.weekAboveUsual?.let {
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.stress_insight, it), style = MaterialTheme.typography.bodyMedium, color = colors.onBackground)
        }
        if (st.learning) {
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.stress_learning), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}

/** Shared detail layout for SpO2, skin temperature, body composition and stress. */
@Composable
fun MetricDetailScreen(state: MetricDetailUi, onBack: (() -> Unit)? = null, onMeasureOnWatch: (() -> Unit)? = null) {
    val colors = HeartlineTheme.colors
    val color = colors.metric(state.metric)
    ReachabilityScaffold(
        title = stringResource(state.metric.label),
        // A watch row's time is a range ("1:19–6:07 AM"): its last reading is the latest.
        subtitle = state.newest?.let { stringResource(R.string.bp_last_measured, "${it.date} ${it.time.substringAfterLast('–')}") },
        onBack = onBack,
    ) {
        onMeasureOnWatch?.let { measure ->
            item { TonalPillButton(stringResource(R.string.action_measure_on_watch), onClick = measure, modifier = Modifier.gutter(), color = color) }
        }
        state.background?.let { bg -> item { BackgroundCard(state.metric, bg) } }
        state.stress?.let { st -> item { StressCard(st) } }
        val latest = state.latest
        if (state.readings.isEmpty() && state.background == null && state.stress == null) {
            item { EmptyCard(stringResource(R.string.ecg_empty_title), stringResource(R.string.metric_empty_body)) }
        }
        if (latest != null) {
            item {
                RoundedCard(Modifier.gutter()) {
                    CardTitle(stringResource(state.metric.valueLabel))
                    Spacer(Modifier.height(8.dp))
                    MetricValue(latest.value, latest.unit, large = true)
                    if (latest.details.isNotEmpty()) {
                        Spacer(Modifier.height(14.dp))
                        Row {
                            latest.details.take(3).forEach { (label, value) -> StatColumn(stringResource(label), value, Modifier.weight(1f)) }
                        }
                    }
                }
            }
            if (state.manual.size >= 2) {
                item {
                    RoundedCard(Modifier.gutter()) {
                        CardTitle(stringResource(R.string.bp_trend))
                        Spacer(Modifier.height(12.dp))
                        val recent = state.manual.take(7).reversed()
                        WeekBars(recent.map { it.plot - recent.minOf { r -> r.plot } * 0.9f }, recent.map { if (' ' in it.date) it.date.substringAfterLast(' ') else it.date }, color)
                    }
                }
            }
        }
        if (state.readings.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.bp_history)) }
            item { HistoryRows(state.readings.take(14)) }
        }
        item {
            Text(
                stringResource(state.metric.about),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 28.dp),
            )
        }
    }
}

/**
 * Measurements and the watch's days, newest first. A watch day shows its usual value and how many
 * readings it had; tapping it lists them.
 */
@Composable
private fun HistoryRows(rows: List<MetricReadingUi>) {
    var open by remember { mutableStateOf<String?>(null) }
    RoundedCard(Modifier.gutter(), contentPadding = 0.dp) {
        rows.forEachIndexed { i, r ->
            val expanded = open == r.id
            CardRow(
                listOfNotNull(r.value, r.unit).joinToString(" "),
                subtitle = if (r.fromWatch) {
                    stringResource(R.string.history_watch_day, r.date, r.time, r.details.lastOrNull()?.second ?: "")
                } else {
                    "${r.date} · ${r.time}"
                },
                showDivider = i < rows.lastIndex || expanded,
                onClick = if (r.fromWatch) ({ open = if (expanded) null else r.id }) else null,
            )
            if (expanded) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    r.entries.take(48).forEach { (time, value) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Text(time, style = MaterialTheme.typography.bodySmall, color = HeartlineTheme.colors.onSurfaceVariant, modifier = Modifier.weight(1f))
                            Text(value, style = MaterialTheme.typography.bodySmall, color = HeartlineTheme.colors.onBackground)
                        }
                    }
                }
            }
        }
    }
}
