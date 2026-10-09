// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.ui.measure

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import com.heartline.bubbles.ParticleGlobeOrb
import com.heartline.bubbles.BubblePhase
import com.heartline.bubbles.BubbleStyle
import com.heartline.bubbles.rememberShownProgress
import com.heartline.phone.R
import com.heartline.phone.measure.MeasureIssue
import com.heartline.phone.measure.MeasureStep
import com.heartline.phone.measure.MeasureUi
import com.heartline.phone.ui.components.PillButton
import com.heartline.phone.ui.components.ReachabilityScaffold
import com.heartline.phone.ui.components.RoundedCard
import com.heartline.phone.ui.components.TonalPillButton
import com.heartline.phone.ui.components.gutter
import com.heartline.phone.ui.theme.HeartlineTheme
import com.heartline.phone.widget.title
import com.heartline.shared.model.Metric
import com.heartline.shared.model.RecordSummary
import com.heartline.shared.sync.MeasureHint
import java.util.Locale

/** What the screen can ask for besides cancel and retry. */
data class MeasureActions(
    val onCancel: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onDone: () -> Unit = {},
    /** Measure the old way: open the measurement on the watch and start it there. */
    val onOpenOnWatch: () -> Unit = {},
    val onUpdates: () -> Unit = {},
    val onCalibrate: () -> Unit = {},
    val onDevModeHelp: () -> Unit = {},
    val onProfile: () -> Unit = {},
)

/**
 * A measurement run from the phone. The watch measures (and shows the same steps); this screen
 * follows it with the particle globe, as on the watch: the points gather while the watch gets
 * ready, light up from the bottom with the progress, beat with the pulse, tremble in amber on a
 * hint and swell once on the result.
 */
