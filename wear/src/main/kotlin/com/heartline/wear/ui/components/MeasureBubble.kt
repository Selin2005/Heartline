// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.ui.components

import android.provider.Settings
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import com.heartline.bubbles.BubbleOrb
import com.heartline.bubbles.BubblePhase
import com.heartline.bubbles.BubbleStyle
import com.heartline.shared.model.Metric
import com.heartline.wear.ui.theme.WearColors

/** False when the system's "Remove animations" is on: the bubble then shows one still frame. */
@Composable
fun motionEnabled(): Boolean {
    val context = LocalContext.current
    return remember { runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f) > 0f }
}

/**
 * The measuring bubble on the watch's dark round screen, in the metric's colour. Draws at most 30
 * frames a second to save battery; [frameMs] fixes the clock for screenshots.
 */
@Composable
fun MeasureBubble(
    metric: Metric,
    phase: BubblePhase,
    modifier: Modifier = Modifier,
    progress: Float = 0f,
    bpm: Int? = null,
    animate: Boolean = true,
    frameMs: Long? = null,
    content: @Composable BoxScope.() -> Unit = {},
) {
    BubbleOrb(
        style = BubbleStyle.of(metric),
        phase = phase,
        accent = WearColors.metric(metric),
        dark = true,
        modifier = modifier,
        progress = progress,
        bpm = bpm,
        seed = metric.ordinal + 3,
        animate = animate && motionEnabled(),
        maxFps = 30,
        frameMs = frameMs ?: if (animate) null else 1_350L,
        phaseFrameMs = if (frameMs != null || !animate) 1_350L else null,
        content = content,
    )
}

/** Text drawn over a bubble: white with a soft shadow, so it reads on the brightest liquid. */
fun onBubble(style: TextStyle): TextStyle = style.copy(
    color = Color.White,
    shadow = Shadow(Color.Black.copy(alpha = 0.55f), Offset(0f, 2f), blurRadius = 10f),
)
