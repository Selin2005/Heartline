// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.bubbles

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * A glossy 3D bubble that shows a measurement: it gathers while waiting, fills with the progress,
 * moves in the metric's own way (beats, breathes, fizzes…), wobbles on a hint and bursts when done.
 * [content] sits in its middle (the value, the seconds left).
 *
 * @param accent the metric's colour from the app's theme
 * @param dark true on a dark background (softer rim, a glow instead of a shadow)
 * @param frameMs a fixed clock instead of the running one (screenshots); [phaseFrameMs] is then the time in [phase]
 * @param animate false draws one still frame, for "Remove animations"
 * @param maxFps caps the frame rate (the watch draws at 30 to save battery)
 */
@Composable
fun BubbleOrb(
    style: BubbleStyle,
    phase: BubblePhase,
    accent: Color,
    dark: Boolean,
    modifier: Modifier = Modifier,
    progress: Float = 0f,
    bpm: Int? = null,
    seed: Int = 11,
    animate: Boolean = true,
    maxFps: Int = 60,
    frameMs: Long? = null,
    phaseFrameMs: Long? = null,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val scene = remember(style, seed) { BubbleScene(style, seed) }
    var now by remember { mutableLongStateOf(0L) }
    if (frameMs == null && animate) {
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
    val clock = frameMs ?: if (animate) now else STILL_FRAME_MS
    val phaseStart = remember(phase) { now }
    val inPhase = phaseFrameMs ?: if (animate && frameMs == null) (now - phaseStart) else STILL_PHASE_MS
    val shown by animateFloatAsState(progress, if (frameMs == null && animate) tween(450) else tween(0), label = "bubble-progress")
    val frame = scene.frame(phase, clock, inPhase, if (frameMs == null) shown else progress, bpm)
    val path = remember { Path() }
    val wave = remember { Path() }
    val trace = remember { Path() }

    Box(
        modifier.semantics {
            if (frame.showProgress) progressBarRangeInfo = ProgressBarRangeInfo(frame.progress, 0f..1f)
        },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) { drawBubble(frame, style, accent, dark, path, wave, trace) }
        content()
    }
}

private const val STILL_FRAME_MS = 1_350L
private const val STILL_PHASE_MS = 2_000L
private val AMBER = Color(0xFFFFB020)
private val GREY = Color(0xFF9AA0A6)
private val COOL = Color(0xFF4FA8FF)
private val FAT = Color(0xFFFFC857)
private val WATER = Color(0xFF4FC3F7)