@Composable
fun MeasureScreen(
    ui: MeasureUi?,
    metric: Metric,
    actions: MeasureActions,
    onBack: () -> Unit,
    fahrenheit: Boolean = false,
    animate: Boolean = true,
    frameMs: Long? = null,
) {
    val colors = HeartlineTheme.colors
    val accent = colors.metric(metric)
    val step = ui?.step ?: MeasureStep.CHECKING
    val active = ui?.active ?: true
    var confirmCancel by rememberSaveable { mutableStateOf(false) }
    // Leaving while the watch measures asks first (the measurement would be lost).
    BackHandler(enabled = active) { confirmCancel = true }
    val view = LocalView.current
    DisposableEffect(active) {
        view.keepScreenOn = active
        onDispose { view.keepScreenOn = false }
    }

    ReachabilityScaffold(
        title = stringResource(metric.title),
        subtitle = stringResource(R.string.measure_subtitle),
        onBack = { if (active) confirmCancel = true else onBack() },
    ) {
        item(key = "globe") {
            // The percentage glides between the watch's messages (predicted while Bluetooth holds
            // one back), holds on a hint and never jumps back.
            val paused = ui?.hint != null && ui.hint != MeasureHint.NONE
            val shown = if (animate && frameMs == null) {
                rememberShownProgress(ui?.progress ?: 0f, paused, done = step == MeasureStep.RESULT) { now -> ui?.progressAt(now) ?: 0f }
            } else {
                ui?.progress ?: 0f
            }
            val phase = when (step) {
                MeasureStep.CHECKING, MeasureStep.OPENING, MeasureStep.WAITING_WATCH, MeasureStep.PREPARING -> BubblePhase.FORMING
                MeasureStep.MEASURING -> if (ui?.hint != null && ui.hint != MeasureHint.NONE) BubblePhase.HINT else BubblePhase.MEASURING
                MeasureStep.RESULT -> BubblePhase.SUCCESS
                MeasureStep.FAILED -> BubblePhase.FAILED
            }
            ParticleGlobeOrb(
                style = BubbleStyle.of(metric),
                phase = phase,
                accent = accent,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                progress = shown,
                bpm = ui?.live ?: (ui?.summary as? RecordSummary.HeartRate)?.bpm,
                seed = metric.ordinal + 3,
                animate = animate,
                maxFps = 60,
                // The phone has room around the globe: orbits, drifting dust and heartbeat ripples fill it.
                fit = 2.55f,
                dark = colors.isDark,
                surroundings = true,
                frameMs = frameMs?.let { 3_400L },
                phaseFrameMs = frameMs?.let { 2_000L },
            ) {
                // Animates only between kinds of content (measuring, result), not on every percent.
                AnimatedContent(
                    targetState = step == MeasureStep.RESULT,
                    transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.7f)) togetherWith fadeOut() },
                    label = "globe-value",
                ) { _ ->
                    val center = centerText(ui, fahrenheit, shown)
                    if (center != null) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(center.first, style = onGlobe(MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.SemiBold), colors.isDark, colors.onBackground))
                            center.second?.let { Text(it, style = onGlobe(MaterialTheme.typography.titleMedium, colors.isDark, colors.onBackground)) }
                        }
                    }
                }
            }
        }
        item(key = "status") {
            Text(
                statusText(ui, metric),
                style = MaterialTheme.typography.titleMedium,
                color = if (ui?.hint != null && ui.hint != MeasureHint.NONE && step == MeasureStep.MEASURING) colors.statusWarn else colors.onBackground,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().gutter().semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        when (step) {
            MeasureStep.RESULT -> item(key = "result") {
                RoundedCard(Modifier.gutter()) {
                    Text(stringResource(R.string.measure_saved), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    resultDetail(ui?.summary)?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TonalPillButton(stringResource(R.string.measure_again), onClick = actions.onRetry, modifier = Modifier.weight(1f), color = accent)
                        PillButton(stringResource(R.string.action_done), onClick = actions.onDone, modifier = Modifier.weight(1f), color = accent)
                    }
                }
            }
            MeasureStep.FAILED -> item(key = "failed") {
                FailedCard(ui?.issue ?: MeasureIssue.OTHER, accent, actions)
            }
            else -> {
                item(key = "tips") {
                    RoundedCard(Modifier.gutter()) {
                        Text(stringResource(R.string.measure_tips_title), style = MaterialTheme.typography.titleSmall, color = colors.onBackground)
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(tips(metric)), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    }
                }
                item(key = "cancel") {
                    TonalPillButton(
                        stringResource(R.string.action_cancel),
                        onClick = { confirmCancel = true },
                        modifier = Modifier.gutter().fillMaxWidth().widthIn(max = 420.dp),
                        color = accent,
                    )
                }
            }
        }
    }

    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text(stringResource(R.string.measure_cancel_title)) },
            text = { Text(stringResource(R.string.measure_cancel_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmCancel = false
                    actions.onCancel()
                    onBack()
                }) { Text(stringResource(R.string.measure_cancel_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text(stringResource(R.string.measure_keep_going)) } },
        )
    }
}

@Composable
private fun FailedCard(issue: MeasureIssue, accent: Color, actions: MeasureActions) {
    val colors = HeartlineTheme.colors
    RoundedCard(Modifier.gutter()) {
        Text(stringResource(issueTitle(issue)), style = MaterialTheme.typography.titleSmall, color = colors.onBackground)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(issueBody(issue)), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (issue) {
                MeasureIssue.NEEDS_CALIBRATION -> PillButton(stringResource(R.string.measure_action_calibrate), actions.onCalibrate, Modifier.fillMaxWidth(), accent)
                MeasureIssue.WATCH_OUTDATED -> PillButton(stringResource(R.string.home_watch_version_action), actions.onUpdates, Modifier.fillMaxWidth(), accent)
                MeasureIssue.SDK_POLICY -> PillButton(stringResource(R.string.measure_action_dev_mode), actions.onDevModeHelp, Modifier.fillMaxWidth(), accent)
                MeasureIssue.NEEDS_PROFILE -> PillButton(stringResource(R.string.measure_action_profile), actions.onProfile, Modifier.fillMaxWidth(), accent)
                MeasureIssue.CANCELLED -> Unit
                else -> PillButton(stringResource(R.string.measure_try_again), actions.onRetry, Modifier.fillMaxWidth(), accent)
            }
            if (issue != MeasureIssue.NO_WATCH && issue != MeasureIssue.NEEDS_CALIBRATION) {
                TonalPillButton(stringResource(R.string.measure_open_on_watch), actions.onOpenOnWatch, Modifier.fillMaxWidth(), accent)
            }
        }
    }
}

