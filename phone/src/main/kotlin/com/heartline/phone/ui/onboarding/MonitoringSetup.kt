// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.heartline.phone.R
import com.heartline.phone.ui.components.CardRow
import com.heartline.phone.ui.components.Chip
import com.heartline.phone.ui.components.IconBadge
import com.heartline.phone.ui.components.LinkButton
import com.heartline.phone.ui.components.PillButton
import com.heartline.phone.ui.components.ReachabilityScaffold
import com.heartline.phone.ui.components.RoundedCard
import com.heartline.phone.ui.components.gutter
import com.heartline.phone.ui.settings.label
import com.heartline.phone.ui.settings.OneUiSwitch
import com.heartline.phone.ui.theme.HeartlineTheme
import com.heartline.shared.hr.AlertSensitivity
import com.heartline.shared.hr.Answer
import com.heartline.shared.hr.HealthContext
import com.heartline.shared.hr.MonitorSettings
import com.heartline.shared.model.Metric

/**
 * The monitoring setup (first run and once after an update): the wearer chooses health monitoring
 * and its parts, answers the required health questions, and may fine-tune the rest; our defaults
 * stand for anything skipped. Pure, so the effect of each answer is unit tested.
 */
object MonitoringSetup {
    /** A usual-hours choice: start and end minute of the day. */
    data class Hours(val start: Int, val end: Int)

    val QUIET_CHOICES = listOf(Hours(22 * 60, 7 * 60), Hours(23 * 60, 8 * 60), Hours(0, 0))
    val SLEEP_CHOICES = listOf(Hours(22 * 60, 6 * 60), Hours(23 * 60, 7 * 60), Hours(0, 8 * 60))

    /** The setup's answers on top of [base] (the switches follow the answers: [MonitorSettings.normalized]). */
    fun apply(base: MonitorSettings, monitoring: Boolean, draft: MonitorSettings): MonitorSettings = base.copy(
        heartAlerts = draft.heartAlerts,
        spo2Monitoring = draft.spo2Monitoring,
        skinTempMonitoring = draft.skinTempMonitoring,
        stressMonitoring = draft.stressMonitoring,
        stressNotifications = draft.stressNotifications,
        spo2InSleep = draft.spo2InSleep,
        alertSensitivity = draft.alertSensitivity,
        health = draft.health,
        quietStartMinute = draft.quietStartMinute,
        quietEndMinute = draft.quietEndMinute,
        sleepStartMinute = draft.sleepStartMinute,
        sleepEndMinute = draft.sleepEndMinute,
    ).withMonitoring(monitoring)

    /** What the answers changed, as string resources for the summary. */
    fun adjustments(s: MonitorSettings): List<Int> = buildList {
        val h = s.health
        if (h.rateLowering) add(R.string.setup_adjust_medicine)
        if (h.af) add(R.string.setup_adjust_af)
        if (h.device) add(R.string.setup_adjust_device)
        if (h.lung) add(R.string.setup_adjust_lung)
        if (h.enduranceTraining) add(R.string.setup_adjust_endurance)
        if (h.pregnant) add(R.string.setup_adjust_pregnant)
    }

    fun hoursLabel(h: Hours) = if (h.start == h.end) null else "%d:00–%d:00".format(h.start / 60, h.end / 60)
}

private enum class Step { MONITORING, PARTS, HEALTH, OPTIONAL, SUMMARY }

/**
 * The setup pages. [initial]: the current settings (an update or a later edit keeps them as the
 * starting point). [onFinish] gets the new settings. [startStep] is for screenshots.
 */
