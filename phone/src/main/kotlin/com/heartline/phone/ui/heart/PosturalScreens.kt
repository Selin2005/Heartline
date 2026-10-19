// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.ui.heart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.NorthEast
import androidx.compose.material.icons.rounded.SouthEast
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.heartline.phone.R
import com.heartline.phone.ui.components.CardRow
import com.heartline.phone.ui.components.CardTitle
import com.heartline.phone.ui.components.Chip
import com.heartline.phone.ui.components.IconBadge
import com.heartline.phone.ui.components.MetricValue
import com.heartline.phone.ui.components.RangeBarChart
import com.heartline.phone.ui.components.ReachabilityScaffold
import com.heartline.phone.ui.components.RoundedCard
import com.heartline.phone.ui.components.SectionHeader
import com.heartline.phone.ui.components.ShareAction
import com.heartline.phone.ui.components.StatColumn
import com.heartline.phone.ui.components.TonalPillButton
import com.heartline.phone.ui.components.gutter
import com.heartline.phone.ui.components.label
import com.heartline.phone.ui.ecg.EmptyCard
import com.heartline.phone.ui.model.CurvePoint
import com.heartline.phone.ui.model.PosturalBannerUi
import com.heartline.phone.ui.model.PosturalReportUi
import com.heartline.phone.ui.model.PosturalSummaries
import com.heartline.phone.ui.model.RiseUi
import com.heartline.phone.ui.theme.HeartlineTheme
import com.heartline.shared.model.Symptom
import com.heartline.shared.pots.PosturalStatus

/**
 * The heart-rate screen's link to the report of rises after getting up. It exists only once a
 * pattern was found: single rises happen to everyone and are not worth a card.
 */
@Composable
fun PosturalBanner(state: PosturalBannerUi, onOpen: () -> Unit) {
    val colors = HeartlineTheme.colors
    val pattern = state.status == PosturalStatus.PATTERN
    RoundedCard(Modifier.gutter(), onClick = onOpen, contentPadding = 0.dp) {
        CardRow(
            stringResource(if (pattern) R.string.postural_banner_title else R.string.postural_banner_easing_title),
            subtitle = if (pattern) {
                stringResource(R.string.postural_banner_text, state.days, state.restBpm ?: 0, state.peakBpm ?: 0)
            } else {
                stringResource(R.string.postural_banner_easing_text)
            },
            leading = { IconBadge(if (pattern) Icons.Rounded.NorthEast else Icons.Rounded.SouthEast, if (pattern) colors.statusWarn else colors.heartRate) },
            trailing = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = colors.onSurfaceVariant) },
            onClick = onOpen,
            subtitleMaxLines = 3,
        )
    }
}

/**
 * The report of rises after getting up: what was seen, a month of it, each rise (with how the
 * wearer felt), what to do and what else raises the rate. Worded as a pattern, never a condition.
 */
@Composable
fun PosturalReportScreen(
    state: PosturalReportUi,
    onBack: (() -> Unit)? = null,
    onOpenRise: (Long) -> Unit = {},
    onShare: (() -> Unit)? = null,
    listState: LazyListState = rememberLazyListState(),
) {
    val colors = HeartlineTheme.colors
    ReachabilityScaffold(
        title = stringResource(R.string.postural_report_title),
        onBack = onBack,
        actions = { if (onShare != null && state.rises.isNotEmpty()) ShareAction(onShare) },
        listState = listState,
    ) {
        if (state.rises.isEmpty()) {
            item { EmptyCard(stringResource(R.string.postural_empty_title), stringResource(R.string.postural_empty_body)) }
            item { HowMeasuredCard() }
            return@ReachabilityScaffold
        }
        item(key = "summary") { SummaryCard(state) }
        item(key = "chart") {
            RoundedCard(Modifier.gutter()) {
                CardTitle(stringResource(R.string.postural_chart_title))
                Text(stringResource(R.string.postural_chart_caption), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                RangeBarChart(
                    state.chart,
                    slots = PosturalSummaries.CHART_DAYS,
                    color = colors.statusWarn,
                    xLabels = state.chartLabels,
                    label = { i -> state.chartDates.getOrElse(i) { "" } },
                    contentDescription = stringResource(R.string.postural_chart_title),
                )
            }
        }
        item(key = "doctor") {
            RoundedCard(Modifier.gutter()) {
                CardTitle(stringResource(R.string.postural_doctor_title))
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.postural_doctor_body), style = MaterialTheme.typography.bodyMedium, color = colors.onBackground)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.postural_urgent), style = MaterialTheme.typography.bodySmall, color = colors.statusAlert)
                if (onShare != null) {
                    Spacer(Modifier.height(16.dp))
                    TonalPillButton(stringResource(R.string.postural_share), onClick = onShare, color = colors.heartRate)
                }
            }
        }
        item { SectionHeader(stringResource(R.string.postural_rises_title)) }
        item(key = "rises") {
            RoundedCard(Modifier.gutter(), contentPadding = 0.dp) {
                state.rises.forEachIndexed { i, rise ->
                    CardRow(
                        "${rise.date} · ${rise.time}",
                        subtitle = riseLine(rise),
                        leading = { IconBadge(Icons.Rounded.NorthEast, colors.statusWarn) },
                        trailing = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = colors.onSurfaceVariant) },
                        showDivider = i < state.rises.lastIndex,
                        onClick = { onOpenRise(rise.startMs) },
                    )
                }
            }
        }
        item(key = "raise") { RaisesCard() }
        item(key = "how") { HowMeasuredCard() }
        item {
            Text(
                stringResource(R.string.postural_not_diagnosis),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 28.dp),
            )
        }
    }
}

