// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.ui.components

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import com.heartline.shared.design.Palette
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/*
 * Effects for the round screen: they come in from the edge (confetti, a ripple), run around it (a
 * light sweep) or breathe on it (a heartbeat glow). Used only inside the app, never in tiles.
 * Each is short, draws nothing when finished, and is off when the user turned off celebrations or
 * the system's animations.
 */

/** Celebrations switched on in settings (the app provides it from the synced settings). */
val LocalCelebrations = compositionLocalOf { true }

/** False when the user turned celebrations off, or the system's "Remove animations" is on. */
@Composable
fun effectsEnabled(): Boolean {
    val context = LocalContext.current
    val scale = remember { runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f) }
    return LocalCelebrations.current && scale > 0f
}

/** One confetti piece at a moment: position in screen radii from the centre (y down), spin, flip, opacity. */
data class Piece(val x: Float, val y: Float, val angle: Float, val flip: Float, val alpha: Float, val color: Int, val width: Float, val length: Float)

/**
 * Confetti fired from random points on the round edge towards the middle, then falling and
 * fading. Pure and seeded: the same seed and time always give the same frame (screenshots, tests).
 */
class ConfettiField(seed: Int = 7, count: Int = 70, val durationMs: Long = 1_700L, private val colors: List<Int> = CELEBRATION) {
    private data class Spec(val edge: Float, val speed: Float, val spread: Float, val spin: Float, val flipHz: Float, val delay: Float, val color: Int, val w: Float, val l: Float, val a0: Float)

    private val specs = Random(seed).let { r ->
        List(count) {
            Spec(
                edge = r.nextFloat() * 2f * PI.toFloat(),
                speed = 1.1f + r.nextFloat() * 0.9f,
                spread = (r.nextFloat() - 0.5f) * 0.9f,
                spin = (r.nextFloat() - 0.5f) * 900f,
                flipHz = 1.5f + r.nextFloat() * 3f,
                delay = r.nextFloat() * 0.18f,
                color = colors[r.nextInt(colors.size)],
                w = 0.022f + r.nextFloat() * 0.018f,
                l = 0.04f + r.nextFloat() * 0.04f,
                a0 = r.nextFloat() * 360f,
            )
        }
    }

    /** Every piece at [tMs] after the burst; pieces not yet fired or gone are left out. */
    fun at(tMs: Long): List<Piece> {
        val life = durationMs / 1000f
        val now = tMs / 1000f
        if (now > life) return emptyList()
        // The whole burst fades out together over its last third.
        val fade = ((life - now) / (life * 0.35f)).coerceIn(0f, 1f)
        return specs.mapNotNull { s ->
            val t = now - s.delay * life
            if (t < 0f) return@mapNotNull null
            // Start on the rim, aim at the middle with some spread, slow down, and fall.
            val sx = cos(s.edge)
            val sy = sin(s.edge)
            val dir = s.edge + PI.toFloat() + s.spread
            val drag = 1f - kotlin.math.exp(-2.6f * t)
            val reach = s.speed * drag / 2.6f
            val x = sx + cos(dir) * reach
            val y = sy + sin(dir) * reach + 0.55f * t * t
            Piece(x, y, s.a0 + s.spin * t, cos(2f * PI.toFloat() * s.flipHz * t), fade, s.color, s.w, s.l)
        }
    }

    companion object {
        val CELEBRATION = listOf(
            Palette.Dark.ECG, Palette.Dark.BP, Palette.Dark.SPO2, Palette.Dark.STRESS, Palette.Dark.BODY, Palette.Dark.PRIMARY, 0xFFFFFFFF,
        ).map { it.toInt() }
    }
}

/** Milliseconds since [key] changed, ticking every frame until [durationMs]; [fixedMs] freezes it (screenshots). */
@Composable
private fun elapsed(key: Any?, durationMs: Long, fixedMs: Long?): Long {
    var t by remember(key) { mutableLongStateOf(fixedMs ?: 0L) }
    if (fixedMs == null) {
        LaunchedEffect(key) {
            val start = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                t = (now - start) / 1_000_000L
                if (t > durationMs) break
            }
        }
    }
    return t
}

