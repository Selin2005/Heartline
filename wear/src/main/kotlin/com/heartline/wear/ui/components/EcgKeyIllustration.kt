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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * How to take an ECG, seen from above: the watch on the left wrist and the index finger of the
 * other hand resting on the top key (2 o'clock), which lights up with a contact pulse. Animated:
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

private val SKIN = Color(0xFFD7A07E)
private val SKIN_SHADE = Color(0xFFB9805F)
private val SKIN_LIGHT = Color(0xFFE8BC9E)
private val NAIL = Color(0xFFF1D3C2)
private val STRAP = Color(0xFF30343B)
private val STRAP_EDGE = Color(0xFF474C55)
private val STEEL = Color(0xFFA7ADB6)
private val STEEL_DARK = Color(0xFF6E747D)
private val BEZEL = Color(0xFF1B1D21)

private fun DrawScope.scene(u: Float, accent: Color, background: Color, lift: Float, onKey: Boolean, pulse: Float) {
    fun p(x: Float, y: Float) = Offset(x * u, y * u)

    // Forearm across the scene, lit from above; it fades out at both sides (it goes on).
    drawRect(Brush.verticalGradient(listOf(SKIN_LIGHT, SKIN, SKIN_SHADE), startY = 32 * u, endY = 74 * u), p(0f, 32f), Size(140 * u, 42 * u))
    drawRect(Brush.horizontalGradient(listOf(background, Color.Transparent), startX = 0f, endX = 28 * u), p(0f, 31f), Size(28 * u, 44 * u))
    drawRect(Brush.horizontalGradient(listOf(Color.Transparent, background), startX = 112 * u, endX = 140 * u), p(112f, 31f), Size(28 * u, 44 * u))

    // Strap over the wrist.
    val cx = 54f
    val cy = 53f
    drawRect(STRAP, p(cx - 16f, 0f), Size(32 * u, 100 * u))
    drawLine(STRAP_EDGE, p(cx - 15f, 0f), p(cx - 15f, 100f), strokeWidth = 1.2f * u)
    drawLine(STRAP_EDGE, p(cx + 15f, 0f), p(cx + 15f, 100f), strokeWidth = 1.2f * u)
    for (y in listOf(10f, 18f)) drawCircle(BEZEL, 1.6f * u, p(cx, y))
    // The strap fades out too.
    drawRect(Brush.verticalGradient(listOf(background, Color.Transparent), startY = 0f, endY = 12 * u), p(cx - 17f, 0f), Size(34 * u, 12 * u))
    drawRect(Brush.verticalGradient(listOf(Color.Transparent, background), startY = 88 * u, endY = 100 * u), p(cx - 17f, 88f), Size(34 * u, 12 * u))

    // Keys on the right side at 2 and 4 o'clock (top one lit while touched).
    val r = 27f
    for ((deg, lit) in listOf(-32f to true, 32f to false)) {
        val a = deg * PI.toFloat() / 180f
        rotate(deg, pivot = p(cx, cy)) {
            drawRoundRect(
                if (lit) accent else STEEL_DARK,
                topLeft = p(cx + r - 2f, cy - 4.5f),
                size = Size(7 * u, 9 * u),
                cornerRadius = CornerRadius(2.5f * u),
            )
        }
        if (lit && onKey) {
            val key = p(cx + (r + 3f) * cos(a), cy + (r + 3f) * sin(a))
            drawCircle(accent.copy(alpha = 0.6f * (1 - pulse)), radius = (7f + 13f * pulse) * u, center = key, style = Stroke(2f * u))
        }
    }

    // Case, bezel with minute ticks, screen with a small ECG trace.
    drawCircle(Brush.linearGradient(listOf(Color(0xFFCDD2D9), STEEL_DARK), p(cx - r, cy - r), p(cx + r, cy + r)), r * u, p(cx, cy))
    drawCircle(BEZEL, (r - 3f) * u, p(cx, cy))
    for (i in 0 until 12) {
        val a = i * PI.toFloat() / 6
        drawLine(
            STEEL,
            p(cx + (r - 4.5f) * cos(a), cy + (r - 4.5f) * sin(a)),
            p(cx + (r - 6.5f) * cos(a), cy + (r - 6.5f) * sin(a)),
            strokeWidth = 1f * u,
        )
    }
    drawCircle(Color.Black, (r - 8f) * u, p(cx, cy))
    val trace = Path().apply {
        val pts = listOf(-12f to 0f, -6f to 0f, -4f to -2f, -2f to 0f, 0f to 0f, 1.5f to -9f, 3f to 5f, 4.5f to 0f, 8f to 0f, 10f to -3f, 12f to 0f)
        pts.forEachIndexed { i, (x, y) -> if (i == 0) moveTo((cx + x) * u, (cy + y) * u) else lineTo((cx + x) * u, (cy + y) * u) }
    }
    drawPath(trace, accent, style = Stroke(1.6f * u, cap = StrokeCap.Round))

    // Index finger of the other hand, from the upper right, tip on the top key.
    val tipAngle = -32f * PI.toFloat() / 180f
    val tip = Offset(cx + (r + 9f) * cos(tipAngle), cy + (r + 9f) * sin(tipAngle))
    val axis = -28f // finger direction from the tip towards the hand, degrees
    val away = 10f * lift
    val ax = cos(axis * PI.toFloat() / 180f)
    val ay = sin(axis * PI.toFloat() / 180f)
    val base = Offset(tip.x + ax * away, tip.y + ay * away)
    rotate(axis, pivot = p(base.x, base.y)) {
        val w = 13f
        // Shadow under the finger.
        drawRoundRect(Color.Black.copy(alpha = 0.18f), p(base.x - 1f, base.y - w / 2 + 3f), Size(90 * u, w * u), CornerRadius(w / 2 * u))
        drawRoundRect(
            Brush.verticalGradient(listOf(SKIN_LIGHT, SKIN, SKIN_SHADE), startY = (base.y - w / 2) * u, endY = (base.y + w / 2) * u),
            p(base.x - 1f, base.y - w / 2),
            Size(90 * u, w * u),
            CornerRadius(w / 2 * u),
        )
        // Nail near the tip, and the two knuckle creases.
        drawRoundRect(NAIL, p(base.x + 1.5f, base.y - w / 2 + 2f), Size(8 * u, (w - 4f) * u), CornerRadius(3.5f * u))
        for (d in listOf(22f, 40f)) {
            drawLine(SKIN_SHADE, p(base.x + d, base.y - w / 2 + 3f), p(base.x + d, base.y + w / 2 - 3f), strokeWidth = 1.1f * u, cap = StrokeCap.Round)
        }
        drawRoundRect(SKIN_SHADE.copy(alpha = 0.6f), p(base.x - 1f, base.y - w / 2), Size(90 * u, w * u), CornerRadius(w / 2 * u), style = Stroke(0.8f * u))
    }
}
