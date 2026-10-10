// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.ui.screens

import com.heartline.wear.ui.components.CenteredValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.heartline.shared.bp.BpCalibration
import com.heartline.shared.bp.BpCategory
import com.heartline.shared.bp.BpSafety
import com.heartline.shared.bp.BpChannel
import com.heartline.shared.bp.HemodynamicState
import com.heartline.wear.bp.BpPhase
import com.heartline.shared.design.Palette
import com.heartline.shared.model.Metric
import com.heartline.wear.R
import com.heartline.wear.ui.components.GoodResult
import com.heartline.wear.ui.components.PersonalNote
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import com.heartline.wear.ui.components.BeatingHeart
import com.heartline.wear.ui.components.MeasureFace
import com.heartline.wear.ui.components.onBubble
import com.heartline.bubbles.BubblePhase
import com.heartline.wear.ui.components.SweepTrace
import com.heartline.wear.ui.components.ActionScreen
import com.heartline.wear.ui.components.isSmallRound
import com.heartline.wear.ui.theme.WearColors

val BpCategory.label: Int
    get() = when (this) {
        BpCategory.NORMAL -> R.string.bp_normal
        BpCategory.ELEVATED -> R.string.bp_elevated
        BpCategory.HIGH_STAGE_1 -> R.string.bp_stage1
        BpCategory.HIGH_STAGE_2 -> R.string.bp_stage2
        BpCategory.CRISIS -> R.string.bp_crisis
    }

val BpCategory.color: Color
    get() = Color(
        when (this) {
            BpCategory.NORMAL -> Palette.Dark.STATUS_NORMAL
            BpCategory.ELEVATED -> Palette.Dark.STRESS
            BpCategory.HIGH_STAGE_1 -> Palette.Dark.TEMP
            BpCategory.HIGH_STAGE_2, BpCategory.CRISIS -> Palette.Dark.STATUS_ALERT
        },
    )

@Composable
private fun Badge(icon: ImageVector, tint: Color) {
    val small = isSmallRound()
    Box(Modifier.size(if (small) 32.dp else 40.dp).clip(CircleShape).background(tint.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(if (small) 20.dp else 24.dp))
    }
}

/** "Calibration 2 of 3", or the optional standing round after the 3 seated ones. */
@Composable
private fun calibrationTitle(round: Int) =
    if (round == BpCalibration.STANDING_ROUND) stringResource(R.string.bp_calibration_standing) else stringResource(R.string.bp_calibration_round, round)

@Composable
private fun Body(text: String) = Text(
    text,
    style = if (isSmallRound()) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
    textAlign = TextAlign.Center,
    color = WearColors.onSurfaceVariant,
    modifier = Modifier.padding(top = 4.dp),
)

/**
 * Before measuring (or a calibration round when [calibrationRound] is set). [onPrecise], when the
 * watch has the ECG sensor, offers precise mode: finger on the key with the arm-raise maneuver.
 */
