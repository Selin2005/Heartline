// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.heartline.shared.model.Metric
import com.heartline.wear.R
import com.heartline.wear.ui.components.Buzz
import com.heartline.wear.ui.theme.WearColors
import kotlin.math.PI
import kotlin.math.cos

/** One minute of slow breathing: six breaths a minute, 5 s in and 5 s out (resonance breathing). */
object Breathing {
    const val DURATION_MS = 60_000L
    const val BREATH_MS = 10_000L

    /** True while breathing in at [elapsedMs]. */
    fun inhaling(elapsedMs: Long) = elapsedMs % BREATH_MS < BREATH_MS / 2

    /** The circle's size, 0 (out) to 1 (in), easing like a breath. */
    fun fill(elapsedMs: Long): Float = ((1 - cos(2 * PI * (elapsedMs % BREATH_MS) / BREATH_MS)) / 2).toFloat()
}

/**
 * A guided minute of slow breathing, opened from a stress notice or the stress screen. A soft
 * vibration marks each change between in and out, so it can be followed with eyes closed.
 * [initialElapsedMs] and [animate] are for previews.
 */
@Composable
fun BreathingScreen(
    initialElapsedMs: Long = 0,
    animate: Boolean = true,
    buzz: (Buzz) -> Unit = {},
    onMeasure: () -> Unit = {},
    onDone: () -> Unit = {},
) {
    var elapsed by remember { mutableLongStateOf(initialElapsedMs) }
    if (animate) {
        LaunchedEffect(Unit) {
            val start = withFrameMillis { it } - initialElapsedMs
            var wasIn = Breathing.inhaling(elapsed)
            while (elapsed < Breathing.DURATION_MS) {
                elapsed = (withFrameMillis { it } - start).coerceAtMost(Breathing.DURATION_MS)
                val isIn = Breathing.inhaling(elapsed)
                if (isIn != wasIn) buzz(Buzz.BREATH)
                wasIn = isIn
            }
            buzz(Buzz.DONE)
        }
    }
    val color = WearColors.metric(Metric.STRESS)
    val done = elapsed >= Breathing.DURATION_MS
    Box(Modifier.fillMaxSize().background(WearColors.background), contentAlignment = Alignment.Center) {
        if (!done) {
            val fill = Breathing.fill(elapsed)
            Canvas(Modifier.fillMaxSize().padding(18.dp)) {
                val max = size.minDimension / 2
                drawCircle(color.copy(alpha = 0.18f), radius = max)
                drawCircle(color.copy(alpha = 0.55f), radius = max * (0.35f + 0.65f * fill))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(if (Breathing.inhaling(elapsed)) R.string.breathe_in else R.string.breathe_out),
                    style = MaterialTheme.typography.titleLarge,
                    color = WearColors.onSurface,
                )
                Text(
                    "${((Breathing.DURATION_MS - elapsed + 999) / 1000)} s",
                    style = MaterialTheme.typography.bodySmall,
                    color = WearColors.onSurfaceVariant,
                )
            }
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(horizontal = 24.dp),
            ) {
                Text(stringResource(R.string.breathe_done), style = MaterialTheme.typography.titleMedium, color = WearColors.onSurface, textAlign = TextAlign.Center)
                Spacer(Modifier.height(10.dp))
                Button(onClick = onMeasure) { Text(stringResource(R.string.breathe_measure)) }
                Spacer(Modifier.height(6.dp))
                Button(onClick = onDone) { Text(stringResource(R.string.breathe_close)) }
            }
        }
    }
}
