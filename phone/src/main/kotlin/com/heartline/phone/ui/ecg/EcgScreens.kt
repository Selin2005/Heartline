package com.heartline.phone.ui.ecg

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.heartline.phone.R
import com.heartline.phone.ui.components.CardRow
import com.heartline.phone.ui.components.CardTitle
import com.heartline.phone.ui.components.Chip
import com.heartline.phone.ui.components.EcgStrip
import com.heartline.phone.ui.components.LinkButton
import com.heartline.phone.ui.components.MiniWave
import com.heartline.phone.ui.components.PillButton
import com.heartline.phone.ui.components.ReachabilityScaffold
import com.heartline.phone.ui.components.ResultBadge
import com.heartline.phone.ui.components.RoundedCard
import com.heartline.phone.ui.components.SectionHeader
import com.heartline.phone.ui.components.StatColumn
import com.heartline.phone.ui.components.TonalPillButton
import com.heartline.phone.ui.components.explanation
import com.heartline.phone.ui.components.gutter
import com.heartline.phone.ui.components.label
import com.heartline.phone.ui.model.EcgListState
import com.heartline.phone.ui.model.EcgRecordUi
import com.heartline.phone.ui.theme.HeartlineTheme
import com.heartline.shared.model.EcgResult

@Composable
fun EcgHomeScreen(
    state: EcgListState,
    onBack: (() -> Unit)? = null,
    onOpenRecord: (String) -> Unit = {},
    onViewAll: () -> Unit = {},
    onRecordOnWatch: () -> Unit = {},
) {
    val latest = state.latest
    ReachabilityScaffold(
        title = stringResource(R.string.metric_ecg),
        subtitle = latest?.let { stringResource(R.string.ecg_last_recorded, "${it.date} ${it.time}") },
        onBack = onBack,
    ) {
        if (latest == null) {
            if (!state.loading) item { EmptyCard(stringResource(R.string.ecg_empty_title), stringResource(R.string.ecg_empty_body)) }
        } else {
            item {
                RoundedCard(Modifier.gutter(), onClick = { onOpenRecord(latest.id) }) {
                    CardTitle(stringResource(R.string.ecg_latest))
                    Spacer(Modifier.height(14.dp))
                    ResultBadge(latest.result, large = true)
                    latest.averageBpm?.let {
                        Text(
                            stringResource(R.string.ecg_bpm_value, it),
                            style = MaterialTheme.typography.bodyMedium,
                            color = HeartlineTheme.colors.onSurfaceVariant,
                            modifier = Modifier.padding(start = 20.dp),
                        )
                    }
                    latest.samples?.let { samples ->
                        Spacer(Modifier.height(16.dp))
                        MiniWave(
                            samples.copyOfRange(0, minOf(samples.size, latest.sampleRateHz * 6)),
                            HeartlineTheme.colors.ecg,
                            Modifier.fillMaxWidth().height(56.dp),
                        )
                    }
                }
            }
        }
        item { TonalPillButton(stringResource(R.string.action_record_on_watch), onClick = onRecordOnWatch, modifier = Modifier.gutter(), color = HeartlineTheme.colors.ecg) }
        item {
            RoundedCard(Modifier.gutter()) {
                CardTitle(stringResource(R.string.ecg_how_to))
                Spacer(Modifier.height(12.dp))
                listOf(R.string.ecg_step_1, R.string.ecg_step_2, R.string.ecg_step_3).forEachIndexed { i, step ->
                    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                        StepNumber(i + 1)
                        Spacer(Modifier.width(14.dp))
                        Text(
                            stringResource(step),
                            style = MaterialTheme.typography.bodyMedium,
                            color = HeartlineTheme.colors.onBackground,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }
        if (state.records.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.ecg_recent)) }
            item {
                RoundedCard(Modifier.gutter(), contentPadding = 0.dp) {
                    val recent = state.records.take(4)
                    recent.forEachIndexed { i, record ->
                        EcgRecordRow(record, showDivider = i < recent.lastIndex, onClick = { onOpenRecord(record.id) })
                    }
                }
            }
            item {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    LinkButton(stringResource(R.string.action_view_all), onClick = onViewAll)
                }
            }
        }
    }
}

@Composable
fun EmptyCard(title: String, body: String, modifier: Modifier = Modifier) {
    RoundedCard(modifier.gutter()) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = HeartlineTheme.colors.onBackground)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = HeartlineTheme.colors.onSurfaceVariant)
    }
}

@Composable
private fun StepNumber(n: Int) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(26.dp).clip(CircleShape).background(HeartlineTheme.colors.ecg),
    ) {
        Text("$n", style = MaterialTheme.typography.labelMedium, color = androidx.compose.ui.graphics.Color.White)
    }
}

@Composable
fun EcgRecordRow(record: EcgRecordUi, showDivider: Boolean, onClick: () -> Unit) {
    val colors = HeartlineTheme.colors
    CardRow(
        title = stringResource(record.result.label),
        subtitle = listOfNotNull(record.date, record.time, record.averageBpm?.let { stringResource(R.string.ecg_bpm_value, it) })
            .joinToString(" · "),
        leading = {
            Box(Modifier.size(10.dp).clip(CircleShape).background(colors.severity(record.result.severity)))
        },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (record.symptoms.isNotEmpty()) {
                    Icon(Icons.Rounded.EditNote, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = colors.onSurfaceVariant)
            }
        },
        showDivider = showDivider,
        dividerStart = 46.dp,
        onClick = onClick,
    )
}

