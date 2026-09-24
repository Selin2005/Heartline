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
import kotlin.math.roundToInt
import com.heartline.wear.ui.components.BeatingHeart
import com.heartline.wear.ui.components.BreathingCircle
import com.heartline.wear.ui.components.KeysContact
import com.heartline.wear.ui.components.ThermometerFill
import com.heartline.wear.ui.components.PulseRipple
import com.heartline.wear.sensor.QuickHint
import com.heartline.wear.ui.components.ActionScreen
import com.heartline.wear.ui.components.icon
import com.heartline.wear.ui.components.isSmallRound
import com.heartline.wear.ui.components.label
import com.heartline.wear.ui.theme.WearColors

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
        QuickHint.WRIST_CONTACT -> R.string.hint_wrist_contact
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
 * Measuring: progress ring, countdown and a metric-specific animation driven by live data where
 * the sensor gives it (the SpO2 ripple and the stress heart beat at the measured heart rate).
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
) {
    val color = WearColors.metric(metric)
    Box(Modifier.fillMaxSize().background(WearColors.background), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxSize().padding(2.dp),
            strokeWidth = 6.dp,
            colors = ProgressIndicatorDefaults.colors(indicatorColor = color, trackColor = WearColors.surfaceHigh),
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, modifier = Modifier.padding(horizontal = 26.dp)) {
            val visual = if (isSmallRound()) 50.dp else 62.dp
            when (metric) {
                Metric.SPO2 -> PulseRipple(bpm, color, Modifier.size(visual), animate) {
                    Icon(metric.icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
                }
                Metric.SKIN_TEMPERATURE -> ThermometerFill(color, Modifier.size(width = visual * 0.6f, height = visual), animate)
                Metric.BODY_COMPOSITION -> KeysContact(color, Modifier.size(visual), animate) {
                    Icon(metric.icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
                }
                Metric.STRESS -> BreathingCircle(color, Modifier.size(visual), animate) {
                    BeatingHeart(bpm, color, 20.dp, animate)
                }
                else -> Icon(metric.icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
            }
            CenteredValue("$secondsLeft", stringResource(R.string.unit_sec), MaterialTheme.typography.displayMedium, MaterialTheme.typography.bodySmall)
            val live = listOfNotNull(
                bpm?.let { stringResource(R.string.live_bpm, it) },
                hrvMs?.takeIf { metric == Metric.STRESS }?.let { stringResource(R.string.live_hrv, it.roundToInt()) },
            ).joinToString(" · ")
            Text(
                when {
                    hint != null -> stringResource(hint.text)
                    live.isNotEmpty() -> live
                    metric == Metric.STRESS -> stringResource(R.string.hint_breathe)
                    else -> stringResource(R.string.hint_measuring)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (hint != null) WearColors.warn else WearColors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }
    }
}

@Composable
fun QuickResultScreen(metric: Metric, summary: RecordSummary, onDone: () -> Unit = {}) {
    val small = isSmallRound()
    ActionScreen(stringResource(R.string.action_done), onDone) {
        Text(stringResource(metric.label), style = MaterialTheme.typography.titleSmall, color = WearColors.metric(metric))
        when (summary) {
            is RecordSummary.Spo2 -> {
                BigValue("${summary.percent}", "%")
                summary.heartRate?.let { Detail(stringResource(R.string.bp_pulse, it)) }
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
            }
            else -> Unit
        }
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