@Composable
fun MonitoringSetupFlow(
    initial: MonitorSettings,
    onFinish: (MonitorSettings) -> Unit,
    onBack: (() -> Unit)? = null,
    startStep: Int = 0,
) {
    var step by rememberSaveable { mutableIntStateOf(startStep) }
    // Unanswered until chosen: monitoring on or not is required, even on an update.
    var monitoring by remember { mutableStateOf(if (startStep > 0 || initial.health.answered) initial.heartMonitoring else null) }
    var draft by remember { mutableStateOf(initial) }
    val back: (() -> Unit)? = if (step > 0) {
        { step = if (Step.entries[step] == Step.SUMMARY && monitoring == false) Step.MONITORING.ordinal else step - 1 }
    } else {
        onBack
    }
    when (Step.entries[step]) {
        Step.MONITORING -> MonitoringPage(monitoring, back) { on ->
            monitoring = on
            step = if (on) Step.PARTS.ordinal else Step.SUMMARY.ordinal
        }
        Step.PARTS -> PartsPage(draft, back, { draft = it }) { step = Step.HEALTH.ordinal }
        Step.HEALTH -> HealthPage(draft.health, back, { draft = draft.copy(health = it) }) { step = Step.OPTIONAL.ordinal }
        Step.OPTIONAL -> OptionalPage(draft, back, { draft = it }) { step = Step.SUMMARY.ordinal }
        Step.SUMMARY -> {
            val result = MonitoringSetup.apply(initial, monitoring == true, draft)
            SummaryPage(result, back) { onFinish(result) }
        }
    }
}

@Composable
private fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = HeartlineTheme.colors.onSurfaceVariant, modifier = Modifier.gutter())
}

