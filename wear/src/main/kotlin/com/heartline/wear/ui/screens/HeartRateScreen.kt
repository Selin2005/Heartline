// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.ui.screens

import com.heartline.wear.ui.components.CenteredValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.heartline.wear.R
import com.heartline.wear.ui.components.MeasureFace
import com.heartline.wear.ui.components.onBubble
import com.heartline.bubbles.BubblePhase
import androidx.compose.foundation.clickable
import com.heartline.wear.ui.components.HeartMonitor
import com.heartline.wear.ui.theme.WearColors

/**
 * Live heart rate: a globe beating at the measured rate with the number inside, over a
 * bedside-monitor style sweep. Grey while the watch is off the wrist.
 */
@Composable
fun HeartRateScreen(bpm: Int?, onBody: Boolean, animate: Boolean = true) {
    val metric = com.heartline.shared.model.Metric.HEART_RATE
    val color = WearColors.metric(metric)
    val phase = when {
        !onBody -> BubblePhase.FAILED
        bpm == null -> BubblePhase.FORMING
        else -> BubblePhase.IDLE
    }
    Box(Modifier.fillMaxSize()) {
        MeasureFace(
            metric,
            phase,
            progress = 1f,
            bpm = bpm.takeIf { onBody },
            animate = animate,
            ring = false,
            bottom = if (onBody) null else stringResource(R.string.hr_off_body),
            below = if (onBody) {
                { HeartMonitor(bpm, color, Modifier.width(84.dp).height(18.dp), animate) }
            } else {
                null
            },
        ) {
            when {
                !onBody -> Unit
                bpm == null -> Text(stringResource(R.string.hr_measuring), style = onBubble(MaterialTheme.typography.titleSmall))
                else -> CenteredValue("$bpm", stringResource(R.string.unit_bpm), onBubble(MaterialTheme.typography.displayMedium), onBubble(MaterialTheme.typography.bodySmall))
            }
        }
        // The rim glows with each beat.
        if (animate && onBody) com.heartline.wear.ui.components.EdgePulse(bpm, color)
    }
}

/**
 * A 30-second heart-rate check (started from the phone): the globe lights up with the good readings
 * and beats at the live rate; then the result. [hint]: the sensor lost the wrist.
 */
@Composable
fun HeartRateCheckScreen(progress: Float, secondsLeft: Int, bpm: Int?, hint: Boolean, fromPhone: Boolean = true, animate: Boolean = true, weak: Boolean = false) {
    val metric = com.heartline.shared.model.Metric.HEART_RATE
    val waiting = hint || weak
    val shown = if (animate) com.heartline.bubbles.rememberShownProgress(progress, paused = waiting, done = false) else progress
    val percent = com.heartline.wear.ui.components.percent(shown)
    MeasureFace(
        metric,
        when {
            waiting -> BubblePhase.HINT
            bpm == null -> BubblePhase.FORMING
            else -> BubblePhase.MEASURING
        },
        shown,
        bpm = bpm,
        animate = animate,
        top = if (fromPhone) stringResource(R.string.from_phone) else null,
        // The percentage is always there: in the middle until the first pulse, then below it.
        bottom = when {
            hint -> stringResource(R.string.hint_wrist_contact)
            weak -> stringResource(R.string.hr_check_hold_still, percent)
            bpm == null -> stringResource(R.string.hr_measuring)
            else -> stringResource(R.string.hr_check_progress, percent)
        },
        warn = waiting,
    ) {
        if (bpm != null) {
            CenteredValue("$bpm", stringResource(R.string.unit_bpm), onBubble(MaterialTheme.typography.displayMedium), onBubble(MaterialTheme.typography.bodySmall))
        } else {
            CenteredValue(percent, "%", onBubble(MaterialTheme.typography.displayMedium), onBubble(MaterialTheme.typography.bodySmall))
        }
    }
}

/** The result of a heart-rate check: the globe fully lit and calm, the number and the range. */
@Composable
fun HeartRateCheckResultScreen(bpm: Int, min: Int, max: Int, onDone: () -> Unit = {}, animate: Boolean = true) {
    val metric = com.heartline.shared.model.Metric.HEART_RATE
    Box(
        Modifier.fillMaxSize().background(WearColors.background).clickable(onClick = onDone),
        contentAlignment = Alignment.Center,
    ) {
        MeasureFace(
            metric,
            BubblePhase.SUCCESS,
            progress = 1f,
            bpm = bpm,
            animate = animate,
            bottom = stringResource(R.string.hr_check_range, min, max),
        ) {
            CenteredValue("$bpm", stringResource(R.string.unit_bpm), onBubble(MaterialTheme.typography.displayMedium), onBubble(MaterialTheme.typography.bodySmall))
        }
    }
}
