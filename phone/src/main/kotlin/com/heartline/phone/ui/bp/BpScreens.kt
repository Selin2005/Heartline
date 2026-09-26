// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.ui.bp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.heartline.phone.R
import com.heartline.phone.ui.components.ShareAction
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import com.heartline.phone.ui.components.CardRow
import com.heartline.phone.ui.components.CardTitle
import com.heartline.phone.ui.components.IconBadge
import com.heartline.phone.ui.components.MetricValue
import com.heartline.phone.ui.components.PillButton
import com.heartline.phone.ui.components.ReachabilityScaffold
import com.heartline.phone.ui.components.RoundedCard
import com.heartline.phone.ui.components.SectionHeader
import com.heartline.phone.ui.components.StatColumn
import com.heartline.phone.ui.components.TonalPillButton
import com.heartline.phone.ui.components.describe
import com.heartline.phone.ui.components.gutter
import com.heartline.phone.ui.components.icon
import com.heartline.phone.ui.model.BpHomeUi
import com.heartline.phone.ui.model.BpReadingUi
import com.heartline.phone.ui.model.CalibrationUi
import com.heartline.phone.ui.settings.OneUiSwitch
import com.heartline.phone.ui.theme.HeartlineColors
import com.heartline.phone.ui.theme.HeartlineTheme
import com.heartline.shared.bp.BpCategory
import com.heartline.shared.bp.BpDrift
import com.heartline.shared.bp.BpProfile
import com.heartline.shared.bp.BpSafety
import com.heartline.shared.model.Metric

fun HeartlineColors.bpCategory(category: BpCategory): Color = when (category) {
    BpCategory.NORMAL -> statusNormal
    BpCategory.ELEVATED -> stress
    BpCategory.HIGH_STAGE_1 -> temp
    BpCategory.HIGH_STAGE_2, BpCategory.CRISIS -> statusAlert
}

val BpCategory.label: Int
    get() = when (this) {
        BpCategory.NORMAL -> R.string.bp_normal
        BpCategory.ELEVATED -> R.string.bp_elevated
        BpCategory.HIGH_STAGE_1 -> R.string.bp_stage1
        BpCategory.HIGH_STAGE_2 -> R.string.bp_stage2
        BpCategory.CRISIS -> R.string.bp_crisis
    }

