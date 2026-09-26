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
import com.heartline.wear.ui.components.BeatingHeart
import com.heartline.wear.ui.components.HeartMonitor
import com.heartline.wear.ui.theme.WearColors

/** Live heart rate with a heart beating at that rate over a bedside-monitor style sweep. */
@Composable
fun HeartRateScreen(bpm: Int?, onBody: Boolean, animate: Boolean = true) {
    val color = WearColors.metric(com.heartline.shared.model.Metric.HEART_RATE)
    Box(Modifier.fillMaxSize().background(WearColors.background), contentAlignment = Alignment.Center) {
        // The rim glows with each beat.
        if (animate && onBody) com.heartline.wear.ui.components.EdgePulse(bpm, color)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 24.dp),
        ) {
            // Beats at the measured rate.
            BeatingHeart(bpm.takeIf { onBody }, color, 28.dp, animate)
            when {
                !onBody -> Text(
                    stringResource(R.string.hr_off_body),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = WearColors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                bpm == null -> Text(stringResource(R.string.hr_measuring), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                else -> {
                    CenteredValue("$bpm", stringResource(R.string.unit_bpm), MaterialTheme.typography.displayLarge, MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.hr_now), style = MaterialTheme.typography.bodySmall, color = WearColors.onSurfaceVariant)
                }
            }
            if (onBody) {
                HeartMonitor(bpm, color, Modifier.fillMaxWidth().padding(top = 10.dp).height(40.dp), animate)
            }
        }
    }
}
