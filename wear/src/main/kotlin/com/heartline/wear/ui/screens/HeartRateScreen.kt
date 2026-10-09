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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.heartline.wear.R
import com.heartline.wear.ui.components.MeasureBubble
import com.heartline.wear.ui.components.onBubble
import com.heartline.bubbles.BubblePhase
import androidx.compose.foundation.clickable
import com.heartline.wear.ui.components.HeartMonitor
import com.heartline.wear.ui.theme.WearColors

/**
 * Live heart rate: a bubble beating at the measured rate with the number inside, over a
 * bedside-monitor style sweep. Grey while the watch is off the wrist.
 */
@Composable
fun HeartRateScreen(bpm: Int?, onBody: Boolean, animate: Boolean = true) {
    val metric = com.heartline.shared.model.Metric.HEART_RATE
    val color = WearColors.metric(metric)
    Box(Modifier.fillMaxSize().background(WearColors.background), contentAlignment = Alignment.Center) {
        // The rim glows with each beat.
        if (animate && onBody) com.heartline.wear.ui.components.EdgePulse(bpm, color)
        val phase = when {
            !onBody -> BubblePhase.FAILED
            bpm == null -> BubblePhase.FORMING
            else -> BubblePhase.IDLE
        }
        MeasureBubble(metric, phase, Modifier.fillMaxSize().padding(bottom = 26.dp), bpm = bpm.takeIf { onBody }, animate = animate) {
            when {
                !onBody -> Unit
                bpm == null -> Text(stringResource(R.string.hr_measuring), style = onBubble(MaterialTheme.typography.titleSmall))
                else -> CenteredValue("$bpm", stringResource(R.string.unit_bpm), onBubble(MaterialTheme.typography.displayMedium), onBubble(MaterialTheme.typography.bodySmall))
            }
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Bottom,
            modifier = Modifier.fillMaxSize().padding(horizontal = 34.dp, vertical = 16.dp),
        ) {
            if (!onBody) {
                Text(
                    stringResource(R.string.hr_off_body),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = WearColors.onSurfaceVariant,
                )
            } else {
                HeartMonitor(bpm, color, Modifier.fillMaxWidth().height(26.dp), animate)
            }
        }
    }
}

/**
 * A 30-second heart-rate check (started from the phone): the bubble fills with the good readings
 * and beats at the live rate; then the result. [hint]: the sensor lost the wrist.
 */
@Composable
fun HeartRateCheckScreen(progress: Float, secondsLeft: Int, bpm: Int?, hint: Boolean, fromPhone: Boolean = true, animate: Boolean = true) {
    val metric = com.heartline.shared.model.Metric.HEART_RATE
    Box(Modifier.fillMaxSize().background(WearColors.background), contentAlignment = Alignment.Center) {
        MeasureBubble(
            metric,
            when {
                hint -> BubblePhase.HINT
                bpm == null -> BubblePhase.FORMING
                else -> BubblePhase.MEASURING
            },
            Modifier.fillMaxSize(),
            progress = progress,
            bpm = bpm,
            animate = animate,
        ) {
            if (bpm != null) {
                CenteredValue("$bpm", stringResource(R.string.unit_bpm), onBubble(MaterialTheme.typography.displayMedium), onBubble(MaterialTheme.typography.bodySmall))
            }
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxSize().padding(horizontal = 30.dp, vertical = 14.dp),
        ) {
            if (fromPhone) FromPhoneLabel() else androidx.compose.foundation.layout.Spacer(Modifier.height(1.dp))
            Text(
                when {
                    hint -> stringResource(R.string.hint_wrist_contact)
                    bpm == null -> stringResource(R.string.hr_measuring)
                    else -> stringResource(R.string.hr_check_left, secondsLeft)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (hint) WearColors.warn else WearColors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** The result of a heart-rate check: the bubble full and calm, the number and the range. */
@Composable
fun HeartRateCheckResultScreen(bpm: Int, min: Int, max: Int, onDone: () -> Unit = {}, animate: Boolean = true) {
    val metric = com.heartline.shared.model.Metric.HEART_RATE
    Box(
        Modifier.fillMaxSize().background(WearColors.background).clickable(onClick = onDone),
        contentAlignment = Alignment.Center,
    ) {
        MeasureBubble(metric, BubblePhase.SUCCESS, Modifier.fillMaxSize(), progress = 1f, bpm = bpm, animate = animate) {
            CenteredValue("$bpm", stringResource(R.string.unit_bpm), onBubble(MaterialTheme.typography.displayMedium), onBubble(MaterialTheme.typography.bodySmall))
        }
        Text(
            stringResource(R.string.hr_check_range, min, max),
            style = MaterialTheme.typography.bodySmall,
            color = WearColors.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp),
        )
    }
}
