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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material.icons.rounded.QuestionMark
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ProgressIndicatorDefaults
import androidx.wear.compose.material3.Text
import com.heartline.shared.model.EcgMetrics
import com.heartline.shared.model.EcgNote
import com.heartline.shared.model.EcgPoorReason
import com.heartline.shared.model.EcgResult
import com.heartline.shared.model.Severity
import com.heartline.wear.R
import com.heartline.wear.ui.components.GoodResult
import com.heartline.wear.ui.components.EdgeGlowSweep
import com.heartline.wear.ui.components.EdgePulse
import com.heartline.wear.ui.components.EdgeRipple
import com.heartline.wear.ui.components.PersonalNote
import com.heartline.wear.ui.components.ActionScreen
import com.heartline.wear.ui.components.EcgKeyIllustration
import com.heartline.wear.ui.components.LiveWave
import com.heartline.wear.ui.components.SweepTrace
import com.heartline.wear.ui.components.isSmallRound
import com.heartline.wear.ui.components.label
import com.heartline.wear.ui.theme.WearColors

/** Step 1: how to hold the watch, with an original illustration of a finger on the top key. */
@Composable
fun EcgInstructionScreen(onStart: () -> Unit = {}, animate: Boolean = true) {
    ActionScreen(stringResource(R.string.action_start), onStart) {
        val small = isSmallRound()
        EcgKeyIllustration(
            WearColors.ecg,
            if (small) Modifier.size(width = 84.dp, height = 60.dp) else Modifier.size(width = 100.dp, height = 72.dp),
            background = WearColors.background,
            animate = animate,
        )
        Text(
            stringResource(R.string.ecg_instruction),
            style = if (small) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** Step 2: recording (ECG). */
/**
 * ECG recording: a monitor-style sweep on ECG paper (filtered, auto-gain) with the live heart rate,
 * the countdown and a contact hint, inside the progress ring. The strip runs from the moment
 * measuring starts, so the user sees the signal appear as soon as the finger touches the key.
 */
@Composable
fun EcgMeasuringScreen(
    progress: Float,
    secondsLeft: Int,
    samples: FloatArray,
    leadOff: Boolean,
    bpm: Int? = null,
    endIndex: Long = samples.size.toLong(),
    waitingForTouch: Boolean = false,
    sampleRateHz: Int = 500,
    showWave: Boolean = true,
    arming: Boolean = false,
    struggling: Boolean = false,
    animate: Boolean = true,
) {
    val color = WearColors.ecg
    Box(Modifier.fillMaxSize().background(WearColors.background), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxSize().padding(2.dp),
            strokeWidth = 6.dp,
            colors = ProgressIndicatorDefaults.colors(indicatorColor = color, trackColor = WearColors.surfaceHigh),
        )
        // A ripple comes in from the rim the moment the finger makes contact, then the rim beats.
        if (!leadOff && !waitingForTouch) {
            EdgeRipple("touch", color)
            EdgePulse(bpm, color)
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 18.dp),
        ) {
            // A percentage that glides and never jumps back (seconds used to stall and jump).
            val shown = if (animate) com.heartline.bubbles.rememberShownProgress(progress, paused = leadOff || waitingForTouch, done = false) else progress
            CenteredValue(com.heartline.wear.ui.components.percent(shown), "%", MaterialTheme.typography.displayMedium, MaterialTheme.typography.bodySmall)
            val waveHeight = if (isSmallRound()) 58.dp else 70.dp
            if (showWave) {
                SweepTrace(
                    samples = samples,
                    endIndex = endIndex,
                    windowSamples = sampleRateHz * 3,
                    color = color,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp).height(waveHeight),
                )
            } else {
                // Live wave turned off in settings: a quiet heart instead.
                Box(Modifier.height(waveHeight), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Favorite, contentDescription = null, tint = color.copy(alpha = if (leadOff) 0.35f else 1f), modifier = Modifier.size(34.dp))
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(18.dp)) {
                if (bpm != null && !leadOff) {
                    Icon(Icons.Rounded.Favorite, contentDescription = null, tint = color, modifier = Modifier.size(12.dp))
                    Text(
                        stringResource(R.string.ecg_bpm_value, bpm),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
            Text(
                stringResource(
                    when {
                        // The countdown only starts once the touch is confirmed as a real ECG.
                        struggling -> R.string.ecg_touch_lighter
                        arming -> R.string.ecg_hold_starting
                        waitingForTouch -> R.string.ecg_touch_to_start
                        leadOff -> R.string.ecg_lead_off
                        else -> R.string.ecg_keep_finger
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if ((leadOff && !waitingForTouch && !arming) || struggling) WearColors.warn else WearColors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
                modifier = Modifier.padding(horizontal = 22.dp),
            )
        }
    }
}

/**
 * Shared recording layout: a progress ring runs around the edge of the round screen, with the
 * seconds left in the centre and the live trace in the middle band.
 */
@Composable
fun MeasuringScreen(
    title: String,
    color: androidx.compose.ui.graphics.Color,
    progress: Float,
    secondsLeft: Int,
    samples: FloatArray,
    hint: String,
    warn: Boolean,
    fixedRangeMv: Float?,
) {
    Box(Modifier.fillMaxSize().background(WearColors.background), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxSize().padding(2.dp),
            strokeWidth = 6.dp,
            colors = ProgressIndicatorDefaults.colors(indicatorColor = color, trackColor = WearColors.surfaceHigh),
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 20.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = color)
            CenteredValue(com.heartline.wear.ui.components.percent(progress.coerceAtMost(0.99f)), "%", MaterialTheme.typography.displayLarge, MaterialTheme.typography.bodySmall)
            LiveWave(samples, color, Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(44.dp), fixedRangeMv)
            Spacer(Modifier.height(6.dp))
            // Narrower than the wave so the text stays inside the round ring on small screens.
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = if (warn) WearColors.warn else WearColors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
                modifier = Modifier.padding(horizontal = 22.dp),
            )
        }
    }
}

/** Step 3: the result, then every measured detail (scroll), like the phone's recording details. */
@Composable
fun EcgResultScreen(result: EcgResult, averageBpm: Int?, metrics: EcgMetrics? = null, onDone: () -> Unit = {}) {
    val color = WearColors.severity(result.severity)
    val icon = when (result.severity) {
        Severity.NORMAL -> Icons.Rounded.Check
        Severity.NEUTRAL -> Icons.Rounded.QuestionMark
        else -> Icons.Rounded.PriorityHigh
    }
    Box(Modifier.fillMaxSize()) {
    ActionScreen(stringResource(R.string.action_done), onDone) {
        val small = isSmallRound()
        Box(
            Modifier.size(if (small) 32.dp else 40.dp).clip(CircleShape).background(color.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(if (small) 20.dp else 24.dp))
        }
        Text(
            stringResource(result.label),
            style = if (small) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (averageBpm != null) {
            Text(stringResource(R.string.ecg_bpm_value, averageBpm), style = MaterialTheme.typography.bodyLarge, color = WearColors.onSurfaceVariant)
        }
        com.heartline.wear.ui.components.BaselineNote(com.heartline.shared.model.Metric.ECG, averageBpm?.toFloat())
        if (result.severity == Severity.NORMAL) PersonalNote(GoodResult.ECG)
        metrics?.poorReason?.takeIf { it != EcgPoorReason.NONE }?.let { reason ->
            Text(
                stringResource(reason.hint),
                style = MaterialTheme.typography.bodySmall,
                color = WearColors.warn,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        metrics?.note?.text?.let { note ->
            Text(
                stringResource(note),
                style = MaterialTheme.typography.bodySmall,
                color = WearColors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        metrics?.let { EcgDetails(it) }
    }
        // A normal rhythm: a band of light runs once around the rim.
        if (result.severity == Severity.NORMAL) EdgeGlowSweep(result, color)
    }
}

/** What else the recording shows, or why it stayed inconclusive (algorithm 3). */
val EcgNote.text: Int?
    get() = when (this) {
        EcgNote.EXTRA_BEATS -> R.string.ecg_note_extra_beats
        EcgNote.FREQUENT_EXTRA_BEATS -> R.string.ecg_note_frequent_extra_beats
        EcgNote.IRREGULAR_PATTERN -> R.string.ecg_note_irregular_pattern
        EcgNote.NO_CLEAR_P_WAVE -> R.string.ecg_note_no_p_wave
        EcgNote.RATE_ABOVE_150 -> R.string.ecg_note_rate_above_150
        EcgNote.FAST_REGULAR -> R.string.ecg_note_fast_regular
        EcgNote.PAUSES -> R.string.ecg_note_pauses
        EcgNote.NOISY_RHYTHM -> R.string.ecg_note_noisy_rhythm
        EcgNote.NONE -> null
    }

@Composable
private fun EcgDetails(m: EcgMetrics) {
    val time = remember(m.startedAtMs) { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(m.startedAtMs)) }
    Spacer(Modifier.height(8.dp))
    DetailRow(stringResource(R.string.ecg_detail_recorded), time)
    DetailRow(stringResource(R.string.ecg_detail_duration), stringResource(R.string.value_seconds, m.durationSec.roundToInt()))
    DetailRow(stringResource(R.string.ecg_detail_usable), stringResource(R.string.value_seconds_percent, m.usableSec.roundToInt(), m.usablePercent))
    DetailRow(stringResource(R.string.ecg_detail_noise), stringResource(R.string.value_seconds, m.noiseSec.roundToInt()))
    if (m.minBpm != null && m.maxBpm != null) DetailRow(stringResource(R.string.ecg_detail_range), "${m.minBpm}–${m.maxBpm}")
    DetailRow(stringResource(R.string.ecg_detail_beats), "${m.beats}")
    if (m.ectopicBeats > 0) DetailRow(stringResource(R.string.ecg_detail_extra_beats), "${m.ectopicBeats}")
    m.rmssdMs?.let { DetailRow(stringResource(R.string.ecg_detail_rmssd), stringResource(R.string.value_ms, it)) }
    DetailRow(stringResource(R.string.ecg_detail_quality), "${m.qualityScore}/100")
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = WearColors.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.labelMedium)
    }
}

private val EcgPoorReason.hint: Int
    get() = when (this) {
        EcgPoorReason.MOTION -> R.string.ecg_poor_motion
        EcgPoorReason.MUSCLE_NOISE -> R.string.ecg_poor_muscle
        EcgPoorReason.LOW_AMPLITUDE -> R.string.ecg_poor_low
        EcgPoorReason.LEAD_OFF -> R.string.ecg_poor_lead_off
        EcgPoorReason.TOO_SHORT -> R.string.ecg_poor_short
        EcgPoorReason.TOO_FEW_BEATS, EcgPoorReason.NONE -> R.string.ecg_poor_beats
    }

@Composable
fun EcgAnalyzingScreen() {
    Box(Modifier.fillMaxSize().background(WearColors.background), contentAlignment = Alignment.Center) {
        // Full ring: the 30 s recording is complete while the result is computed.
        CircularProgressIndicator(
            progress = { 1f },
            modifier = Modifier.fillMaxSize().padding(2.dp),
            strokeWidth = 6.dp,
            colors = ProgressIndicatorDefaults.colors(indicatorColor = WearColors.ecg, trackColor = WearColors.surfaceHigh),
        )
        Text(stringResource(R.string.ecg_analyzing), style = MaterialTheme.typography.titleMedium)
    }
}