@Composable
private fun MonitoringPage(choice: Boolean?, onBack: (() -> Unit)?, onChoose: (Boolean) -> Unit) {
    ReachabilityScaffold(title = stringResource(R.string.setup_monitoring_title), subtitle = stringResource(R.string.setup_required), onBack = onBack) {
        item { Body(stringResource(R.string.setup_monitoring_body)) }
        item { Spacer(Modifier.height(20.dp)) }
        item {
            Column(Modifier.gutter(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(stringResource(R.string.setup_monitoring_on), onClick = { onChoose(true) })
                LinkButton(stringResource(R.string.setup_monitoring_later), onClick = { onChoose(false) })
                if (choice == false) Text(stringResource(R.string.setup_monitoring_later_note), style = MaterialTheme.typography.bodySmall, color = HeartlineTheme.colors.onSurfaceVariant)
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
        item { Body(stringResource(R.string.not_a_diagnosis)) }
    }
}

@Composable
private fun PartsPage(draft: MonitorSettings, onBack: (() -> Unit)?, onDraft: (MonitorSettings) -> Unit, onNext: () -> Unit) {
    val colors = HeartlineTheme.colors
    ReachabilityScaffold(title = stringResource(R.string.setup_parts_title), subtitle = stringResource(R.string.setup_parts_subtitle), onBack = onBack) {
        item {
            RoundedCard(Modifier.gutter(), contentPadding = 0.dp) {
                CardRow(
                    stringResource(R.string.settings_part_heart),
                    subtitle = stringResource(R.string.setup_part_heart),
                    leading = { IconBadge(Icons.Rounded.MonitorHeart, colors.heartRate) },
                    trailing = { OneUiSwitch(draft.heartAlerts) { onDraft(draft.copy(heartAlerts = it)) } },
                    showDivider = true,
                    subtitleMaxLines = 4,
                )
                CardRow(
                    stringResource(R.string.settings_part_spo2),
                    subtitle = stringResource(R.string.setup_part_spo2),
                    leading = { IconBadge(Icons.Rounded.WaterDrop, colors.spo2) },
                    trailing = { OneUiSwitch(draft.spo2Monitoring) { onDraft(draft.copy(spo2Monitoring = it)) } },
                    showDivider = true,
                    subtitleMaxLines = 4,
                )
                CardRow(
                    stringResource(R.string.settings_part_temp),
                    subtitle = stringResource(R.string.setup_part_temp),
                    leading = { IconBadge(Icons.Rounded.Thermostat, colors.temp) },
                    trailing = { OneUiSwitch(draft.skinTempMonitoring) { onDraft(draft.copy(skinTempMonitoring = it)) } },
                    showDivider = true,
                    subtitleMaxLines = 4,
                )
                CardRow(
                    stringResource(R.string.settings_part_stress),
                    subtitle = stringResource(R.string.setup_part_stress),
                    leading = { IconBadge(Icons.Rounded.SelfImprovement, colors.metric(Metric.STRESS)) },
                    trailing = { OneUiSwitch(draft.stressMonitoring) { onDraft(draft.copy(stressMonitoring = it)) } },
                    subtitleMaxLines = 4,
                )
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
        item { PillButton(stringResource(R.string.action_continue), onClick = onNext, modifier = Modifier.gutter()) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnswerRow(question: Int, answer: Answer?, onAnswer: (Answer) -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(stringResource(question), style = MaterialTheme.typography.bodyLarge, color = HeartlineTheme.colors.onBackground)
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Answer.YES to R.string.answer_yes, Answer.NO to R.string.answer_no, Answer.UNSURE to R.string.answer_unsure).forEach { (a, label) ->
                Chip(stringResource(label), selected = answer == a) { onAnswer(a) }
            }
        }
    }
}

@Composable
private fun HealthPage(health: HealthContext, onBack: (() -> Unit)?, onHealth: (HealthContext) -> Unit, onNext: () -> Unit) {
    ReachabilityScaffold(title = stringResource(R.string.setup_health_title), subtitle = stringResource(R.string.setup_required), onBack = onBack) {
        item { Body(stringResource(R.string.setup_health_body)) }
        item { Spacer(Modifier.height(12.dp)) }
        item {
            RoundedCard(Modifier.gutter(), contentPadding = 0.dp) {
                AnswerRow(R.string.setup_q_medicine, health.rateLoweringMedicine) { onHealth(health.copy(rateLoweringMedicine = it)) }
                AnswerRow(R.string.setup_q_af, health.atrialFibrillation) { onHealth(health.copy(atrialFibrillation = it)) }
                AnswerRow(R.string.setup_q_device, health.heartDevice) { onHealth(health.copy(heartDevice = it)) }
                AnswerRow(R.string.setup_q_lung, health.lungCondition) { onHealth(health.copy(lungCondition = it)) }
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
        item {
            Column(Modifier.gutter()) {
                if (health.answered) {
                    PillButton(stringResource(R.string.action_continue), onClick = onNext)
                } else {
                    Text(stringResource(R.string.setup_health_needed), style = MaterialTheme.typography.bodySmall, color = HeartlineTheme.colors.onSurfaceVariant)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OptionalPage(draft: MonitorSettings, onBack: (() -> Unit)?, onDraft: (MonitorSettings) -> Unit, onNext: () -> Unit) {
    val colors = HeartlineTheme.colors
    @Composable
    fun Switch(title: Int, subtitle: Int, checked: Boolean, divider: Boolean = true, onChange: (Boolean) -> Unit) = CardRow(
        stringResource(title),
        subtitle = stringResource(subtitle),
        trailing = { OneUiSwitch(checked, onChange) },
        showDivider = divider,
        subtitleMaxLines = 4,
    )

    @Composable
    fun Hours(title: Int, choices: List<MonitoringSetup.Hours>, current: MonitoringSetup.Hours, onPick: (MonitoringSetup.Hours) -> Unit) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge, color = colors.onBackground)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                choices.forEach { c -> Chip(MonitoringSetup.hoursLabel(c) ?: stringResource(R.string.setup_quiet_none), selected = c == current) { onPick(c) } }
            }
        }
    }
    ReachabilityScaffold(title = stringResource(R.string.setup_optional_title), subtitle = stringResource(R.string.setup_optional_subtitle), onBack = onBack) {
        item {
            RoundedCard(Modifier.gutter(), contentPadding = 0.dp) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Text(stringResource(R.string.settings_sensitivity), style = MaterialTheme.typography.bodyLarge, color = colors.onBackground)
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AlertSensitivity.entries.forEach { s -> Chip(stringResource(s.label), selected = draft.alertSensitivity == s) { onDraft(draft.copy(alertSensitivity = s)) } }
                    }
                }
                Switch(R.string.setup_endurance, R.string.setup_endurance_text, draft.health.enduranceTraining) { onDraft(draft.copy(health = draft.health.copy(enduranceTraining = it))) }
                Switch(R.string.setup_pregnant, R.string.setup_pregnant_text, draft.health.pregnant) { onDraft(draft.copy(health = draft.health.copy(pregnant = it))) }
                Switch(R.string.setup_stress_notifications, R.string.setup_stress_notifications_text, draft.stressNotifications) { onDraft(draft.copy(stressNotifications = it)) }
                Switch(R.string.setup_spo2_sleep, R.string.setup_spo2_sleep_text, draft.spo2InSleep) { onDraft(draft.copy(spo2InSleep = it)) }
                Hours(R.string.setup_sleep_hours, MonitoringSetup.SLEEP_CHOICES, MonitoringSetup.Hours(draft.sleepStartMinute, draft.sleepEndMinute)) {
                    onDraft(draft.copy(sleepStartMinute = it.start, sleepEndMinute = it.end))
                }
                Hours(R.string.setup_quiet_hours, MonitoringSetup.QUIET_CHOICES, MonitoringSetup.Hours(draft.quietStartMinute, draft.quietEndMinute)) {
                    onDraft(draft.copy(quietStartMinute = it.start, quietEndMinute = it.end))
                }
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
        item {
            Column(Modifier.gutter(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(stringResource(R.string.action_continue), onClick = onNext)
                LinkButton(stringResource(R.string.setup_skip), onClick = onNext)
            }
        }
    }
}

@Composable
private fun SummaryPage(result: MonitorSettings, onBack: (() -> Unit)?, onStart: () -> Unit) {
    val colors = HeartlineTheme.colors
    val on = stringResource(R.string.state_on)
    val off = stringResource(R.string.state_off)
    ReachabilityScaffold(title = stringResource(R.string.setup_summary_title), onBack = onBack) {
        item {
            RoundedCard(Modifier.gutter(), contentPadding = 0.dp) {
                CardRow(
                    stringResource(R.string.settings_heart_monitoring),
                    subtitle = if (result.heartMonitoring) on else stringResource(R.string.setup_summary_off),
                    leading = { IconBadge(Icons.Rounded.NotificationsActive, colors.heartRate) },
                    showDivider = result.heartMonitoring,
                    subtitleMaxLines = 3,
                )
                if (result.heartMonitoring) {
                    listOf(
                        R.string.settings_part_heart to result.heartActive,
                        R.string.settings_part_spo2 to result.spo2Active,
                        R.string.settings_part_temp to result.skinTempActive,
                        R.string.settings_part_stress to result.stressActive,
                    ).forEachIndexed { i, (name, active) ->
                        CardRow(stringResource(name), subtitle = if (active) on else off, leading = { Spacer(Modifier.size(40.dp)) }, showDivider = i < 3)
                    }
                }
            }
        }
        val adjustments = MonitoringSetup.adjustments(result)
        if (adjustments.isNotEmpty()) {
            item { Spacer(Modifier.height(16.dp)) }
            item { Text(stringResource(R.string.setup_summary_adjusted), style = MaterialTheme.typography.titleSmall, color = colors.onBackground, modifier = Modifier.gutter()) }
            adjustments.forEach { item { Body("• " + stringResource(it)) } }
        }
        item { Spacer(Modifier.height(12.dp)) }
        item { Body(stringResource(R.string.setup_summary_later)) }
        item { Spacer(Modifier.height(20.dp)) }
        item { PillButton(stringResource(R.string.setup_start), onClick = onStart, modifier = Modifier.gutter()) }
    }
}

/** The setup with its view model: [onDone] after the settings are saved and sent to the watch. */
@Composable
fun MonitoringSetupRoute(onDone: () -> Unit, onBack: (() -> Unit)? = null) {
    val vm: com.heartline.phone.ui.model.MonitoringSetupViewModel = org.koin.androidx.compose.koinViewModel()
    val current by vm.current.collectAsStateWithLifecycle()
    current?.let { MonitoringSetupFlow(it, onFinish = { result -> vm.finish(result, onDone) }, onBack = onBack) }
}
