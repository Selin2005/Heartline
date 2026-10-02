// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.ui.metric

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.heartline.phone.ui.model.BackgroundVitalsUi
import com.heartline.phone.ui.model.MetricDetailUi
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

/** Shared detail layout for SpO2, skin temperature, body composition and stress. */
@Composable
fun MetricDetailScreen(state: MetricDetailUi, onBack: (() -> Unit)? = null, onMeasureOnWatch: (() -> Unit)? = null) {
    val colors = HeartlineTheme.colors
    val color = colors.metric(state.metric)
    ReachabilityScaffold(
        title = stringResource(state.metric.label),
        subtitle = state.latest?.let { stringResource(R.string.bp_last_measured, "${it.date} ${it.time}") },
        onBack = onBack,
    ) {
        onMeasureOnWatch?.let { measure ->
            item { TonalPillButton(stringResource(R.string.action_measure_on_watch), onClick = measure, modifier = Modifier.gutter(), color = color) }
        }
        state.background?.let { bg -> item { BackgroundCard(state.metric, bg) } }
        val latest = state.latest
        if (latest == null) {
            // With background readings only, no measurement of your own yet, there is nothing more to show.
            if (state.background == null) {
                item { EmptyCard(stringResource(R.string.ecg_empty_title), stringResource(R.string.metric_empty_body)) }
            }
        } else {
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
            if (state.readings.size >= 2) {
                item {
                    RoundedCard(Modifier.gutter()) {
                        CardTitle(stringResource(R.string.bp_trend))
                        Spacer(Modifier.height(12.dp))
                        val recent = state.readings.take(7).reversed()
                        WeekBars(recent.map { it.plot - recent.minOf { r -> r.plot } * 0.9f }, recent.map { if (' ' in it.date) it.date.substringAfterLast(' ') else it.date }, color)
                    }
                }
            }
            item { SectionHeader(stringResource(R.string.bp_history)) }
            item {
                RoundedCard(Modifier.gutter(), contentPadding = 0.dp) {
                    val rows = state.readings.take(10)
                    rows.forEachIndexed { i, r ->
                        CardRow(
                            listOfNotNull(r.value, r.unit).joinToString(" "),
                            subtitle = "${r.date} · ${r.time}",
                            showDivider = i < rows.lastIndex,
                        )
                    }
                }
            }
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
