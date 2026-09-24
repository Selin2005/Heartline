package com.heartline.wear.ui.screens

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
import com.heartline.shared.design.Palette
import com.heartline.shared.model.Metric
import com.heartline.wear.R
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

@Composable
fun BpResultScreen(systolic: Int, diastolic: Int, pulse: Int, category: BpCategory, onDone: () -> Unit = {}) {
    ActionScreen(stringResource(R.string.action_done), onDone) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("$systolic/$diastolic", style = MaterialTheme.typography.displayMedium)
        }
        Text(stringResource(R.string.unit_mmhg), style = MaterialTheme.typography.bodySmall, color = WearColors.onSurfaceVariant)
        Text(
            stringResource(category.label),
            style = MaterialTheme.typography.labelMedium,
            color = Color.Black,
            modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(50)).background(category.color).padding(horizontal = 10.dp, vertical = 3.dp),
        )
        Body(stringResource(R.string.bp_pulse, pulse))
    }
}

@Composable
fun BpNeedsCalibrationScreen(onDone: () -> Unit = {}) {
    ActionScreen(stringResource(R.string.action_done), onDone) {
        Badge(Icons.Rounded.PhoneAndroid, WearColors.metric(Metric.BLOOD_PRESSURE))
        Text(
            stringResource(R.string.bp_needs_calibration_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
        Body(stringResource(R.string.bp_needs_calibration_body))
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
