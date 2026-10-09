// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.ui.screens

import com.heartline.wear.ui.components.CenteredValue
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ProgressIndicatorDefaults
import androidx.wear.compose.material3.Text
import com.heartline.shared.model.Metric
import com.heartline.shared.model.RecordSummary
import com.heartline.shared.profile.StressIndex
import com.heartline.shared.profile.StressLevel
import com.heartline.wear.R
import com.heartline.wear.ui.components.GoodResult
import com.heartline.wear.ui.components.PersonalNote
import kotlin.math.roundToInt
import com.heartline.wear.sensor.QuickHint
import com.heartline.wear.ui.components.ActionScreen
import com.heartline.wear.ui.components.icon
import com.heartline.wear.ui.components.isSmallRound
import com.heartline.wear.ui.components.label
import com.heartline.wear.ui.theme.WearColors
import com.heartline.bubbles.BubblePhase
import com.heartline.wear.ui.components.MeasureBubble
import com.heartline.wear.ui.components.onBubble
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.Icons

private val Metric.instruction: Int
    get() = when (this) {
        Metric.SPO2 -> R.string.spo2_instruction
        Metric.SKIN_TEMPERATURE -> R.string.temp_instruction
        Metric.BODY_COMPOSITION -> R.string.body_instruction
        else -> R.string.stress_instruction
    }

val QuickHint.text: Int
    get() = when (this) {
        QuickHint.HOLD_STILL -> R.string.hint_hold_still
        QuickHint.LOW_SIGNAL -> R.string.hint_low_signal
        QuickHint.TOUCH_KEYS -> R.string.hint_touch_keys
        QuickHint.TOP_KEY -> R.string.hint_top_key
        QuickHint.BOTTOM_KEY -> R.string.hint_bottom_key
        QuickHint.WRIST_CONTACT -> R.string.hint_wrist_contact
        QuickHint.DRY_SKIN -> R.string.hint_dry_skin
        QuickHint.HANDS_APART -> R.string.hint_hands_apart
        QuickHint.KEYS_ONLY -> R.string.hint_keys_only
        QuickHint.CHECK_PROFILE -> R.string.hint_check_profile
    }