/** Confetti bursting in from the round edge. Plays once per [key]. */
@Composable
fun EdgeConfetti(key: Any?, modifier: Modifier = Modifier, seed: Int = 7, frameMs: Long? = null) {
    if (!effectsEnabled() && frameMs == null) return
    val field = remember(seed) { ConfettiField(seed) }
    val t = elapsed(key, field.durationMs, frameMs)
    if (t > field.durationMs) return
    Canvas(modifier.fillMaxSize()) {
        val r = size.minDimension / 2
        val c = center
        field.at(t).forEach { p ->
            translate(c.x + p.x * r, c.y + p.y * r) {
                rotate(p.angle, Offset.Zero) {
                    // The flip squeezes the piece like paper turning over.
                    scale(abs(p.flip).coerceAtLeast(0.15f), 1f, Offset.Zero) {
                        drawRoundRect(
                            Color(p.color).copy(alpha = p.alpha),
                            topLeft = Offset(-p.width * r / 2, -p.length * r / 2),
                            size = Size(p.width * r, p.length * r),
                            cornerRadius = CornerRadius(p.width * r * 0.3f),
                        )
                    }
                }
            }
        }
    }
}

/** A band of light that runs once around the edge in [color] (a good result, a measurement starting). */
@Composable
fun EdgeGlowSweep(key: Any?, color: Color, modifier: Modifier = Modifier, durationMs: Long = 1_100L, frameMs: Long? = null) {
    if (!effectsEnabled() && frameMs == null) return
    val t = elapsed(key, durationMs, frameMs)
    if (t > durationMs) return
    val p = (t.toFloat() / durationMs).coerceIn(0f, 1f)
    Canvas(modifier.fillMaxSize()) {
        val stroke = size.minDimension * 0.035f
        val head = -90f + 360f * easeOut(p)
        val tail = 110f
        val fade = if (p > 0.75f) (1f - p) / 0.25f else 1f
        // Tail drawn as short segments getting fainter behind the head.
        val steps = 24
        for (i in 0 until steps) {
            val a = head - tail * i / steps
            val alpha = (1f - i.toFloat() / steps) * fade
            drawArc(
                color.copy(alpha = alpha * 0.9f),
                startAngle = a - tail / steps,
                sweepAngle = tail / steps + 0.5f,
                useCenter = false,
                topLeft = Offset(stroke / 2, stroke / 2),
                size = Size(size.width - stroke, size.height - stroke),
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        glow(color.copy(alpha = 0.25f * fade), stroke * 3)
    }
}

/** A ring of [color] travelling in from the edge and fading (a finger touching the key). */
@Composable
fun EdgeRipple(key: Any?, color: Color, modifier: Modifier = Modifier, durationMs: Long = 700L, frameMs: Long? = null) {
    if (!effectsEnabled() && frameMs == null) return
    val t = elapsed(key, durationMs, frameMs)
    if (t > durationMs) return
    val p = (t.toFloat() / durationMs).coerceIn(0f, 1f)
    Canvas(modifier.fillMaxSize()) {
        val r = size.minDimension / 2
        val ring = r * (1f - 0.35f * easeOut(p))
        drawCircle(color.copy(alpha = 0.7f * (1f - p)), radius = ring, style = Stroke(r * 0.05f * (1f - p) + 1f))
    }
}

/** A soft glow on the rim that beats with [bpm] (live heart rate and ECG screens). */
@Composable
fun EdgePulse(bpm: Int?, color: Color, modifier: Modifier = Modifier) {
    if (bpm == null || bpm <= 0 || !effectsEnabled()) return
    val period = (60_000 / bpm.coerceIn(30, 200))
    val beat by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(period, easing = LinearEasing), RepeatMode.Restart),
        label = "beat",
    )
    Canvas(modifier.fillMaxSize()) {
        // Quick rise, slow fall, like a pulse wave.
        val strength = if (beat < 0.15f) beat / 0.15f else (1f - (beat - 0.15f) / 0.85f)
        glow(color.copy(alpha = 0.35f * strength * strength), size.minDimension * 0.09f)
    }
}

/** A soft ring of light just inside the round edge. */
private fun DrawScope.glow(color: Color, width: Float) {
    val r = size.minDimension / 2
    drawCircle(
        Brush.radialGradient(0f to Color.Transparent, ((r - width) / r).coerceIn(0f, 1f) to Color.Transparent, 1f to color, center = center, radius = r),
        radius = r,
    )
}

private fun easeOut(p: Float) = 1f - (1f - p) * (1f - p) * (1f - p)

/** Distance from the centre in screen radii (tests). */
internal fun Piece.radius() = hypot(x, y)
