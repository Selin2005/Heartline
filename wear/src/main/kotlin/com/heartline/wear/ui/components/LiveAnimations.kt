package com.heartline.wear.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Icon

/**
 * Measuring animations. Where a live heart rate is known, the motion beats at that rate, so the
 * animation itself shows live data; [animate] = false freezes a representative frame (screenshots).
 */
private fun beatMs(bpm: Int?) = (60_000 / (bpm ?: 70).coerceIn(35, 200))

/** A heart that contracts on every beat (quick squeeze, slower release). */
@Composable
fun BeatingHeart(bpm: Int?, color: Color, size: Dp = 30.dp, animate: Boolean = true, modifier: Modifier = Modifier) {
    val period = beatMs(bpm)
    val scale = if (animate && bpm != null) {
        val t = rememberInfiniteTransition(label = "heart")
        val v by t.animateFloat(
            initialValue = 1f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                keyframes {
                    durationMillis = period
                    1f at 0
                    1.22f at (period * 0.12f).toInt() using FastOutSlowInEasing
                    0.96f at (period * 0.3f).toInt()
                    1f at (period * 0.5f).toInt()
                },
            ),
            label = "beat",
        )
        v
    } else {
        1f
    }
    Icon(Icons.Rounded.Favorite, contentDescription = null, tint = color, modifier = modifier.size(size).scale(scale))
}

/** Rings that leave the centre on every beat, like a pulse travelling out (SpO2, pulse). */
@Composable
fun PulseRipple(bpm: Int?, color: Color, modifier: Modifier = Modifier, animate: Boolean = true, content: @Composable () -> Unit = {}) {
    val phase = if (animate) {
        val t = rememberInfiniteTransition(label = "ripple")
        val v by t.animateFloat(0f, 1f, infiniteRepeatable(tween(beatMs(bpm), easing = LinearEasing)), label = "phase")
        v
    } else {
        0.4f
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2
            for (k in 0..2) {
                val p = (phase + k / 3f) % 1f
                drawCircle(color.copy(alpha = (1f - p) * 0.5f), radius = r * (0.35f + 0.65f * p), style = Stroke(2.dp.toPx()))
            }
            drawCircle(color.copy(alpha = 0.16f), radius = r * 0.36f)
        }
        content()
    }
}

/** A circle that grows while breathing in (4 s) and shrinks while breathing out (6 s). */
@Composable
fun BreathingCircle(color: Color, modifier: Modifier = Modifier, animate: Boolean = true, content: @Composable () -> Unit = {}) {
    val grow = if (animate) {
        val t = rememberInfiniteTransition(label = "breath")
        val v by t.animateFloat(
            0f,
            0f,
            infiniteRepeatable(
                keyframes {
                    durationMillis = 10_000
                    0f at 0
                    1f at 4_000 using FastOutSlowInEasing
                    0f at 10_000 using FastOutSlowInEasing
                },
            ),
            label = "breath",
        )
        v
    } else {
        0.6f
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2
            drawCircle(color.copy(alpha = 0.12f), radius = r)
            drawCircle(color.copy(alpha = 0.30f), radius = r * (0.45f + 0.5f * grow))
            drawCircle(color.copy(alpha = 0.9f), radius = r * (0.45f + 0.5f * grow), style = Stroke(2.dp.toPx()))
        }
        content()
    }
}

/** Thermometer whose column rises while the reading settles. */
@Composable
fun ThermometerFill(color: Color, modifier: Modifier = Modifier, animate: Boolean = true) {
    val level = if (animate) {
        val t = rememberInfiniteTransition(label = "thermo")
        val v by t.animateFloat(0.2f, 0.85f, infiniteRepeatable(tween(2_400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "level")
        v
    } else {
        0.6f
    }
    Canvas(modifier) {
        val w = size.width * 0.28f
        val x = (size.width - w) / 2
        val bulb = w * 1.1f
        val tubeTop = 2f
        val tubeBottom = size.height - bulb * 1.6f
        drawRoundRect(color.copy(alpha = 0.25f), Offset(x, tubeTop), Size(w, tubeBottom - tubeTop + bulb), CornerRadius(w / 2))
        val fillTop = tubeBottom - (tubeBottom - tubeTop) * level
        drawRoundRect(color, Offset(x + w * 0.22f, fillTop), Size(w * 0.56f, tubeBottom - fillTop + bulb), CornerRadius(w / 3))
        drawCircle(color, radius = bulb, center = Offset(size.width / 2, size.height - bulb))
    }
}

/** The watch face with its two side keys glowing in turn (body composition needs both keys held). */
@Composable
fun KeysContact(color: Color, modifier: Modifier = Modifier, animate: Boolean = true, content: @Composable () -> Unit = {}) {
    val phase = if (animate) {
        val t = rememberInfiniteTransition(label = "keys")
        val v by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1_400, easing = LinearEasing)), label = "phase")
        v
    } else {
        0.25f
    }
    Box(modifier) {
    Canvas(Modifier.fillMaxSize()) {
        // The watch seen from the front: its face, and the two side keys the fingers rest on.
        val w = size.width
        val h = size.height
        val face = Offset(w * 0.40f, h * 0.5f)
        val radius = w * 0.32f
        drawCircle(color.copy(alpha = 0.14f), radius = radius, center = face)
        drawCircle(color.copy(alpha = 0.55f), radius = radius, center = face, style = Stroke(2.dp.toPx()))
        val keyW = w * 0.11f
        val keyH = h * 0.17f
        val keyX = face.x + radius + w * 0.05f
        listOf(face.y - h * 0.2f, face.y + h * 0.2f).forEachIndexed { i, y ->
            // Contact glow: the two keys pulse in turn while the current flows.
            val glow = ((phase + i * 0.5f) % 1f).let { if (it < 0.5f) it * 2 else (1 - it) * 2 }
            val centre = Offset(keyX + keyW / 2, y)
            drawCircle(color.copy(alpha = 0.10f + 0.30f * glow), radius = keyH * (0.75f + 0.35f * glow), center = centre)
            drawRoundRect(color, Offset(keyX, y - keyH / 2), Size(keyW, keyH), CornerRadius(keyW / 2))
        }
    }
        // The face is centred at 40 % of the width; [content] (the metric icon) sits in it.
        Box(Modifier.fillMaxHeight().fillMaxWidth(0.8f).align(Alignment.CenterStart), contentAlignment = Alignment.Center) { content() }
    }
}