/**
 * Text over the globe, readable over its points: white with a dark shadow on a dark background,
 * ink with a light halo on a light one.
 */
internal fun onGlobe(style: TextStyle, dark: Boolean, ink: Color) = if (dark) {
    style.copy(color = Color.White, shadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 3f), 14f))
} else {
    style.copy(color = ink, shadow = Shadow(Color.White, Offset.Zero, 18f))
}

/** The number in the globe and its line under it: the percentage (with the live pulse for heart rate), or the result. */
@Composable
private fun centerText(ui: MeasureUi?, fahrenheit: Boolean, shown: Float): Pair<String, String?>? {
    ui ?: return null
    val percent = (shown.coerceIn(0f, 1f) * 100f + 1e-3f).toInt()
    return when (ui.step) {
        MeasureStep.MEASURING -> when {
            ui.metric == Metric.HEART_RATE && ui.live != null -> "${ui.live}" to stringResource(R.string.measure_bpm_percent, percent)
            else -> "$percent" to stringResource(R.string.unit_percent)
        }
        MeasureStep.RESULT -> when (val s = ui.summary) {
            is RecordSummary.HeartRate -> "${s.bpm}" to stringResource(R.string.unit_bpm)
            is RecordSummary.Spo2 -> "${s.percent}" to stringResource(R.string.unit_percent)
            is RecordSummary.SkinTemperature -> {
                val value = if (fahrenheit) s.skinCelsius * 9f / 5f + 32f else s.skinCelsius
                String.format(Locale.getDefault(), "%.1f", value) to if (fahrenheit) "°F" else "°C"
            }
            is RecordSummary.Stress -> "${s.score}" to stringResource(R.string.measure_stress_unit)
            is RecordSummary.BloodPressure -> "${s.systolic}/${s.diastolic}" to stringResource(R.string.unit_mmhg)
            // A calibration round has no value: the cuff reading is entered next.
            null -> "✓" to null
            else -> null
        }
        else -> null
    }
}

@Composable
private fun statusText(ui: MeasureUi?, metric: Metric): String {
    ui ?: return stringResource(R.string.measure_checking)
    return when (ui.step) {
        MeasureStep.CHECKING -> stringResource(R.string.measure_checking)
        MeasureStep.OPENING, MeasureStep.WAITING_WATCH -> stringResource(R.string.measure_waiting_watch)
        MeasureStep.PREPARING -> stringResource(R.string.measure_preparing)
        MeasureStep.MEASURING -> when (ui.hint) {
            MeasureHint.HOLD_STILL, MeasureHint.MOVING -> stringResource(R.string.measure_hint_still)
            MeasureHint.WRIST_CONTACT -> stringResource(R.string.measure_hint_wrist)
            MeasureHint.LOW_SIGNAL -> stringResource(R.string.measure_hint_signal)
            MeasureHint.NONE -> when {
                ui.progress >= 0.97f -> stringResource(R.string.measure_finishing)
                metric == Metric.STRESS -> stringResource(R.string.measure_breathe)
                else -> stringResource(R.string.measure_measuring)
            }
        }
        MeasureStep.RESULT -> stringResource(if (ui.round != null) R.string.measure_round_done else R.string.measure_done)
        MeasureStep.FAILED -> stringResource(issueTitle(ui.issue ?: MeasureIssue.OTHER))
    }
}