@Composable
fun BpInstructionScreen(calibrationRound: Int? = null, onStart: () -> Unit = {}, onPrecise: (() -> Unit)? = null, precise: Boolean = false) {
    ActionScreen(stringResource(R.string.action_start), onStart) {
        Badge(Icons.Rounded.Speed, WearColors.metric(Metric.BLOOD_PRESSURE))
        Text(
            calibrationRound?.let { calibrationTitle(it) } ?: stringResource(R.string.metric_bp),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
        Body(
            stringResource(
                when {
                    precise -> R.string.bp_instruction_precise
                    calibrationRound == BpCalibration.STANDING_ROUND -> R.string.bp_instruction_standing
                    else -> R.string.bp_instruction
                },
            ),
        )
        onPrecise?.let {
            androidx.wear.compose.material3.FilledTonalButton(onClick = it, modifier = Modifier.padding(top = 6.dp)) {
                Text(stringResource(R.string.bp_precise_mode), maxLines = 1)
            }
            Note(stringResource(R.string.bp_precise_hint), WearColors.onSurfaceVariant)
        }
    }
}

/**
 * The reading, always with its ±. A reading beyond the calibration or at a [BpSafety] level is
 * shown too (never replaced by an error), with a prompt to confirm it with a second reading and,
 * when it's very high or low, to check with a cuff.
 */
@Composable
fun BpResultScreen(
    systolic: Int,
    diastolic: Int,
    pulse: Int,
    category: BpCategory,
    uncertainty: Int = 0,
    beyondCalibration: Boolean = false,
    confirmed: Boolean = false,
    safety: BpSafety = BpSafety.NONE,
    notValidated: Boolean = false,
    ectopicBeats: Int = 0,
    bodyState: HemodynamicState = HemodynamicState.STEADY,
    channels: List<BpChannel> = emptyList(),
    postureDiffers: Boolean = false,
    wideRange: Boolean = uncertainty > com.heartline.shared.bp.BpEstimator.RANGE_ONLY_SD,
    onMeasureAgain: () -> Unit = {},
    onDone: () -> Unit = {},
) {
    val needsConfirming = (beyondCalibration || safety != BpSafety.NONE) && !confirmed
    Box(Modifier.fillMaxSize()) {
    ActionScreen(stringResource(R.string.action_done), onDone) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("$systolic/$diastolic", style = MaterialTheme.typography.displayMedium)
        }
        // The estimate's uncertainty is shown, never hidden: this is an estimate, not a cuff reading.
        Text(
            if (uncertainty > 0) stringResource(R.string.bp_unit_uncertainty, uncertainty) else stringResource(R.string.unit_mmhg),
            style = MaterialTheme.typography.bodySmall,
            color = WearColors.onSurfaceVariant,
        )
        // Shown right under the number, before anything that needs scrolling.
        if (confirmed) {
            Note(stringResource(R.string.bp_confirmed), WearColors.onSurfaceVariant)
        } else if (beyondCalibration) {
            Note(stringResource(R.string.bp_beyond_calibration), WearColors.warn)
        }
        // Lying down or the hand far from where it was at calibration: say how to measure instead.
        if (postureDiffers) Note(stringResource(R.string.bp_posture_differs), WearColors.warn)
        // A ± this wide spans several categories: the number without a label (algorithm 6.5).
        if (wideRange) {
            Note(stringResource(R.string.bp_wide_range), WearColors.onSurfaceVariant)
        } else {
            Text(
                stringResource(category.label),
                style = MaterialTheme.typography.labelMedium,
                color = Color.Black,
                modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(50)).background(category.color).padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
        stateNote(bodyState)?.let { Note(stringResource(it), WearColors.onSurfaceVariant) }
        if (channels.isNotEmpty()) {
            val names = channels.map { it.label }.distinct().map { stringResource(it) }
            Note(stringResource(R.string.bp_channels, names.joinToString(" · ")), WearColors.onSurfaceVariant)
        }
        if (notValidated) Note(stringResource(R.string.bp_not_validated), WearColors.warn)
        Body(stringResource(R.string.bp_pulse, pulse))
        if (ectopicBeats > 0) Note(stringResource(R.string.bp_ectopic_removed), WearColors.onSurfaceVariant)
        com.heartline.wear.ui.components.BaselineNote(Metric.BLOOD_PRESSURE, systolic.toFloat())
        if (category == BpCategory.NORMAL && !needsConfirming && !wideRange) PersonalNote(GoodResult.BLOOD_PRESSURE)
        when (safety) {
            BpSafety.VERY_HIGH -> Note(stringResource(R.string.bp_safety_high), WearColors.warn)
            BpSafety.LOW -> Note(stringResource(R.string.bp_safety_low), WearColors.warn)
            BpSafety.NONE -> Unit
        }
        if (needsConfirming) {
            androidx.wear.compose.material3.FilledTonalButton(onClick = onMeasureAgain, modifier = Modifier.padding(top = 6.dp)) {
                Text(stringResource(R.string.bp_measure_again), maxLines = 1)
            }
        }
    }
        if (category == BpCategory.NORMAL && !needsConfirming && !wideRange) com.heartline.wear.ui.components.EdgeGlowSweep(systolic, category.color)
    }
}

/** What to do in each step of the precise-mode maneuver. */
val BpPhase.prompt: Int
    get() = when (this) {
        BpPhase.REST -> R.string.bp_phase_rest
        BpPhase.RAISE -> R.string.bp_phase_raise
        BpPhase.HOLD_UP -> R.string.bp_phase_hold
        BpPhase.LOWER -> R.string.bp_phase_lower
        BpPhase.REST_AGAIN -> R.string.bp_phase_rest
    }

/** Short sensor names for the result screen. */
val BpChannel.label: Int
    get() = when (this) {
        BpChannel.PWA_GREEN -> R.string.bp_channel_ppg
        BpChannel.PWA_IR -> R.string.bp_channel_ir
        BpChannel.BCG_PTT -> R.string.bp_channel_bcg
        BpChannel.PAT, BpChannel.ECG_PTT -> R.string.bp_channel_ecg
        BpChannel.HYDRO_MAP -> R.string.bp_channel_arm
    }

/** A short note on the body's state the reading was taken in, or null when steady. */
fun stateNote(state: HemodynamicState): Int? = when (state) {
    HemodynamicState.STEADY -> null
    HemodynamicState.TRANSIENT -> R.string.bp_state_transient
    HemodynamicState.COMPENSATORY -> R.string.bp_state_compensatory
    HemodynamicState.IRREGULAR -> R.string.bp_state_irregular
}

@Composable
private fun Note(text: String, color: Color) = Text(
    text,
    style = MaterialTheme.typography.bodySmall,
    textAlign = TextAlign.Center,
    color = color,
    modifier = Modifier.padding(top = 4.dp),
)

/**
 * Blood pressure recording: the globe beating with every pulse and lighting up with the progress,
 * the percentage inside it, and the pulse wave (filtered, upright) sweeping under it.
 * [fromPhone]: started from the phone, which shows the same steps.
 */
