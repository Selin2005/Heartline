// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.bubbles

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import kotlin.math.exp
import kotlin.math.min

/**
 * The watch's measuring animation ([ParticleGlobe]): a globe of points turning in 3D that lights
 * up from the bottom with the progress and beats with the pulse, on a black round screen.
 * [content] (the seconds, the value) sits in its middle.
 *
 * @param frameMs a fixed clock instead of the running one (screenshots); [phaseFrameMs] is then the time in [phase]
 * @param animate false draws one still frame, for "Remove animations"
 * @param maxFps caps the frame rate (30 on the watch, to save battery)
 * @param fit how many globe radii either side of the centre fit in the box (larger: a smaller globe)
 */
@Composable
fun ParticleGlobeOrb(
    style: BubbleStyle,
    phase: BubblePhase,
    accent: Color,
    modifier: Modifier = Modifier,
    progress: Float = 0f,
    bpm: Int? = null,
    seed: Int = 7,
    animate: Boolean = true,
    maxFps: Int = 30,
    fit: Float = 1.9f,
    frameMs: Long? = null,
    phaseFrameMs: Long? = null,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val globe = remember(style, seed) { ParticleGlobe(style, seed = seed) }
    val live = animate && frameMs == null && !LocalInspectionMode.current
    var now by remember { mutableLongStateOf(0L) }
    if (live) {
        LaunchedEffect(maxFps) {
            val start = withFrameMillis { it }
            val step = 1000L / maxFps.coerceIn(1, 120)
            var last = -step
            while (true) {
                withFrameMillis { ms ->
                    val elapsed = ms - start
                    if (elapsed - last >= step) {
                        now = elapsed
                        last = elapsed
                    }
                }
            }
        }
    }
    val phaseStart = remember(phase) { now }
    // Ease the progress so the light climbs smoothly between the sensor's updates.
    val target = progress.coerceIn(0f, 1f)
    val progressShown = remember { floatArrayOf(target) }
    val shownAt = remember { longArrayOf(0L) }
    if (live) {
        val dt = (now - shownAt[0]).coerceIn(0, 200)
        progressShown[0] += (target - progressShown[0]) * (1f - exp(-dt / 220f))
        shownAt[0] = now
    } else {
        progressShown[0] = target
    }
    val clock = frameMs ?: if (live) now else STILL_FRAME_MS
    val inPhase = phaseFrameMs ?: if (live) now - phaseStart else STILL_PHASE_MS

    Box(
        modifier.semantics {
            if (phase == BubblePhase.MEASURING || phase == BubblePhase.HINT) progressBarRangeInfo = ProgressBarRangeInfo(target, 0f..1f)
        },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            globe.frame(phase, clock, inPhase, progressShown[0], bpm)
            drawGlobe(globe, accent, fit)
        }
        content()
    }
}

private const val STILL_FRAME_MS = 3_400L
private const val STILL_PHASE_MS = 2_000L
private val AMBER = Color(0xFFFFB020)
private val GREY = Color(0xFF9AA0A6)
private val COOL = Color(0xFF4FA8FF)
private val WATER = Color(0xFF4FC3F7)
private val FAT = Color(0xFFFFC857)

private fun DrawScope.drawGlobe(g: ParticleGlobe, accent: Color, fit: Float) {
    val unit = min(size.width, size.height) * 0.5f / fit
    val c = Offset(size.width / 2, size.height / 2)
    var tone = accent
    if (g.style == BubbleStyle.TEMPERATURE) tone = lerp(lerp(accent, COOL, 0.6f), accent, g.warmth)
    tone = lerp(lerp(tone, AMBER, g.amber), GREY, g.grey)

    // A faint halo behind the globe, in its colour.
    drawCircle(
        Brush.radialGradient(listOf(tone.copy(alpha = 0.18f), Color.Transparent), c, unit * 1.5f * g.scale),
        radius = unit * 1.5f * g.scale,
        center = c,
    )
    for (k in 0 until g.count) {
        val i = g.order[k]
        val a = g.alpha[i]
        if (a <= 0.01f) continue
        val color = if (g.lit[i]) {
            val base = if (g.style == BubbleStyle.BODY) {
                when (g.layer[i]) {
                    0 -> lerp(lerp(WATER, AMBER, g.amber), GREY, g.grey)
                    1 -> tone
                    else -> lerp(lerp(FAT, AMBER, g.amber), GREY, g.grey)
                }
            } else {
                tone
            }
            lerp(base, Color.White, g.light[i])
        } else {
            Color.White
        }
        drawCircle(color.copy(alpha = a.coerceIn(0f, 1f)), g.size[i] * unit, Offset(c.x + g.x[i] * unit, c.y + g.y[i] * unit))
    }
    if (g.fillAlpha > 0f) {
        drawOval(
            lerp(tone, Color.White, 0.5f).copy(alpha = g.fillAlpha),
            topLeft = Offset(c.x - g.fillRx * unit, c.y + g.fillY * unit - g.fillRy * unit),
            size = Size(g.fillRx * unit * 2, g.fillRy * unit * 2),
            style = Stroke(unit * 0.02f),
        )
    }
}
