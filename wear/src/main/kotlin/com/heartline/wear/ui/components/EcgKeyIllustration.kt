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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
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

private val SKIN = Color(0xFFD9A585)
private val SKIN_SHADE = Color(0xFFBF8A6A)
private val SKIN_LIGHT = Color(0xFFEBC3A6)
private val NAIL = Color(0xFFF4DCCD)
private val NAIL_EDGE = Color(0xFFE2B9A2)
private val STRAP = Color(0xFF2E333B)
private val STRAP_LIGHT = Color(0xFF3D434D)
private val STEEL_LIGHT = Color(0xFFE4E8ED)
private val STEEL = Color(0xFFAEB4BD)
private val STEEL_DARK = Color(0xFF6C727B)
private val BEZEL = Color(0xFF17191D) // strap holes
private val TICK = Color(0xFF9AA0A9)

private fun DrawScope.scene(u: Float, accent: Color, background: Color, lift: Float, onKey: Boolean, pulse: Float) {
    fun p(x: Float, y: Float) = Offset(x * u, y * u)
    val cx = 56f
    val cy = 54f
    val r = 30f

    // Forearm across the scene, softly lit from above; it fades out at both sides.
    drawRect(Brush.verticalGradient(listOf(SKIN_LIGHT, SKIN, SKIN_SHADE), startY = 35 * u, endY = 75 * u), p(0f, 35f), Size(140 * u, 40 * u))
    drawRect(Brush.horizontalGradient(listOf(background, Color.Transparent), startX = 0f, endX = 30 * u), p(0f, 34f), Size(30 * u, 42 * u))
    drawRect(Brush.horizontalGradient(listOf(Color.Transparent, background), startX = 110 * u, endX = 140 * u), p(110f, 34f), Size(30 * u, 42 * u))

    // Strap with a lighter centre band and two holes; it fades out at the top and bottom.
    drawRoundRect(STRAP, p(cx - 17f, 0f), Size(34 * u, 100 * u), CornerRadius(4 * u))
    drawRoundRect(STRAP_LIGHT, p(cx - 11f, 0f), Size(22 * u, 100 * u), CornerRadius(4 * u))
    for (y in listOf(9f, 17f)) drawCircle(BEZEL, 1.5f * u, p(cx, y))
    drawRect(Brush.verticalGradient(listOf(background, Color.Transparent), startY = 0f, endY = 14 * u), p(cx - 18f, 0f), Size(36 * u, 14 * u))
    drawRect(Brush.verticalGradient(listOf(Color.Transparent, background), startY = 86 * u, endY = 100 * u), p(cx - 18f, 86f), Size(36 * u, 14 * u))

    // Soft shadow of the case on the wrist.
    drawCircle(Color.Black.copy(alpha = 0.22f), (r + 1.5f) * u, p(cx + 1.5f, cy + 2f))

    // Side keys at 2 and 4 o'clock: steel, the top one lit (with a contact pulse) while touched.
    val keyDeg = -32f
    val keyRad = keyDeg * PI.toFloat() / 180f
    val key = p(cx + (r + 3f) * cos(keyRad), cy + (r + 3f) * sin(keyRad))
    if (onKey) {
        drawCircle(
            Brush.radialGradient(listOf(accent.copy(alpha = 0.45f), Color.Transparent), center = key, radius = 14f * u),
            radius = 14f * u,
            center = key,
        )
        drawCircle(accent.copy(alpha = 0.7f * (1 - pulse)), radius = (8f + 12f * pulse) * u, center = key, style = Stroke(1.6f * u))
    }
    for ((deg, lit) in listOf(keyDeg to true, -keyDeg to false)) {
        rotate(deg, pivot = p(cx, cy)) {
            val top = p(cx + r - 2f, cy - 5f)
            val keySize = Size(7 * u, 10 * u)
            drawRoundRect(
                if (lit) {
                    Brush.horizontalGradient(listOf(accent, accent.copy(alpha = 0.75f)), startX = top.x, endX = top.x + keySize.width)
                } else {
                    Brush.horizontalGradient(listOf(STEEL, STEEL_DARK), startX = top.x, endX = top.x + keySize.width)
                },
                top,
                keySize,
                CornerRadius(2.5f * u),
            )
            drawLine(Color.White.copy(alpha = 0.35f), p(cx + r + 3.5f, cy - 3.5f), p(cx + r + 3.5f, cy + 3.5f), strokeWidth = 0.8f * u)
        }
    }

    // Case: brushed steel with a bright rim, dark bezel with minute ticks (longer at 12/3/6/9).
    drawCircle(Brush.linearGradient(listOf(STEEL_LIGHT, STEEL, STEEL_DARK), p(cx - r, cy - r), p(cx + r, cy + r)), r * u, p(cx, cy))
    drawCircle(Color.White.copy(alpha = 0.5f), (r - 0.6f) * u, p(cx, cy), style = Stroke(0.8f * u))
    // One black face (no separate bezel ring), minute ticks around its edge.
    val face = r - 3.2f
    drawCircle(Brush.radialGradient(listOf(Color(0xFF1A1D22), Color.Black), p(cx - 5f, cy - 7f), face * 1.2f * u), face * u, p(cx, cy))
    for (i in 0 until 12) {
        val a = i * PI.toFloat() / 6
        val long = i % 3 == 0
        drawLine(
            if (long) STEEL_LIGHT else TICK.copy(alpha = 0.7f),
            p(cx + (face - 2f) * cos(a), cy + (face - 2f) * sin(a)),
            p(cx + (face - if (long) 5f else 3.8f) * cos(a), cy + (face - if (long) 5f else 3.8f) * sin(a)),
            strokeWidth = (if (long) 1.3f else 0.8f) * u,
            cap = StrokeCap.Round,
        )
    }
    val screen = face - 2f
    val trace = Path().apply {
        val pts = listOf(-14f to 0f, -7f to 0f, -5f to -2.5f, -3f to 0f, -1f to 0f, 1f to -11f, 3f to 6f, 5f to 0f, 9f to 0f, 11.5f to -3.5f, 14f to 0f)
        pts.forEachIndexed { i, (x, y) -> if (i == 0) moveTo((cx + x) * u, (cy + 2f + y) * u) else lineTo((cx + x) * u, (cy + 2f + y) * u) }
    }
    drawPath(trace, accent.copy(alpha = 0.3f), style = Stroke(4f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(trace, accent, style = Stroke(1.7f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawArc(
        Color.White.copy(alpha = 0.10f),
        startAngle = 200f,
        sweepAngle = 70f,
        useCenter = false,
        topLeft = p(cx - screen + 2f, cy - screen + 2f),
        size = Size((screen - 2f) * 2 * u, (screen - 2f) * 2 * u),
        style = Stroke(2.2f * u, cap = StrokeCap.Round),
    )

    // Index finger of the other hand, from the upper right: tapered, with a nail, resting on the key.
    val axis = -28f // from the fingertip towards the hand, degrees
    val ax = cos(axis * PI.toFloat() / 180f)
    val ay = sin(axis * PI.toFloat() / 180f)
    val gap = 7.5f + 10f * lift
    val tip = Offset(key.x / u + ax * gap, key.y / u + ay * gap)
    rotate(axis, pivot = p(tip.x, tip.y)) {
        val w0 = 11.5f // at the tip
        val w1 = 15f // towards the hand
        val len = 95f
        fun finger(dx: Float, dy: Float) = Path().apply {
            moveTo((tip.x + dx + w0 / 2) * u, (tip.y + dy - w0 / 2) * u)
            lineTo((tip.x + dx + len) * u, (tip.y + dy - w1 / 2) * u)
            lineTo((tip.x + dx + len) * u, (tip.y + dy + w1 / 2) * u)
            lineTo((tip.x + dx + w0 / 2) * u, (tip.y + dy + w0 / 2) * u)
            arcTo(
                Rect(Offset((tip.x + dx) * u, (tip.y + dy - w0 / 2) * u), Size(w0 * u, w0 * u)),
                startAngleDegrees = 90f,
                sweepAngleDegrees = 180f,
                forceMoveTo = false,
            )
            close()
        }
        drawPath(finger(1.2f, 2.4f), Color.Black.copy(alpha = 0.2f))
        drawPath(finger(0f, 0f), Brush.verticalGradient(listOf(SKIN_LIGHT, SKIN, SKIN_SHADE), startY = (tip.y - w1 / 2) * u, endY = (tip.y + w1 / 2) * u))
        drawPath(finger(0f, 0f), SKIN_SHADE.copy(alpha = 0.5f), style = Stroke(0.7f * u))
        // Nail: rounded towards the tip, a soft cuticle line and a small highlight.
        drawRoundRect(NAIL, p(tip.x + 1.6f, tip.y - 3.6f), Size(8.5f * u, 7.2f * u), CornerRadius(3.6f * u))
        drawRoundRect(NAIL_EDGE, p(tip.x + 1.6f, tip.y - 3.6f), Size(8.5f * u, 7.2f * u), CornerRadius(3.6f * u), style = Stroke(0.6f * u))
        drawLine(Color.White.copy(alpha = 0.6f), p(tip.x + 3.5f, tip.y - 2f), p(tip.x + 7f, tip.y - 2f), strokeWidth = 0.9f * u, cap = StrokeCap.Round)
        // One faint joint line, not harsh creases.
        drawLine(SKIN_SHADE.copy(alpha = 0.45f), p(tip.x + 24f, tip.y - 3f), p(tip.x + 24f, tip.y + 3f), strokeWidth = 0.8f * u, cap = StrokeCap.Round)
    }
}
