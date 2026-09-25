package com.heartline.wear.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * How to take an ECG, seen from above, in a flat friendly style: the watch (a heart on its screen)
 * on the wrist and the other hand pointing, index finger on the top key (2 o'clock), which glows. Animated:
 * the finger rests on the key, lifts off briefly and comes back. Drawn on a 140 × 100 grid.
 */
@Composable
fun EcgKeyIllustration(accent: Color, modifier: Modifier = Modifier, background: Color = Color.Black, animate: Boolean = true) {
    val t = if (animate) {
        val transition = rememberInfiniteTransition(label = "ecgKey")
        val v by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(2_800, easing = LinearEasing)), label = "t")
        v
    } else {
        0.2f
    }
    // 0–0.6 on the key (pulses), 0.6–0.75 lifting off, 0.75–1 coming back.
    val lift = when {
        t < 0.6f -> 0f
        t < 0.75f -> (t - 0.6f) / 0.15f
        else -> 1f - (t - 0.75f) / 0.25f
    }
    val onKey = lift < 0.05f
    val pulse = if (t < 0.6f) t / 0.6f else 0f
    Canvas(modifier) {
        val u = minOf(size.width / 140f, size.height / 100f)
        translate((size.width - 140f * u) / 2, (size.height - 100f * u) / 2) {
            scene(u, accent, background, lift, onKey, pulse)
        }
    }
}

// Flat, friendly palette (no realistic shading): two warm tones tell the two hands apart.
private val ARM = Color(0xFFD9A583)
private val ARM_EDGE = Color(0xFFE6B996)
private val HAND = Color(0xFFF4CBA9)
private val HAND_EDGE = Color(0xFFDFAF8B)
private val NAIL = Color(0xFFFBE3D3)
private val STRAP = Color(0xFF2B3038)
private val CASE_LIGHT = Color(0xFFE1E5EA)
private val CASE_DARK = Color(0xFF8D949E)
private val BEZEL = Color(0xFF1C1F24)

private fun DrawScope.scene(u: Float, accent: Color, background: Color, lift: Float, onKey: Boolean, pulse: Float) {
    fun p(x: Float, y: Float) = Offset(x * u, y * u)
    val cx = 60f
    val cy = 58f
    val r = 27f

    // Wrist: a soft band that fades out on both sides, with the strap across it.
    drawRoundRect(
        Brush.verticalGradient(listOf(ARM_EDGE, ARM), startY = 41 * u, endY = 76 * u),
        p(0f, 41f),
        Size(140 * u, 35 * u),
        CornerRadius(17 * u),
    )
    drawRoundRect(STRAP, p(cx - 15f, 12f), Size(30 * u, 88 * u), CornerRadius(8 * u))
    drawRect(Brush.horizontalGradient(listOf(background, Color.Transparent), startX = 0f, endX = 30 * u), p(0f, 39f), Size(30 * u, 40 * u))
    drawRect(Brush.horizontalGradient(listOf(Color.Transparent, background), startX = 110 * u, endX = 140 * u), p(110f, 39f), Size(30 * u, 40 * u))
    drawRect(Brush.verticalGradient(listOf(background, Color.Transparent), startY = 10 * u, endY = 24 * u), p(cx - 16f, 10f), Size(32 * u, 14 * u))
    drawRect(Brush.verticalGradient(listOf(Color.Transparent, background), startY = 88 * u, endY = 100 * u), p(cx - 16f, 88f), Size(32 * u, 12 * u))

    // Side keys at 2 and 4 o'clock; the top one glows while touched.
    val keyAngle = -32f * PI.toFloat() / 180f
    val key = p(cx + (r + 2.5f) * cos(keyAngle), cy + (r + 2.5f) * sin(keyAngle))
    if (onKey) {
        drawCircle(
            Brush.radialGradient(listOf(accent.copy(alpha = 0.55f), Color.Transparent), center = key, radius = 16f * u),
            radius = 16f * u,
            center = key,
        )
        drawCircle(accent.copy(alpha = 0.7f * (1 - pulse)), radius = (8f + 12f * pulse) * u, center = key, style = Stroke(1.6f * u))
    }
    for ((deg, lit) in listOf(-32f to true, 32f to false)) {
        rotate(deg, pivot = p(cx, cy)) {
            drawRoundRect(if (lit) accent else CASE_DARK, p(cx + r - 2f, cy - 4.5f), Size(6.5f * u, 9 * u), CornerRadius(3f * u))
        }
    }

    // Watch: light case, dark bezel, a heart on the screen.
    drawCircle(Brush.linearGradient(listOf(CASE_LIGHT, CASE_DARK), p(cx - r, cy - r), p(cx + r, cy + r)), r * u, p(cx, cy))
    drawCircle(BEZEL, (r - 3f) * u, p(cx, cy))
    drawCircle(Color.Black, (r - 6.5f) * u, p(cx, cy))
    val heart = Path().apply {
        val s = 0.62f
        moveTo(cx * u, (cy + 8f * s) * u)
        cubicTo((cx - 14f * s) * u, (cy - 1f * s) * u, (cx - 9f * s) * u, (cy - 13f * s) * u, cx * u, (cy - 6f * s) * u)
        cubicTo((cx + 9f * s) * u, (cy - 13f * s) * u, (cx + 14f * s) * u, (cy - 1f * s) * u, cx * u, (cy + 8f * s) * u)
        close()
    }
    drawPath(heart, accent)

    // The other hand: a rounded palm at the top right and the index finger resting on the key.
    val axis = -22f // from the fingertip towards the hand, degrees
    val ax = cos(axis * PI.toFloat() / 180f)
    val ay = sin(axis * PI.toFloat() / 180f)
    val tipGap = 5.5f + 10f * lift
    val tip = Offset(key.x / u + ax * tipGap, key.y / u + ay * tipGap)
    rotate(axis, pivot = p(tip.x, tip.y)) {
        val w = 12f
        // Palm and folded fingers, beyond the index finger.
        drawRoundRect(HAND, p(tip.x + 34f, tip.y - 11f), Size(40 * u, 32 * u), CornerRadius(14 * u))
        drawRoundRect(HAND_EDGE, p(tip.x + 34f, tip.y - 11f), Size(40 * u, 32 * u), CornerRadius(14 * u), style = Stroke(1f * u))
        drawRoundRect(HAND, p(tip.x + 26f, tip.y + 4f), Size(16 * u, 13 * u), CornerRadius(6.5f * u))
        drawRoundRect(HAND_EDGE, p(tip.x + 26f, tip.y + 4f), Size(16 * u, 13 * u), CornerRadius(6.5f * u), style = Stroke(1f * u))
        // Index finger.
        drawRoundRect(HAND, p(tip.x, tip.y - w / 2), Size(44 * u, w * u), CornerRadius(w / 2 * u))
        drawRoundRect(HAND_EDGE, p(tip.x, tip.y - w / 2), Size(44 * u, w * u), CornerRadius(w / 2 * u), style = Stroke(1f * u))
        drawRoundRect(NAIL, p(tip.x + 2f, tip.y - w / 2 + 2.5f), Size(7 * u, (w - 5f) * u), CornerRadius(3f * u))
    }
}
