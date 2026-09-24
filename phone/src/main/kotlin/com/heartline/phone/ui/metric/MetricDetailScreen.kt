package com.heartline.phone.ui.metric

import androidx.compose.foundation.layout.Row
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
        val latest = state.latest
        if (latest == null) {
            item { EmptyCard(stringResource(R.string.ecg_empty_title), stringResource(R.string.metric_empty_body)) }
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