@Composable
private fun resultDetail(summary: RecordSummary?): String? = when (summary) {
    is RecordSummary.HeartRate -> stringResource(R.string.measure_hr_range, summary.minBpm, summary.maxBpm)
    is RecordSummary.BloodPressure -> summary.pulse?.let { stringResource(R.string.measure_bp_pulse, it) }
    is RecordSummary.Spo2 -> summary.heartRate?.let { stringResource(R.string.measure_bp_pulse, it) }
    else -> null
}

private fun tips(metric: Metric): Int = when (metric) {
    Metric.SPO2 -> R.string.measure_tips_spo2
    Metric.BLOOD_PRESSURE -> R.string.measure_tips_bp
    Metric.STRESS -> R.string.measure_tips_stress
    Metric.SKIN_TEMPERATURE -> R.string.measure_tips_temp
    else -> R.string.measure_tips_hr
}

private fun issueTitle(issue: MeasureIssue): Int = when (issue) {
    MeasureIssue.NO_WATCH -> R.string.measure_issue_no_watch
    MeasureIssue.WATCH_OUTDATED -> R.string.measure_issue_outdated
    MeasureIssue.NO_RESPONSE -> R.string.measure_issue_no_response
    MeasureIssue.LOST -> R.string.measure_issue_lost
    MeasureIssue.BUSY -> R.string.measure_issue_busy
    MeasureIssue.NEEDS_SETUP -> R.string.measure_issue_setup
    MeasureIssue.PERMISSION -> R.string.measure_issue_permission
    MeasureIssue.SDK_POLICY -> R.string.measure_issue_dev_mode
    MeasureIssue.NEEDS_CALIBRATION -> R.string.measure_issue_calibration
    MeasureIssue.NEEDS_PROFILE -> R.string.measure_issue_profile
    MeasureIssue.SENSOR -> R.string.measure_issue_sensor
    MeasureIssue.LOW_SIGNAL -> R.string.measure_issue_signal
    MeasureIssue.WRIST_CONTACT -> R.string.measure_issue_wrist
    MeasureIssue.MOVING -> R.string.measure_issue_moving
    MeasureIssue.WATCH_LEFT -> R.string.measure_issue_watch_left
    MeasureIssue.CANCELLED -> R.string.measure_issue_cancelled
    MeasureIssue.OTHER -> R.string.measure_issue_other
}

private fun issueBody(issue: MeasureIssue): Int = when (issue) {
    MeasureIssue.NO_WATCH -> R.string.open_result_no_watch
    MeasureIssue.WATCH_OUTDATED -> R.string.measure_issue_outdated_body
    MeasureIssue.NO_RESPONSE, MeasureIssue.LOST -> R.string.measure_issue_no_response_body
    MeasureIssue.BUSY -> R.string.measure_issue_busy_body
    MeasureIssue.NEEDS_SETUP -> R.string.measure_issue_setup_body
    MeasureIssue.PERMISSION -> R.string.measure_issue_permission_body
    MeasureIssue.SDK_POLICY -> R.string.measure_issue_dev_mode_body
    MeasureIssue.NEEDS_CALIBRATION -> R.string.measure_issue_calibration_body
    MeasureIssue.NEEDS_PROFILE -> R.string.measure_issue_profile_body
    MeasureIssue.SENSOR -> R.string.measure_issue_sensor_body
    MeasureIssue.LOW_SIGNAL, MeasureIssue.WRIST_CONTACT -> R.string.measure_issue_signal_body
    MeasureIssue.MOVING -> R.string.measure_issue_moving_body
    MeasureIssue.WATCH_LEFT -> R.string.measure_issue_watch_left_body
    MeasureIssue.CANCELLED -> R.string.measure_issue_cancelled_body
    MeasureIssue.OTHER -> R.string.measure_issue_other_body
}