@Composable
fun BpHomeScreen(
    state: BpHomeUi,
    onBack: (() -> Unit)? = null,
    onCalibrate: () -> Unit = {},
    onMeasureOnWatch: (() -> Unit)? = null,
    onValidate: (Int?, Int?) -> Boolean = { _, _ -> true },
    listState: LazyListState = rememberLazyListState(),
    onShare: (() -> Unit)? = null,
    onProfileChange: (BpProfile) -> Unit = {},
) {
    var validating by remember { mutableStateOf(false) }
    if (validating) ValidationDialog(onDismiss = { validating = false }, onSave = { s, d -> onValidate(s, d).also { ok -> if (ok) validating = false } })
    var editingProfile by remember { mutableStateOf(false) }
    if (editingProfile) {
        BpProfileDialog(state.profile, onDismiss = { editingProfile = false }, onSave = {
            onProfileChange(it)
            editingProfile = false
        })
    }
    val colors = HeartlineTheme.colors
    ReachabilityScaffold(
        title = stringResource(R.string.metric_bp),
        subtitle = state.latest?.let { stringResource(R.string.bp_last_measured, "${it.date} ${it.time}") },
        onBack = onBack,
        listState = listState,
        actions = { if (onShare != null && state.latest != null) ShareAction(onShare) },
    ) {
        item {
            RoundedCard(Modifier.gutter()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (state.calibrated) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = if (state.calibrated) colors.statusNormal else colors.statusWarn,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(if (state.calibrated) R.string.bp_calibrated else R.string.bp_needs_calibration),
                            style = MaterialTheme.typography.titleSmall,
                            color = colors.onBackground,
                        )
                        Text(
                            if (state.calibrated) stringResource(R.string.bp_days_left, state.daysLeft) else stringResource(R.string.bp_calibration_why),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
                state.calibrationSpan?.let { span ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        pluralStringResource(R.plurals.bp_calibration_span, state.cuffChecksInCalibration, span.first, span.last, state.cuffChecksInCalibration),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(14.dp))
                if (state.calibrated) {
                    onMeasureOnWatch?.let { measure ->
                        PillButton(stringResource(R.string.action_measure_on_watch), onClick = measure, color = colors.bp)
                        Spacer(Modifier.height(8.dp))
                    }
                    TonalPillButton(stringResource(R.string.bp_recalibrate), onClick = onCalibrate)
                    Spacer(Modifier.height(8.dp))
                    TonalPillButton(stringResource(R.string.bp_profile_title), onClick = { editingProfile = true })
                } else {
                    // The watch never measures without a valid calibration: this is the only way forward.
                    PillButton(stringResource(R.string.bp_calibrate_first), onClick = onCalibrate, color = colors.bp)
                }
            }
        }
        if (state.drift != null || state.recalibrate) {
            item {
                RoundedCard(Modifier.gutter()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = colors.statusWarn)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            stringResource(
                                when {
                                    state.recalibrate -> R.string.bp_drift_recalibrate
                                    state.drift == BpDrift.Direction.HIGHER -> R.string.bp_drift_higher
                                    else -> R.string.bp_drift_lower
                                },
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onBackground,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    if (state.recalibrate) {
                        TonalPillButton(stringResource(R.string.bp_recalibrate), onClick = onCalibrate)
                    } else if (state.canValidateLatest) {
                        TonalPillButton(stringResource(R.string.bp_accuracy_compare), onClick = { validating = true })
                    } else {
                        Text(stringResource(R.string.bp_drift_how), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                }
            }
        }
        state.latest?.let { latest ->
            item {
                RoundedCard(Modifier.gutter()) {
                    CardTitle(stringResource(R.string.bp_latest))
                    Spacer(Modifier.height(8.dp))
                    if (latest.rangeOnly && latest.uncertainty != null) {
                        // Too uncertain for one number (mostly a pulse change): the range, no category.
                        val u = latest.uncertainty
                        MetricValue(
                            "${latest.systolic - u}–${latest.systolic + u}",
                            stringResource(R.string.bp_range_diastolic, latest.diastolic - u * 7 / 10, latest.diastolic + u * 7 / 10),
                            large = true,
                        )
                    } else {
                        MetricValue(
                            "${latest.systolic}/${latest.diastolic}",
                            latest.uncertainty?.let { stringResource(R.string.bp_unit_uncertainty, it) } ?: stringResource(R.string.unit_mmhg),
                            large = true,
                        )
                    }
                    latest.pulse?.let {
                        Text(stringResource(R.string.bp_pulse, it), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    }
                    ReadingNotes(latest)
                    if (!latest.rangeOnly) {
                        Spacer(Modifier.height(14.dp))
                        CategoryScale(latest.category)
                    }
                }
            }
            item {
                RoundedCard(Modifier.gutter()) {
                    CardTitle(stringResource(R.string.bp_trend))
                    Spacer(Modifier.height(12.dp))
                    BpTrendChart(
                        state.readings.take(14).reversed(),
                        Modifier.fillMaxWidth().height(140.dp).describe(stringResource(R.string.a11y_bp_chart, state.readings.take(14).size)),
                    )
                    Spacer(Modifier.height(14.dp))
                    Row {
                        StatColumn(stringResource(R.string.bp_avg7), state.average7?.let { "${it.first}/${it.second}" } ?: "–", Modifier.weight(1f))
                        StatColumn(stringResource(R.string.bp_avg30), state.average30?.let { "${it.first}/${it.second}" } ?: "–", Modifier.weight(1f))
                    }
                }
            }
            item {
                RoundedCard(Modifier.gutter()) {
                    CardTitle(stringResource(R.string.bp_accuracy_title))
                    Spacer(Modifier.height(6.dp))
                    val acc = state.accuracy
                    if (acc == null) {
                        Text(stringResource(R.string.bp_accuracy_empty), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    } else {
                        Row {
                            StatColumn(stringResource(R.string.bp_accuracy_sys), signed(acc.meanDiffSys, acc.sdSys), Modifier.weight(1f))
                            StatColumn(stringResource(R.string.bp_accuracy_dia), signed(acc.meanDiffDia, acc.sdDia), Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            pluralStringResource(R.plurals.bp_accuracy_summary, acc.count, acc.count, acc.within10Percent),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                        state.personalRange80?.let {
                            Text(stringResource(R.string.bp_personal_range, it), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                        Text(stringResource(R.string.bp_accuracy_learns), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                    if (state.canValidateLatest) {
                        Spacer(Modifier.height(12.dp))
                        TonalPillButton(stringResource(R.string.bp_accuracy_compare), onClick = { validating = true })
                    }
                }
            }
            item { SectionHeader(stringResource(R.string.bp_history)) }
            item {
                RoundedCard(Modifier.gutter(), contentPadding = 0.dp) {
                    val rows = state.readings.take(8)
                    rows.forEachIndexed { i, r ->
                        CardRow(
                            "${r.systolic}/${r.diastolic} ${stringResource(R.string.unit_mmhg)}",
                            subtitle = listOfNotNull(
                                r.date,
                                r.time,
                                r.uncertainty?.let { "±$it" },
                                r.pulse?.let { stringResource(R.string.bp_pulse, it) },
                                stringResource(R.string.bp_tag_beyond).takeIf { r.beyondCalibration && !r.confirmed },
                                stringResource(R.string.bp_tag_confirmed).takeIf { r.confirmed },
                                stringResource(R.string.bp_tag_refined).takeIf { r.refined },
                                stringResource(R.string.bp_tag_range).takeIf { r.rangeOnly },
                            ).joinToString(" · "),
                            leading = { Box(Modifier.size(10.dp).clip(CircleShape).background(colors.bpCategory(r.category))) },
                            trailing = {
                                Text(stringResource(r.category.label), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                            },
                            showDivider = i < rows.lastIndex,
                            dividerStart = 46.dp,
                        )
                    }
                }
            }
        }
        item {
            Text(
                stringResource(R.string.bp_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 28.dp),
            )
        }
    }
}

/** What the latest reading needs the user to know: refined, beyond the calibration, confirmed, very high or low. */
@Composable
private fun ReadingNotes(r: BpReadingUi) {
    val colors = HeartlineTheme.colors
    @Composable
    fun note(text: String, color: Color) = Text(text, style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.padding(top = 4.dp))
    if (r.refined) note(stringResource(R.string.bp_refined_note, r.watchSystolic ?: 0, r.watchDiastolic ?: 0), colors.onSurfaceVariant)
    if (r.rangeOnly) note(stringResource(R.string.bp_range_note), colors.statusWarn)
    if (r.confirmed) {
        note(stringResource(R.string.bp_confirmed), colors.onSurfaceVariant)
    } else if (r.beyondCalibration) {
        note(stringResource(R.string.bp_beyond_calibration), colors.statusWarn)
    }
    when (r.safety) {
        BpSafety.VERY_HIGH -> if (!r.rangeOnly) note(stringResource(R.string.bp_safety_high), colors.statusAlert)
        BpSafety.LOW -> note(stringResource(R.string.bp_safety_low), colors.statusWarn)
        BpSafety.NONE -> Unit
    }
}

private fun signed(mean: Double, sd: Double) = "%+.0f ± %.0f".format(mean, sd)

/** Cuff reading taken right after the latest watch reading. */
@Composable
private fun ValidationDialog(onDismiss: () -> Unit, onSave: (Int?, Int?) -> Boolean) {
    var sys by remember { mutableStateOf("") }
    var dia by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bp_accuracy_compare)) },
        text = {
            Column {
                Text(stringResource(R.string.bp_accuracy_dialog_body), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(sys, { sys = it.filter(Char::isDigit).take(3) }, label = { Text(stringResource(R.string.bp_sys)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                    OutlinedTextField(dia, { dia = it.filter(Char::isDigit).take(3) }, label = { Text(stringResource(R.string.bp_dia)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                }
                if (error) Text(stringResource(R.string.bp_input_error), style = MaterialTheme.typography.bodySmall, color = HeartlineTheme.colors.statusAlert)
            }
        },
        confirmButton = { TextButton(onClick = { error = !onSave(sys.toIntOrNull(), dia.toIntOrNull()) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Segmented AHA scale with a marker on the current category (Samsung Health style). */
@Composable
fun CategoryScale(category: BpCategory) {
    val colors = HeartlineTheme.colors
    val categories = BpCategory.entries.filter { it != BpCategory.CRISIS }
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
            categories.forEach { c ->
                val selected = c == category || (category == BpCategory.CRISIS && c == BpCategory.HIGH_STAGE_2)
                Box(
                    Modifier
                        .weight(1f)
                        .height(if (selected) 10.dp else 6.dp)
                        .clip(RoundedCornerShape(50))
                        .background(colors.bpCategory(c).copy(alpha = if (selected) 1f else 0.35f)),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(category.label), style = MaterialTheme.typography.titleSmall, color = colors.bpCategory(category))
    }
}

/** Each reading as a vertical bar from diastolic to systolic. */
@Composable
fun BpTrendChart(readings: List<BpReadingUi>, modifier: Modifier = Modifier) {
    val colors = HeartlineTheme.colors
    Canvas(modifier) {
        for (i in 0..3) drawLine(colors.divider, Offset(0f, size.height * i / 3), Offset(size.width, size.height * i / 3), 1f)
        if (readings.isEmpty()) return@Canvas
        val lo = (readings.minOf { it.diastolic } - 10).toFloat()
        val hi = (readings.maxOf { it.systolic } + 10).toFloat()
        fun y(v: Int) = size.height - (v - lo) / (hi - lo) * size.height
        val slot = size.width / maxOf(readings.size, 7)
        readings.forEachIndexed { i, r ->
            val x = i * slot + slot / 2
            drawLine(colors.bp, Offset(x, y(r.diastolic)), Offset(x, y(r.systolic)), strokeWidth = slot * 0.35f, cap = StrokeCap.Round)
        }
        // Reference line at 120 mmHg.
        if (120f in lo..hi) {
            drawRoundRect(colors.statusNormal.copy(alpha = 0.5f), Offset(0f, y(120) - 1f), Size(size.width, 2f), CornerRadius(1f))
        }
    }
}

@Composable
fun BpCalibrationScreen(
    state: CalibrationUi,
    onBack: (() -> Unit)? = null,
    onStart: () -> Unit = {},
    onSubmit: (Int?, Int?, Int?) -> Unit = { _, _, _ -> },
    onDone: () -> Unit = {},
    onProfileChange: (BpProfile) -> Unit = {},
    onAddStanding: () -> Unit = {},
    onFinish: () -> Unit = {},
) {
    val colors = HeartlineTheme.colors
    ReachabilityScaffold(
        title = stringResource(R.string.bp_calibration_title),
        subtitle = stringResource(R.string.bp_calibration_progress, state.completedRounds),
        onBack = onBack,
    ) {
        item {
            Row(Modifier.gutter().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(3) { i ->
                    Box(
                        Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(50))
                            .background(if (i < state.completedRounds) colors.bp else colors.surfaceVariant),
                    )
                }
            }
        }
        item {
            RoundedCard(Modifier.gutter()) {
                when (state.phase) {
                    CalibrationUi.Phase.INTRO -> {
                        CardTitle(stringResource(R.string.bp_calibration_intro_title))
                        Spacer(Modifier.height(8.dp))
                        listOf(R.string.bp_cal_step_1, R.string.bp_cal_step_2, R.string.bp_cal_step_3, R.string.bp_cal_step_4).forEach {
                            Row(Modifier.padding(vertical = 5.dp)) {
                                Box(Modifier.padding(top = 7.dp).size(6.dp).clip(CircleShape).background(colors.bp))
                                Spacer(Modifier.width(12.dp))
                                Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = colors.onBackground)
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        PillButton(stringResource(R.string.action_start), onClick = onStart, color = colors.bp)
                    }
                    CalibrationUi.Phase.WAITING_FOR_WATCH -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconBadge(Metric.BLOOD_PRESSURE.icon, colors.bp)
                            Spacer(Modifier.width(14.dp))
                            Text(
                                if (state.standingRound) stringResource(R.string.bp_round_standing) else stringResource(R.string.bp_round, state.round),
                                style = MaterialTheme.typography.titleMedium,
                                color = colors.onBackground,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        if (state.standingRound) {
                            Text(stringResource(R.string.bp_standing_instruction), style = MaterialTheme.typography.bodyMedium, color = colors.onBackground)
                            Spacer(Modifier.height(8.dp))
                        }
                        Text(stringResource(R.string.bp_waiting_watch), style = MaterialTheme.typography.bodyMedium, color = colors.onBackground)
                        Spacer(Modifier.height(16.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(20.dp), color = colors.bp, strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(stringResource(R.string.bp_waiting_status), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                    }
                    CalibrationUi.Phase.ENTER_CUFF -> CuffEntry(state, onSubmit)
                    CalibrationUi.Phase.OFFER_STANDING -> {
                        CardTitle(stringResource(R.string.bp_standing_offer_title))
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.bp_standing_offer_body), style = MaterialTheme.typography.bodyMedium, color = colors.onBackground)
                        Spacer(Modifier.height(16.dp))
                        PillButton(stringResource(R.string.bp_standing_add), onClick = onAddStanding, color = colors.bp)
                        Spacer(Modifier.height(8.dp))
                        TonalPillButton(stringResource(R.string.bp_finish), onClick = onFinish)
                    }
                    CalibrationUi.Phase.DONE -> {
                        Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = colors.statusNormal, modifier = Modifier.size(40.dp))
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.bp_calibration_done), style = MaterialTheme.typography.titleMedium, color = colors.onBackground)
                        Text(stringResource(R.string.bp_calibration_done_body), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                        Spacer(Modifier.height(16.dp))
                        PillButton(stringResource(R.string.action_done), onClick = onDone, color = colors.bp)
                    }
                }
            }
        }
        if (state.phase == CalibrationUi.Phase.INTRO) {
            item {
                RoundedCard(Modifier.gutter(), contentPadding = 0.dp) {
                    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp)) {
                        CardTitle(stringResource(R.string.bp_profile_title))
                        Text(stringResource(R.string.bp_profile_why), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                    BpProfileEditor(state.profile, onProfileChange)
                }
            }
        }
    }
}

/**
 * Conditions and medicines that change how the pulse wave relates to pressure (algorithm 5): a
 * rate-setting drug or pacemaker takes the pulse rate out of the model, atrial fibrillation
 * lengthens the recording, POTS makes the "unsteady" check more sensitive, pregnancy is flagged
 * as not validated, and diabetes or kidney disease shortens the calibration's validity.
 */
@Composable
fun BpProfileEditor(profile: BpProfile, onChange: (BpProfile) -> Unit) {
    val rows = listOf(
        Triple(R.string.bp_profile_beta_blocker, profile.betaBlocker) { v: Boolean -> profile.copy(betaBlocker = v) },
        Triple(R.string.bp_profile_pacemaker, profile.pacemaker) { v: Boolean -> profile.copy(pacemaker = v) },
        Triple(R.string.bp_profile_af, profile.atrialFibrillation) { v: Boolean -> profile.copy(atrialFibrillation = v) },
        Triple(R.string.bp_profile_orthostatic, profile.orthostaticIntolerance) { v: Boolean -> profile.copy(orthostaticIntolerance = v) },
        Triple(R.string.bp_profile_diabetes, profile.diabetesOrKidney) { v: Boolean -> profile.copy(diabetesOrKidney = v) },
        Triple(R.string.bp_profile_pregnancy, profile.pregnancy) { v: Boolean -> profile.copy(pregnancy = v) },
    )
    Column {
        rows.forEachIndexed { i, (label, checked, update) ->
            CardRow(
                stringResource(label),
                trailing = { OneUiSwitch(checked) { onChange(update(it)) } },
                showDivider = i < rows.lastIndex,
                onClick = { onChange(update(!checked)) },
            )
        }
    }
}

@Composable
private fun BpProfileDialog(profile: BpProfile, onDismiss: () -> Unit, onSave: (BpProfile) -> Unit) {
    var edited by remember(profile) { mutableStateOf(profile) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bp_profile_title)) },
        text = {
            Column {
                Text(stringResource(R.string.bp_profile_why), style = MaterialTheme.typography.bodySmall)
                BpProfileEditor(edited) { edited = it }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(edited) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun CuffEntry(state: CalibrationUi, onSubmit: (Int?, Int?, Int?) -> Unit) {
    val colors = HeartlineTheme.colors
    var sys by remember(state.round) { mutableStateOf("") }
    var dia by remember(state.round) { mutableStateOf("") }
    var pulse by remember(state.round) { mutableStateOf("") }
    CardTitle(stringResource(R.string.bp_enter_cuff, state.round))
    Spacer(Modifier.height(4.dp))
    Text(stringResource(R.string.bp_enter_cuff_hint), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        NumberField(stringResource(R.string.bp_sys), sys, { sys = it }, Modifier.weight(1f))
        NumberField(stringResource(R.string.bp_dia), dia, { dia = it }, Modifier.weight(1f))
        NumberField(stringResource(R.string.bp_pulse_short), pulse, { pulse = it }, Modifier.weight(1f))
    }
    if (state.inputError) {
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.bp_input_error), style = MaterialTheme.typography.bodySmall, color = colors.statusAlert)
    }
    Spacer(Modifier.height(16.dp))
    PillButton(
        stringResource(if (state.round < 3) R.string.bp_next_round else R.string.bp_finish),
        onClick = { onSubmit(sys.toIntOrNull(), dia.toIntOrNull(), pulse.toIntOrNull()) },
        color = colors.bp,
    )
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier) {
    val colors = HeartlineTheme.colors
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter(Char::isDigit).take(3)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = colors.bp,
            unfocusedBorderColor = colors.divider,
            focusedLabelColor = colors.bp,
            unfocusedContainerColor = colors.surfaceVariant.copy(alpha = 0.4f),
            focusedContainerColor = colors.surfaceVariant.copy(alpha = 0.4f),
        ),
        modifier = modifier,
    )
}
