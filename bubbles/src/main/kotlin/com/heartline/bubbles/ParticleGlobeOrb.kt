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
 * @param dark on a dark background (the watch, the phone's dark theme): unlit points are faint white; on a light one, faint ink
 * @param surroundings fills the room around the globe ([GlobeSurroundings]: orbits, dust, heartbeat ripples), for a
 *   large box such as the phone's screen; give it a larger [fit] so there is room
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
    dark: Boolean = true,
    surroundings: Boolean = false,
    frameMs: Long? = null,
    phaseFrameMs: Long? = null,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val globe = remember(style, seed) { ParticleGlobe(style, seed = seed) }
    val around = remember(style, seed, surroundings) { if (surroundings) GlobeSurroundings(style, seed) else null }
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
            val tone = toneOf(globe, accent)
            if (around != null) {
                around.frame(phase, clock, inPhase, progressShown[0], bpm, globe.scale)
                drawAround(around, tone, fit, dark, front = false)
            }
            drawGlobe(globe, accent, tone, fit, dark)
            if (around != null) drawAround(around, tone, fit, dark, front = true)
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
private val INK = Color(0xFF1C1C28)

private fun toneOf(g: ParticleGlobe, accent: Color): Color {
    var tone = accent
    if (g.style == BubbleStyle.TEMPERATURE) tone = lerp(lerp(accent, COOL, 0.6f), accent, g.warmth)
    return lerp(lerp(tone, AMBER, g.amber), GREY, g.grey)
}

/** The surroundings: ripples and dust and the far half of the orbits behind the globe ([front] false), the near half in front. */
private fun DrawScope.drawAround(s: GlobeSurroundings, tone: Color, fit: Float, dark: Boolean, front: Boolean) {
    val unit = min(size.width, size.height) * 0.5f / fit
    val c = Offset(size.width / 2, size.height / 2)
    val faint = if (dark) Color.White else INK
    if (!front) {
        for (k in 0 until GlobeSurroundings.RIPPLES) {
            val a = s.rippleAlpha[k]
            if (a <= 0.005f) continue
            drawCircle(tone.copy(alpha = a), s.rippleRadius[k] * unit, c, style = Stroke(unit * 0.012f))
        }
        for (j in 0 until GlobeSurroundings.DUST) {
            val a = s.dustAlpha[j]
            if (a <= 0.01f) continue
            val color = lerp(faint, tone, s.dustTint[j])
            drawCircle(color.copy(alpha = a.coerceIn(0f, 1f)), s.dustSize[j] * unit, Offset(c.x + s.dustX[j] * unit, c.y + s.dustY[j] * unit))
        }
    }
    for (i in 0 until GlobeSurroundings.ORBIT_POINTS) {
        if (s.orbitFront[i] != front) continue
        val a = s.orbitAlpha[i]
        if (a <= 0.01f) continue
        val color = if (s.orbitLit[i]) (if (dark) lerp(tone, Color.White, 0.25f) else tone) else faint
        drawCircle(color.copy(alpha = a.coerceIn(0f, 1f)), s.orbitSize[i] * unit, Offset(c.x + s.orbitX[i] * unit, c.y + s.orbitY[i] * unit))
    }
}

private fun DrawScope.drawGlobe(g: ParticleGlobe, accent: Color, tone: Color, fit: Float, dark: Boolean) {
    val unit = min(size.width, size.height) * 0.5f / fit
    val c = Offset(size.width / 2, size.height / 2)

    // A faint halo behind the globe, in its colour.
    drawCircle(
        Brush.radialGradient(listOf(tone.copy(alpha = if (dark) 0.18f else 0.12f), Color.Transparent), c, unit * 1.5f * g.scale),
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
            // On a light background the highlights go darker, not whiter, so the points keep their edge.
            if (dark) lerp(base, Color.White, g.light[i]) else lerp(base, INK, g.light[i] * 0.35f)
        } else {
            if (dark) Color.White else INK
        }
        drawCircle(color.copy(alpha = (if (!dark && !g.lit[i]) a * 0.9f else a).coerceIn(0f, 1f)), g.size[i] * unit, Offset(c.x + g.x[i] * unit, c.y + g.y[i] * unit))
    }
    if (g.fillAlpha > 0f) {
        drawOval(
            (if (dark) lerp(tone, Color.White, 0.5f) else tone).copy(alpha = g.fillAlpha),
            topLeft = Offset(c.x - g.fillRx * unit, c.y + g.fillY * unit - g.fillRy * unit),
            size = Size(g.fillRx * unit * 2, g.fillRy * unit * 2),
            style = Stroke(unit * 0.02f),
        )
    }
}