@Composable
private fun SummaryCard(state: PosturalReportUi) {
    val colors = HeartlineTheme.colors
    RoundedCard(Modifier.gutter()) {
        val pattern = state.status == PosturalStatus.PATTERN
        Text(
            stringResource(if (pattern) R.string.postural_status_pattern else R.string.postural_status_easing),
            style = MaterialTheme.typography.labelLarge,
            color = if (pattern) colors.statusWarn else colors.heartRate,
        )
        Spacer(Modifier.height(8.dp))
        if (state.medianRiseBpm != null) {
            MetricValue("+${state.medianRiseBpm}", stringResource(R.string.unit_bpm), large = true)
            Text(
                stringResource(R.string.postural_typical, state.medianRestBpm ?: 0, state.medianPeakBpm ?: 0),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onBackground,
            )
        } else {
            Text(stringResource(R.string.postural_none_recent), style = MaterialTheme.typography.bodyMedium, color = colors.onBackground)
        }
        Spacer(Modifier.height(16.dp))
        Row {
            StatColumn(stringResource(R.string.postural_days_label), stringResource(R.string.postural_days_value, state.days, PosturalSummaries.PATTERN_DAYS), Modifier.weight(1f))
            StatColumn(stringResource(R.string.postural_rises_label), "${state.riseCount}", Modifier.weight(1f))
            StatColumn(stringResource(R.string.postural_before_label), stringResource(R.string.postural_days_value, state.previousDays, PosturalSummaries.PATTERN_DAYS), Modifier.weight(1f))
        }
    }
}

@Composable
private fun riseLine(rise: RiseUi): String = listOfNotNull(
    stringResource(R.string.postural_rise_row, rise.restBpm, rise.peakBpm, rise.riseBpm),
    rise.symptoms.takeIf { it.isNotEmpty() }?.map { stringResource(it.label) }?.joinToString(", ") ?: stringResource(R.string.postural_add_feeling),
).joinToString(" · ")

@Composable
private fun RaisesCard() {
    val colors = HeartlineTheme.colors
    RoundedCard(Modifier.gutter()) {
        CardTitle(stringResource(R.string.postural_raises_title))
        Spacer(Modifier.height(8.dp))
        listOf(R.string.postural_raise_caffeine, R.string.postural_raise_heat, R.string.postural_raise_fluids, R.string.postural_raise_sleep, R.string.postural_raise_rest).forEach {
            Text("•  " + stringResource(it), style = MaterialTheme.typography.bodyMedium, color = colors.onBackground, modifier = Modifier.padding(vertical = 2.dp))
        }
    }
}

@Composable
private fun HowMeasuredCard() {
    val colors = HeartlineTheme.colors
    RoundedCard(Modifier.gutter()) {
        CardTitle(stringResource(R.string.postural_how_title))
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.postural_how_body), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    }
}