@Composable
private fun MetricBadge(metric: Metric) {
    val small = isSmallRound()
    val tint = WearColors.metric(metric)
    Box(Modifier.size(if (small) 32.dp else 40.dp).clip(CircleShape).background(tint.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
        Icon(metric.icon, contentDescription = null, tint = tint, modifier = Modifier.size(if (small) 20.dp else 24.dp))
    }
}

@Composable
fun QuickInstructionScreen(metric: Metric, onStart: () -> Unit = {}) {
    ActionScreen(stringResource(R.string.action_start), onStart) {
        MetricBadge(metric)
        Text(stringResource(metric.label), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        Text(
            stringResource(metric.instruction),
            style = if (isSmallRound()) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
            color = WearColors.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * Measuring: a glossy bubble that fills with the progress and moves in the metric's own way (the
 * SpO2 bubble fizzes, the stress bubble breathes, the heart beats at the measured rate), the
 * seconds left inside it and the hint under it. [fromPhone]: started from the phone, which shows
 * the same steps.
 */
@Composable
fun QuickMeasuringScreen(
    metric: Metric,
    progress: Float,
    secondsLeft: Int,
    hint: QuickHint?,
    bpm: Int? = null,
    hrvMs: Double? = null,
    animate: Boolean = true,
    fromPhone: Boolean = false,
) {
    Box(Modifier.fillMaxSize().background(WearColors.background), contentAlignment = Alignment.Center) {
        MeasureBubble(
            metric,
            if (hint != null) BubblePhase.HINT else BubblePhase.MEASURING,
            Modifier.fillMaxSize(),
            progress = progress,
            bpm = bpm,
            animate = animate,
        ) {
            CenteredValue("$secondsLeft", stringResource(R.string.unit_sec), onBubble(MaterialTheme.typography.displayMedium), onBubble(MaterialTheme.typography.bodySmall))
        }
        val live = listOfNotNull(
            bpm?.let { stringResource(R.string.live_bpm, it) },
            hrvMs?.takeIf { metric == Metric.STRESS }?.let { stringResource(R.string.live_hrv, it.roundToInt()) },
        ).joinToString(" · ")
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize().padding(horizontal = 30.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            if (fromPhone) FromPhoneLabel() else Spacer(Modifier.height(1.dp))
            Text(
                when {
                    hint != null -> stringResource(hint.text)
                    progress >= 0.98f -> stringResource(R.string.hint_finishing)
                    live.isNotEmpty() -> live
                    metric == Metric.STRESS -> stringResource(R.string.hint_breathe)
                    else -> stringResource(R.string.hint_measuring)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (hint != null) WearColors.warn else WearColors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/** A small "Started from your phone" chip at the top of a measurement the phone started. */
@Composable
fun FromPhoneLabel() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.PhoneAndroid, contentDescription = null, tint = WearColors.onSurfaceVariant, modifier = Modifier.size(12.dp))
        Text(
            stringResource(R.string.from_phone),
            style = MaterialTheme.typography.labelSmall,
            color = WearColors.onSurfaceVariant,
            modifier = Modifier.padding(start = 3.dp),
        )
    }
}

@Composable
fun QuickResultScreen(metric: Metric, summary: RecordSummary, onDone: () -> Unit = {}) {
    val small = isSmallRound()
    val good = (summary is RecordSummary.Spo2 && summary.percent >= 95 && !summary.lowConfidence) ||
        (summary is RecordSummary.Stress && StressIndex.level(summary.score) == StressLevel.LOW)
    Box(Modifier.fillMaxSize()) {
    ActionScreen(stringResource(R.string.action_done), onDone) {
        Text(stringResource(metric.label), style = MaterialTheme.typography.titleSmall, color = WearColors.metric(metric))
        when (summary) {
            is RecordSummary.Spo2 -> {
                BigValue("${summary.percent}", "%")
                summary.heartRate?.let { Detail(stringResource(R.string.bp_pulse, it)) }
                com.heartline.wear.ui.components.BaselineNote(Metric.SPO2, summary.percent.toFloat())
                if (summary.percent >= 95 && !summary.lowConfidence) PersonalNote(GoodResult.SPO2)
            }
            is RecordSummary.SkinTemperature -> {
                BigValue("%.1f".format(summary.skinCelsius), "°C")
                summary.ambientCelsius?.let { Detail(stringResource(R.string.temp_ambient, "%.1f".format(it))) }
            }
            is RecordSummary.BodyComposition -> {
                BigValue("%.1f".format(summary.bodyFatPercent), "%")
                Detail(stringResource(R.string.body_fat))
                summary.skeletalMuscleKg?.let { Detail(stringResource(R.string.body_muscle, "%.1f".format(it))) }
            }
            is RecordSummary.Stress -> {
                val level = StressIndex.level(summary.score)
                Text(
                    stringResource(level.label),
                    style = if (small) MaterialTheme.typography.displayMedium else MaterialTheme.typography.displayLarge,
                )
                StressBar(summary.score, Modifier.fillMaxWidth(0.8f).padding(top = 6.dp).height(10.dp))
                summary.rmssdMs?.let { Detail(stringResource(R.string.stress_hrv, it.toInt())) }
                com.heartline.wear.ui.components.BaselineNote(Metric.STRESS, summary.score.toFloat())
                if (level == StressLevel.LOW) PersonalNote(GoodResult.CALM)
            }
            else -> Unit
        }
    }
    if (good) com.heartline.wear.ui.components.EdgeGlowSweep(summary, WearColors.metric(metric))
    }
}

val StressLevel.label: Int
    get() = when (this) {
        StressLevel.LOW -> R.string.stress_low
        StressLevel.MEDIUM -> R.string.stress_medium
        StressLevel.HIGH -> R.string.stress_high
    }

@Composable
private fun BigValue(value: String, unit: String) {
    CenteredValue(value, unit, if (isSmallRound()) MaterialTheme.typography.displayMedium else MaterialTheme.typography.displayLarge, MaterialTheme.typography.bodyMedium)
}

@Composable
private fun Detail(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = WearColors.onSurfaceVariant, textAlign = TextAlign.Center)

/** Green → yellow → red scale with a marker at the score (Samsung Health stress style). */
@Composable
fun StressBar(score: Int, modifier: Modifier = Modifier) {
    val low = WearColors.severity(com.heartline.shared.model.Severity.NORMAL)
    val mid = WearColors.metric(Metric.STRESS)
    val high = WearColors.severity(com.heartline.shared.model.Severity.ALERT)
    Canvas(modifier) {
        val y = size.height / 2
        drawLine(Brush.horizontalGradient(listOf(low, mid, high)), Offset(0f, y), Offset(size.width, y), strokeWidth = size.height * 0.5f, cap = StrokeCap.Round)
        drawCircle(androidx.compose.ui.graphics.Color.White, radius = size.height / 2, center = Offset(size.width * score / 100f, y))
    }
}