@Composable
fun BpMeasuringScreen(
    progress: Float,
    secondsLeft: Int,
    trace: FloatArray,
    contact: Boolean,
    bpm: Int?,
    endIndex: Long = trace.size.toLong(),
    calibrationRound: Int? = null,
    showWave: Boolean = true,
    animate: Boolean = true,
    settling: Boolean = false,
    phase: BpPhase? = null,
    fromPhone: Boolean = false,
) {
    val color = WearColors.metric(Metric.BLOOD_PRESSURE)
    val shown = if (animate) com.heartline.bubbles.rememberShownProgress(progress, paused = !contact, done = false) else progress
    MeasureFace(
        Metric.BLOOD_PRESSURE,
        if (contact) BubblePhase.MEASURING else BubblePhase.HINT,
        shown,
        bpm = bpm,
        animate = animate,
        top = when {
            calibrationRound != null -> calibrationTitle(calibrationRound)
            fromPhone -> stringResource(R.string.from_phone)
            else -> stringResource(R.string.metric_bp)
        },
        topColor = if (fromPhone && calibrationRound == null) WearColors.onSurfaceVariant else color,
        bottom = stringResource(
            when {
                phase != null && !contact -> R.string.bp_touch_key
                phase != null -> phase.prompt
                // Past the minimum: it records on until the pulse is steady.
                settling && contact -> R.string.bp_settling
                contact -> R.string.bp_keep_still
                else -> R.string.bp_adjust_watch
            },
        ),
        warn = !contact,
        below = when {
            showWave -> {
                { SweepTrace(trace, endIndex, windowSamples = 300, color = color, paper = false, centered = false, minRange = 0f, modifier = Modifier.width(84.dp).height(18.dp)) }
            }
            bpm != null && contact -> {
                {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(18.dp)) {
                        BeatingHeart(bpm, color, 12.dp, animate)
                        Text(stringResource(R.string.live_pulse, bpm), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }
            else -> null
        },
    ) {
        CenteredValue(com.heartline.wear.ui.components.percent(shown), "%", onBubble(MaterialTheme.typography.displayMedium), onBubble(MaterialTheme.typography.bodySmall))
    }
}

/**
 * The recording wasn't a trustworthy pulse wave ([moving]: the arm moved). Only signal problems
 * end here; a real change in pressure is always shown as a reading.
 */
@Composable
fun BpOutOfRangeScreen(moving: Boolean = false, onRetry: () -> Unit = {}) {
    ActionScreen(stringResource(R.string.action_try_again), onRetry) {
        Badge(Icons.Rounded.Speed, WearColors.warn)
        Text(
            stringResource(if (moving) R.string.bp_moving_title else R.string.bp_out_of_range_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
        Body(stringResource(if (moving) R.string.bp_moving_body else R.string.bp_out_of_range_body))
    }
}

@Composable
fun BpNeedsCalibrationScreen(onOpenOnPhone: () -> Unit = {}, opened: Boolean? = null) {
    ActionScreen(stringResource(R.string.action_open_on_phone), onOpenOnPhone, wideAction = true, compactLabel = stringResource(R.string.action_open_short)) {
        Badge(Icons.Rounded.PhoneAndroid, WearColors.metric(Metric.BLOOD_PRESSURE))
        Text(
            stringResource(R.string.bp_needs_calibration_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
        Body(stringResource(R.string.bp_needs_calibration_body))
        opened?.let {
            Text(
                stringResource(if (it) R.string.opened_on_phone else R.string.open_on_phone_failed),
                style = MaterialTheme.typography.bodySmall,
                color = WearColors.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
fun BpCalibrationRecordedScreen(round: Int, onDone: () -> Unit = {}) {
    ActionScreen(stringResource(R.string.action_done), onDone) {
        Badge(Icons.Rounded.Check, WearColors.severity(com.heartline.shared.model.Severity.NORMAL))
        Text(
            stringResource(R.string.bp_calibration_recorded, round),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
        Body(stringResource(R.string.bp_enter_cuff))
    }
}

/** A calibration round that wasn't steady enough: take it again (the phone keeps waiting). */
@Composable
fun BpCalibrationRetryScreen(round: Int, onRetry: () -> Unit = {}) {
    ActionScreen(stringResource(R.string.action_try_again), onRetry) {
        Badge(Icons.Rounded.Speed, WearColors.warn)
        Text(
            stringResource(R.string.bp_calibration_retry_title, round),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
        Body(stringResource(R.string.bp_calibration_retry_body))
    }
}

@Composable
fun ProfileNeededScreen(onDone: () -> Unit = {}) {
    ActionScreen(stringResource(R.string.action_done), onDone) {
        Badge(Icons.Rounded.PhoneAndroid, WearColors.metric(Metric.BODY_COMPOSITION))
        Text(stringResource(R.string.profile_needed_title), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        Body(stringResource(R.string.profile_needed_body))
    }
}
