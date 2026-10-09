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
import com.heartline.bubbles.ParticleGlobeOrb
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
 * The watch's measuring animation, the particle globe ([ParticleGlobeOrb]): a globe of points
 * turning in 3D in the metric's colour, lit from the bottom by the progress and beating with the
 * pulse. At most 30 frames a second to save battery; [frameMs] fixes the clock for screenshots.
 */
@Composable
fun MeasureGlobe(
    metric: Metric,
    phase: BubblePhase,
    modifier: Modifier = Modifier,
    progress: Float = 0f,
    bpm: Int? = null,
    animate: Boolean = true,
    frameMs: Long? = null,
    content: @Composable BoxScope.() -> Unit = {},
) {
    ParticleGlobeOrb(
        style = BubbleStyle.of(metric),
        phase = phase,
        accent = WearColors.metric(metric),
        modifier = modifier,
        progress = progress,
        bpm = bpm,
        seed = metric.ordinal + 3,
        animate = animate && motionEnabled(),
        maxFps = 30,
        frameMs = frameMs ?: if (animate) null else STILL_MS,
        phaseFrameMs = if (frameMs != null || !animate) 2_000L else null,
        content = content,
    )
}

private const val STILL_MS = 3_400L

/** Text drawn over the globe: white with a soft shadow, so it reads over the brightest points. */
fun onBubble(style: TextStyle): TextStyle = style.copy(
    color = Color.White,
    shadow = Shadow(Color.Black.copy(alpha = 0.55f), Offset(0f, 2f), blurRadius = 10f),
)