private fun DrawScope.drawBubble(f: BubbleFrame, style: BubbleStyle, accent: Color, dark: Boolean, path: Path, wave: Path, trace: Path) {
    val base = min(size.width, size.height) * 0.30f
    val r = base * f.scale
    val c = Offset(size.width / 2 + f.centerX * base, size.height / 2 + f.centerY * base)

    // Temperature warms up from a pale, cool tint to the full colour as it measures.
    var tone = if (style == BubbleStyle.TEMPERATURE) lerp(lerp(lerp(accent, COOL, 0.35f), Color.White, 0.45f), accent, f.warmth) else accent
    tone = lerp(tone, AMBER, f.amber)
    tone = lerp(tone, GREY, f.grey)
    val light = lerp(tone, Color.White, 0.55f)
    val deep = lerp(tone, Color.Black, if (dark) 0.35f else 0.45f)

    // Progress ring, behind everything, so the small bubbles cross it.
    if (f.showProgress) {
        val ringR = base * 1.22f
        val w = base * 0.045f
        val tl = Offset(size.width / 2 - ringR, size.height / 2 - ringR)
        drawCircle((if (dark) Color.White else Color.Black).copy(alpha = 0.07f), ringR, Offset(size.width / 2, size.height / 2), style = Stroke(w))
        drawArc(
            Brush.sweepGradient(listOf(light, tone, light), Offset(size.width / 2, size.height / 2)),
            startAngle = -90f,
            sweepAngle = 360f * f.progress,
            useCenter = false,
            topLeft = tl,
            size = Size(ringR * 2, ringR * 2),
            style = Stroke(w, cap = StrokeCap.Round),
        )
    }

    // Shadow under the floating bubble (a glow on a dark background).
    val shadowC = Offset(c.x, size.height / 2 + base * 1.2f)
    val shadowColor = if (dark) tone.copy(alpha = 0.32f * f.alpha) else Color.Black.copy(alpha = 0.16f * f.alpha)
    drawOval(
        Brush.radialGradient(listOf(shadowColor, Color.Transparent), shadowC, base * 0.8f),
        topLeft = Offset(shadowC.x - base * 0.8f, shadowC.y - base * 0.16f),
        size = Size(base * 1.6f, base * 0.32f),
    )

    for (ring in f.rings) drawCircle(tone.copy(alpha = ring.alpha), base * ring.radius, c, style = Stroke(base * 0.03f))
    for (o in f.behind) miniSphere(o, c, base, tone, light, deep)

    // The outline, smoothed through the points (Catmull-Rom as cubic Béziers).
    outline(path, f.radii, c, r)
    if (f.alpha > 0.01f) {
        clipPath(path) {
            // The empty part is glassy and pale, so the liquid stands out as it fills.
            val glass = if (dark) {
                listOf(lerp(tone, Color.White, 0.45f), lerp(tone, Color.Black, 0.25f), lerp(tone, Color.Black, 0.6f))
            } else {
                listOf(lerp(tone, Color.White, 0.88f), lerp(tone, Color.White, 0.6f), lerp(tone, Color.White, 0.25f))
            }
            drawRect(Brush.radialGradient(glass, Offset(c.x - r * 0.35f, c.y - r * 0.45f), r * 2f), alpha = f.alpha)
            if (style == BubbleStyle.BODY && f.layers.size == 3) {
                liquid(wave, c, r, f.layers[2], f.wavePhase, f.waveHeight, WATER, f.alpha)
                liquid(wave, c, r, f.layers[1], f.wavePhase + 1.3f, f.waveHeight, tone, f.alpha)
                liquid(wave, c, r, f.layers[0], f.wavePhase + 2.4f, f.waveHeight, FAT, f.alpha)
            } else if (f.level > 0f) {
                liquid(wave, c, r, f.level, f.wavePhase, f.waveHeight, tone, f.alpha)
            }
            for (o in f.inside) {
                val p = Offset(c.x + o.x * r, c.y + o.y * r)
                drawCircle(Color.White.copy(alpha = 0.22f * o.alpha), o.radius * r, p)
                drawCircle(Color.White.copy(alpha = 0.7f * o.alpha), o.radius * r, p, style = Stroke(r * 0.008f))
                drawCircle(Color.White.copy(alpha = 0.9f * o.alpha), o.radius * r * 0.28f, Offset(p.x - o.radius * r * 0.35f, p.y - o.radius * r * 0.35f))
            }
            if (style == BubbleStyle.TEMPERATURE) {
                val s = f.shimmer * 4f - 2f
                drawRect(
                    Brush.linearGradient(
                        listOf(Color.Transparent, Color.White.copy(alpha = 0.22f), Color.Transparent),
                        Offset(c.x + (s - 0.4f) * r, c.y - r),
                        Offset(c.x + (s + 0.4f) * r, c.y + r),
                    ),
                    alpha = f.alpha,
                )
            }
            if (style == BubbleStyle.ECG) ecgTrace(trace, c, r, f.trace, f.alpha)
            // Light that went through the bubble gathers at the bottom right.
            drawRect(Brush.radialGradient(listOf(light.copy(alpha = 0.55f * f.glow), Color.Transparent), Offset(c.x + r * 0.35f, c.y + r * 0.55f), r * 0.9f), alpha = f.alpha)
            // Fresnel rim: the edge is brighter than the middle, as on a soap bubble or glass.
            drawRect(
                Brush.radialGradient(
                    0f to Color.Transparent,
                    0.74f to Color.Transparent,
                    1f to Color.White.copy(alpha = if (dark) 0.38f else 0.5f),
                    center = c,
                    radius = r * 1.03f,
                ),
                alpha = f.alpha,
            )
            // Specular highlight, top left.
            val spec = Offset(c.x - r * 0.4f, c.y - r * 0.5f)
            rotate(-28f, spec) {
                drawOval(
                    Brush.radialGradient(listOf(Color.White.copy(alpha = 0.9f), Color.White.copy(alpha = 0f)), spec, r * 0.34f),
                    topLeft = Offset(spec.x - r * 0.32f, spec.y - r * 0.18f),
                    size = Size(r * 0.64f, r * 0.36f),
                    alpha = f.alpha,
                )
            }
            drawCircle(Color.White.copy(alpha = 0.4f * f.alpha), r * 0.055f, Offset(c.x + r * 0.46f, c.y + r * 0.5f))
        }
        drawPath(path, Color.White.copy(alpha = (if (dark) 0.18f else 0.3f) * f.alpha), style = Stroke(r * 0.012f))
    }

    for (o in f.front) miniSphere(o, c, base, tone, light, deep)
    for (o in f.sparks) miniSphere(o, c, base, tone, light, deep)
}

