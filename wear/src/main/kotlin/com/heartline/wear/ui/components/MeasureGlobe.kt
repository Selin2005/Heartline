// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.ui.components

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.CurvedDirection
import androidx.wear.compose.foundation.CurvedLayout
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ProgressIndicatorDefaults
import androidx.wear.compose.material3.curvedText
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

/** False when the system's "Remove animations" is on: the globe then shows one still frame. */
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
    surroundings: Boolean = false,
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
        // With orbits and dust around it the globe is a little smaller, so they fit the round screen.
        fit = if (surroundings) 2.2f else 1.9f,
        surroundings = surroundings,
        frameMs = frameMs ?: if (animate) null else STILL_MS,
        phaseFrameMs = if (frameMs != null || !animate) 2_000L else null,
        content = content,
    )
}

private const val STILL_MS = 3_400L

/**
 * A measuring screen on the watch: a thin progress ring at the rim, the particle globe with its
 * orbits in the middle, [center] (the percentage, the pulse) over a soft shade that keeps it
 * readable, and the guide as curved text along the bottom of the round screen ([bottom], amber
 * when [warn]), with an optional curved label along the top ([top]). [below] sits under the
 * globe, above the curved guide (a pulse trace).
 */
@Composable
fun MeasureFace(
    metric: Metric,
    phase: BubblePhase,
    progress: Float,
    modifier: Modifier = Modifier,
    bpm: Int? = null,
    animate: Boolean = true,
    ring: Boolean = true,
    top: String? = null,
    topColor: Color = WearColors.onSurfaceVariant,
    bottom: String? = null,
    warn: Boolean = false,
    below: @Composable (BoxScope.() -> Unit)? = null,
    center: @Composable BoxScope.() -> Unit = {},
) {
    val accent = WearColors.metric(metric)
    Box(modifier.fillMaxSize().background(WearColors.background), contentAlignment = Alignment.Center) {
        if (ring) {
            CircularProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxSize().padding(2.dp),
                strokeWidth = 3.dp,
                colors = ProgressIndicatorDefaults.colors(indicatorColor = if (warn) WearColors.warn else accent, trackColor = WearColors.surfaceHigh),
            )
        }
        MeasureGlobe(metric, phase, Modifier.fillMaxSize().padding(8.dp), progress = progress, bpm = bpm, animate = animate, surroundings = true) {
            // A soft dark shade behind the number: it reads over the brightest points.
            Box(
                Modifier.fillMaxSize(0.62f).drawBehind {
                    drawCircle(Brush.radialGradient(0f to Color.Black.copy(alpha = 0.62f), 0.55f to Color.Black.copy(alpha = 0.42f), 1f to Color.Transparent))
                },
            )
            center()
        }
        if (below != null) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                Box(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = minOf(maxWidth, maxHeight) * 0.165f),
                    contentAlignment = Alignment.Center,
                    content = below,
                )
            }
        }
        if (top != null) CurvedLabel(top, topColor, atTop = true)
        if (bottom != null) {
            CurvedLabel(
                bottom,
                if (warn) WearColors.warn else WearColors.onSurface,
                atTop = false,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/**
 * One line of text following the round screen's edge, inside the progress ring: along the top
 * ([atTop]) or along the bottom, reading left to right either way. A long line ends in an ellipsis
 * rather than running round the screen.
 */
@Composable
fun CurvedLabel(text: String, color: Color, atTop: Boolean, modifier: Modifier = Modifier) {
    // A long line in the smaller arc style, so it fits rather than ending in an ellipsis.
    val style = if (text.length > 22) MaterialTheme.typography.arcSmall else MaterialTheme.typography.arcMedium
    CurvedLayout(
        modifier.fillMaxSize().padding(9.dp),
        anchor = if (atTop) 270f else 90f,
        angularDirection = if (atTop) CurvedDirection.Angular.Normal else CurvedDirection.Angular.Reversed,
    ) {
        curvedText(text, color = color, style = style, maxSweepAngle = if (atTop) 120f else 160f, overflow = TextOverflow.Ellipsis)
    }
}

/** Text drawn over the globe: white with a soft shadow, so it reads over the brightest points. */
fun onBubble(style: TextStyle): TextStyle = style.copy(
    color = Color.White,
    shadow = Shadow(Color.Black.copy(alpha = 0.55f), Offset(0f, 2f), blurRadius = 10f),
)

/** A progress (0…1) as the whole percentage shown on the measuring screens. */
fun percent(progress: Float): String = "${(progress.coerceIn(0f, 1f) * 100f + 1e-3f).toInt()}"