private enum class HistoryFilter { ALL, SINUS, AFIB, OTHER }

@Composable
fun EcgHistoryScreen(
    state: EcgListState,
    onBack: (() -> Unit)? = null,
    onOpenRecord: (String) -> Unit = {},
) {
    val records = state.records
    var filter by remember { mutableStateOf(HistoryFilter.ALL) }
    val filtered = records.filter {
        when (filter) {
            HistoryFilter.ALL -> true
            HistoryFilter.SINUS -> it.result == EcgResult.SINUS_RHYTHM
            HistoryFilter.AFIB -> it.result == EcgResult.AFIB_SIGNS
            HistoryFilter.OTHER -> it.result != EcgResult.SINUS_RHYTHM && it.result != EcgResult.AFIB_SIGNS
        }
    }
    val byMonth = filtered.groupBy { it.month }
    ReachabilityScaffold(title = stringResource(R.string.ecg_history_title), onBack = onBack) {
        if (records.isEmpty() && !state.loading) {
            item { EmptyCard(stringResource(R.string.ecg_empty_title), stringResource(R.string.ecg_empty_body)) }
            return@ReachabilityScaffold
        }
        item {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Chip(stringResource(R.string.ecg_filter_all), filter == HistoryFilter.ALL) { filter = HistoryFilter.ALL }
                Chip(stringResource(R.string.ecg_result_sinus), filter == HistoryFilter.SINUS) { filter = HistoryFilter.SINUS }
                Chip(stringResource(R.string.ecg_result_afib), filter == HistoryFilter.AFIB) { filter = HistoryFilter.AFIB }
                Chip(stringResource(R.string.ecg_filter_other), filter == HistoryFilter.OTHER) { filter = HistoryFilter.OTHER }
            }
        }
        byMonth.forEach { (month, monthRecords) ->
            item(key = month) { SectionHeader(month) }
            item(key = "$month-card") {
                RoundedCard(Modifier.gutter(), contentPadding = 0.dp) {
                    monthRecords.forEachIndexed { i, record ->
                        EcgRecordRow(record, showDivider = i < monthRecords.lastIndex, onClick = { onOpenRecord(record.id) })
                    }
                }
            }
        }
    }
}

@Composable
fun EcgDetailScreen(
    record: EcgRecordUi,
    onBack: (() -> Unit)? = null,
    onSharePdf: () -> Unit = {},
    onDelete: () -> Unit = {},
    onEditSymptoms: () -> Unit = {},
) {
    val colors = HeartlineTheme.colors
    ReachabilityScaffold(
        title = stringResource(record.result.label),
        subtitle = "${record.date} · ${record.time}",
        onBack = onBack,
    ) {
        item {
            RoundedCard(Modifier.gutter()) {
                Row {
                    StatColumn(
                        stringResource(R.string.ecg_avg_hr),
                        record.averageBpm?.let { stringResource(R.string.ecg_bpm_value, it) } ?: "–",
                        Modifier.weight(1f),
                    )
                    StatColumn(stringResource(R.string.ecg_duration), "${record.durationSec} ${stringResource(R.string.unit_seconds)}", Modifier.weight(1f))
                }
            }
        }
        val samples = record.samples
        if (samples != null) {
            item {
                EcgStripCard(samples, record.sampleRateHz)
            }
        }
        item {
            RoundedCard(Modifier.gutter()) {
                CardTitle(stringResource(R.string.ecg_symptoms)) {
                    LinkButton(stringResource(R.string.action_edit), onClick = onEditSymptoms)
                }
                Spacer(Modifier.height(10.dp))
                if (record.symptoms.isEmpty()) {
                    Text(stringResource(R.string.ecg_no_symptoms), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        record.symptoms.forEach {
                            Text(
                                stringResource(it.label),
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.onBackground,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(colors.surfaceVariant)
                                    .padding(horizontal = 12.dp, vertical = 7.dp),
                            )
                        }
                    }
                }
            }
        }
        item {
            RoundedCard(Modifier.gutter()) {
                CardTitle(stringResource(R.string.ecg_about_result))
                Spacer(Modifier.height(8.dp))
                Text(stringResource(record.result.explanation), style = MaterialTheme.typography.bodyMedium, color = colors.onBackground)
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.not_a_diagnosis), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
        item {
            Column(Modifier.gutter().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                PillButton(stringResource(R.string.action_share_pdf), onClick = onSharePdf)
                Spacer(Modifier.height(4.dp))
                LinkButton(stringResource(R.string.action_delete), onClick = onDelete, color = colors.statusAlert)
            }
        }
    }
}


/** The full recording on ECG paper; scrolls horizontally like a printed strip. */
@Composable
fun EcgStripCard(samples: FloatArray, sampleRateHz: Int, modifier: Modifier = Modifier) {
    RoundedCard(modifier.gutter(), contentPadding = 0.dp) {
        Box(
            Modifier
                .padding(12.dp)
                .clip(RoundedCornerShape(12.dp))
                .horizontalScroll(rememberScrollState()),
        ) {
            EcgStrip(
                samples,
                sampleRateHz,
                mmSize = 3.4.dp,
                contentDescription = stringResource(R.string.a11y_ecg_strip, samples.size / sampleRateHz),
            )
        }
        Text(
            stringResource(R.string.ecg_strip_caption),
            style = MaterialTheme.typography.bodySmall,
            color = HeartlineTheme.colors.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, bottom = 14.dp),
        )
    }
}
