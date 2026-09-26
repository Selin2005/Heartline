package com.heartline.wear.ui.screens

import com.heartline.wear.ui.components.CenteredValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import com.heartline.shared.bp.BpCategory
import com.heartline.shared.bp.BpSafety
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

@Composable
private fun Body(text: String) = Text(
    text,
    style = if (isSmallRound()) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
    textAlign = TextAlign.Center,
    color = WearColors.onSurfaceVariant,
    modifier = Modifier.padding(top = 4.dp),
)

/** Before measuring (or a calibration round when [calibrationRound] is set). */
@Composable
fun BpInstructionScreen(calibrationRound: Int? = null, onStart: () -> Unit = {}) {
    ActionScreen(stringResource(R.string.action_start), onStart) {
        Badge(Icons.Rounded.Speed, WearColors.metric(Metric.BLOOD_PRESSURE))
        Text(
            if (calibrationRound != null) stringResource(R.string.bp_calibration_round, calibrationRound) else stringResource(R.string.metric_bp),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
        Body(stringResource(R.string.bp_instruction))
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
        Text(
            stringResource(category.label),
            style = MaterialTheme.typography.labelMedium,
            color = Color.Black,
            modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(50)).background(category.color).padding(horizontal = 10.dp, vertical = 3.dp),
        )
        Body(stringResource(R.string.bp_pulse, pulse))
        if (category == BpCategory.NORMAL && !needsConfirming) PersonalNote(GoodResult.BLOOD_PRESSURE)
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
        if (category == BpCategory.NORMAL && !needsConfirming) com.heartline.wear.ui.components.EdgeGlowSweep(systolic, category.color)
    }
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
 * Blood pressure recording: the pulse wave sweeps across the screen (filtered, upright) with the
 * live pulse rate, inside the progress ring.
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
) {
    val color = WearColors.metric(Metric.BLOOD_PRESSURE)
    Box(Modifier.fillMaxSize().background(WearColors.background), contentAlignment = Alignment.Center) {
        androidx.wear.compose.material3.CircularProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxSize().padding(2.dp),
            strokeWidth = 6.dp,
            colors = androidx.wear.compose.material3.ProgressIndicatorDefaults.colors(indicatorColor = color, trackColor = WearColors.surfaceHigh),
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 18.dp),
        ) {
            Text(
                calibrationRound?.let { stringResource(R.string.bp_calibration_round, it) } ?: stringResource(R.string.metric_bp),
                style = MaterialTheme.typography.labelMedium,
                color = color,
            )
            CenteredValue("$secondsLeft", stringResource(R.string.unit_sec), MaterialTheme.typography.displayMedium, MaterialTheme.typography.bodySmall)
            val waveHeight = if (isSmallRound()) 44.dp else 54.dp
            if (showWave) {
                SweepTrace(trace, endIndex, windowSamples = 300, color = color, paper = false, centered = false, minRange = 0f, modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(waveHeight))
            } else {
                Box(Modifier.height(waveHeight), contentAlignment = Alignment.Center) { BeatingHeart(bpm, color, 30.dp, animate) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(18.dp).padding(top = 2.dp)) {
                if (bpm != null && contact) {
                    BeatingHeart(bpm, color, 12.dp, animate)
                    Text(stringResource(R.string.live_pulse, bpm), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 4.dp))
                }
            }
            Text(
                stringResource(if (contact) R.string.bp_keep_still else R.string.bp_adjust_watch),
                style = MaterialTheme.typography.bodySmall,
                color = if (contact) WearColors.onSurfaceVariant else WearColors.warn,
                textAlign = TextAlign.Center,
                maxLines = 2,
                modifier = Modifier.padding(horizontal = 18.dp),
            )
        }
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

@Composable
fun ProfileNeededScreen(onDone: () -> Unit = {}) {
    ActionScreen(stringResource(R.string.action_done), onDone) {
        Badge(Icons.Rounded.PhoneAndroid, WearColors.metric(Metric.BODY_COMPOSITION))
        Text(stringResource(R.string.profile_needed_title), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        Body(stringResource(R.string.profile_needed_body))
    }
}