/** One rise: its curve from the still minutes before to the minutes after, and how the wearer felt. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RiseDetailScreen(
    rise: RiseUi,
    curve: List<CurvePoint>,
    minRiseBpm: Int,
    onBack: (() -> Unit)? = null,
    onSymptoms: (List<Symptom>) -> Unit = {},
) {
    val colors = HeartlineTheme.colors
    ReachabilityScaffold(title = stringResource(R.string.postural_rise_title), subtitle = "${rise.date} · ${rise.time}", onBack = onBack) {
        item(key = "curve") {
            RoundedCard(Modifier.gutter()) {
                MetricValue("+${rise.riseBpm}", stringResource(R.string.unit_bpm), large = true)
                Text(stringResource(R.string.postural_rise_summary, rise.restBpm, rise.peakBpm), style = MaterialTheme.typography.bodyMedium, color = colors.onBackground)
                Spacer(Modifier.height(16.dp))
                RiseCurveChart(curve, rise.restBpm, rise.restBpm + minRiseBpm, Modifier.fillMaxWidth().height(200.dp))
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Legend(colors.onSurfaceVariant, stringResource(R.string.postural_legend_rest))
                    Spacer(Modifier.width(16.dp))
                    Legend(colors.statusWarn, stringResource(R.string.postural_legend_threshold, minRiseBpm))
                }
            }
        }
        item(key = "stats") {
            RoundedCard(Modifier.gutter()) {
                Row {
                    StatColumn(stringResource(R.string.postural_rest_label), stringResource(R.string.ecg_bpm_value, rise.restBpm), Modifier.weight(1f))
                    StatColumn(stringResource(R.string.postural_held_label), stringResource(R.string.ecg_bpm_value, rise.peakBpm), Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(if (rise.measured) R.string.postural_measured_probe else R.string.postural_measured_passive),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
        item(key = "feel") {
            RoundedCard(Modifier.gutter()) {
                CardTitle(stringResource(R.string.postural_feel_title))
                Text(stringResource(R.string.postural_feel_caption), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PosturalSummaries.OFFERED.forEach { symptom ->
                        val on = symptom in rise.symptoms
                        Chip(stringResource(symptom.label), on) { onSymptoms(if (on) rise.symptoms - symptom else rise.symptoms + symptom) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.postural_urgent), style = MaterialTheme.typography.bodySmall, color = colors.statusAlert)
            }
        }
    }
}

@Composable
private fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.width(16.dp).height(2.dp)) {
            drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = size.height, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f)))
        }
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = HeartlineTheme.colors.onSurfaceVariant)
    }
}

/**
 * Heart rate from 15 minutes before getting up to 10 minutes after: the still readings before, the
 * watch's readings after (joined), a mark where the wearer got up, the resting rate and the rise
 * that counts as dashed guides.
 */
@Composable
fun RiseCurveChart(points: List<CurvePoint>, restBpm: Int, thresholdBpm: Int, modifier: Modifier = Modifier) {
    val colors = HeartlineTheme.colors
    val measurer = rememberTextMeasurer()
    val axis = MaterialTheme.typography.labelSmall.copy(color = colors.onSurfaceVariant)
    val gotUp = stringResource(R.string.postural_got_up)
    val description = stringResource(R.string.postural_curve_a11y, restBpm, points.maxOfOrNull { it.bpm } ?: restBpm)
    Canvas(modifier.semantics { contentDescription = description }) {
        val lo = ((minOf(points.minOfOrNull { it.bpm } ?: restBpm, restBpm) - 10) / 10) * 10
        val hi = ((maxOf(points.maxOfOrNull { it.bpm } ?: thresholdBpm, thresholdBpm) + 19) / 10) * 10
        val axisWidth = 36.dp.toPx()
        val bottom = size.height - 20.dp.toPx()
        val width = size.width - axisWidth
        fun x(seconds: Int) = (seconds + 15 * 60) / (25f * 60) * width
        fun y(bpm: Int) = bottom - (bpm - lo).toFloat() / (hi - lo) * bottom
        val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
        // Round-number grid with labels at the right.
        val step = if (hi - lo > 60) 20 else 10
        for (v in (lo..hi step step)) {
            drawLine(colors.divider, Offset(0f, y(v)), Offset(width, y(v)), strokeWidth = 1f)
            axisText(measurer, "$v", Offset(width + 6.dp.toPx(), y(v) - 7.dp.toPx()), axis)
        }
        listOf(-15, -10, -5, 0, 5, 10).forEach { m -> axisText(measurer, if (m > 0) "+$m" else "$m", Offset(x(m * 60) - 8.dp.toPx(), bottom + 4.dp.toPx()), axis) }
        drawLine(colors.onSurfaceVariant, Offset(0f, y(restBpm)), Offset(width, y(restBpm)), strokeWidth = 2f, pathEffect = dash)
        drawLine(colors.statusWarn, Offset(0f, y(thresholdBpm)), Offset(width, y(thresholdBpm)), strokeWidth = 2f, pathEffect = dash)
        drawLine(colors.onBackground, Offset(x(0), 0f), Offset(x(0), bottom), strokeWidth = 2f)
        axisText(measurer, gotUp, Offset(x(0) + 4.dp.toPx(), 0f), axis)
        val shown = points.filter { it.seconds in -15 * 60..10 * 60 }
        shown.filter { it.seconds >= 0 }.zipWithNext { a, b ->
            // Joined within a slice; the gaps between slices stay open.
            if (b.seconds - a.seconds <= 30) drawLine(colors.heartRate, Offset(x(a.seconds), y(a.bpm)), Offset(x(b.seconds), y(b.bpm)), strokeWidth = 3.dp.toPx())
        }
        shown.forEach { drawCircle(if (it.seconds < 0) colors.onSurfaceVariant else colors.heartRate, 3.5.dp.toPx(), Offset(x(it.seconds), y(it.bpm))) }
    }
}

private fun DrawScope.axisText(measurer: TextMeasurer, text: String, at: Offset, style: TextStyle) = drawText(measurer, text, at, style)