private fun outline(path: Path, radii: FloatArray, c: Offset, r: Float) {
    val n = radii.size
    fun point(i: Int): Offset {
        val k = ((i % n) + n) % n
        val a = (k * 2 * PI / n).toFloat()
        return Offset(c.x + cos(a) * radii[k] * r, c.y + sin(a) * radii[k] * r)
    }
    path.reset()
    val first = point(0)
    path.moveTo(first.x, first.y)
    for (i in 0 until n) {
        val p0 = point(i - 1)
        val p1 = point(i)
        val p2 = point(i + 1)
        val p3 = point(i + 2)
        path.cubicTo(
            p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f,
            p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f,
            p2.x, p2.y,
        )
    }
    path.close()
}

/** Liquid up to [level] (0 empty, 1 full) with a moving wavy surface and a bright meniscus line. */
private fun DrawScope.liquid(wave: Path, c: Offset, r: Float, level: Float, phase: Float, height: Float, color: Color, alpha: Float) {
    if (level <= 0f) return
    val top = c.y + r * (1f - 2f * level)
    val left = c.x - r * 1.1f
    val right = c.x + r * 1.1f
    val steps = 24
    wave.reset()
    wave.moveTo(left, c.y + r * 1.2f)
    for (i in 0..steps) {
        val x = left + (right - left) * i / steps
        val u = (x - c.x) / r
        val y = top + r * height * (sin(u * 3.1f + phase) * 0.7f + sin(u * 5.3f - phase * 1.4f) * 0.3f)
        wave.lineTo(x, y)
    }
    wave.lineTo(right, c.y + r * 1.2f)
    wave.close()
    drawPath(
        wave,
        Brush.verticalGradient(listOf(color.copy(alpha = 0.92f), lerp(color, Color.Black, 0.3f).copy(alpha = 0.95f)), top, c.y + r),
        alpha = alpha,
    )
    drawPath(wave, Color.White.copy(alpha = 0.28f), style = Stroke(r * 0.018f), alpha = alpha)
}

/** A trace like an ECG strip running across the bubble, with a soft glow. */
private fun DrawScope.ecgTrace(path: Path, c: Offset, r: Float, scroll: Float, alpha: Float) {
    path.reset()
    val steps = 90
    for (i in 0..steps) {
        val u = i / steps.toFloat()
        val x = c.x - r + 2f * r * u
        val beat = ((u + scroll) * 2.2f) % 1f
        val y = c.y - r * 0.55f * ecgShape(beat)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path, Color.White.copy(alpha = 0.25f), style = Stroke(r * 0.09f, cap = StrokeCap.Round), alpha = alpha)
    drawPath(path, Color.White.copy(alpha = 0.9f), style = Stroke(r * 0.03f, cap = StrokeCap.Round), alpha = alpha)
}

/** A stylised PQRST complex over one beat (0…1), roughly -0.3…1. */
private fun ecgShape(x: Float): Float {
    fun bump(center: Float, width: Float, height: Float) = height * exp(-((x - center) * (x - center)) / (2 * width * width))
    return bump(0.18f, 0.03f, 0.12f) - bump(0.30f, 0.008f, 0.12f) + bump(0.33f, 0.012f, 1f) - bump(0.36f, 0.01f, 0.25f) + bump(0.58f, 0.05f, 0.22f)
}

/** A small glossy sphere (satellite, burst piece). */
private fun DrawScope.miniSphere(o: Orb, c: Offset, base: Float, tone: Color, light: Color, deep: Color) {
    if (o.alpha <= 0.01f) return
    val p = Offset(c.x + o.x * base, c.y + o.y * base)
    val rr = o.radius * base
    drawCircle(Brush.radialGradient(listOf(light, tone, deep), Offset(p.x - rr * 0.35f, p.y - rr * 0.4f), rr * 1.7f), rr, p, alpha = o.alpha)
    drawCircle(Color.White.copy(alpha = 0.85f * o.alpha), rr * 0.28f, Offset(p.x - rr * 0.38f, p.y - rr * 0.4f))
    drawCircle(Color.White.copy(alpha = 0.3f * o.alpha), rr, p, style = Stroke(rr * 0.08f))
}
