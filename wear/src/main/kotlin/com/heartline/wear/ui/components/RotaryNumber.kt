// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.withSign

/**
 * Turns rotary input into whole steps. A Galaxy rotating bezel sends one large event per click:
 * that is exactly one step (a burst of fast clicks moves [fastSteps] at a time). A smooth crown
 * sends many small events: they add up, one step per [smoothPx].
 */
class RotaryStepper(
    private val detentPx: Float = 20f,
    private val smoothPx: Float = 36f,
    private val fastMs: Long = 90,
    private val fastSteps: Int = 5,
) {
    private var pending = 0f
    private var lastAt = Long.MIN_VALUE / 2
    private var lastSign = 0f

    /** @return signed number of steps for an event of [px] at [atMs] (positive = clockwise = up). */
    fun onEvent(px: Float, atMs: Long): Int {
        if (px == 0f) return 0
        val sign = 1f.withSign(px)
        val fast = atMs - lastAt < fastMs && sign == lastSign
        lastAt = atMs
        lastSign = sign
        if (abs(px) >= detentPx) {
            pending = 0f
            return (if (fast) fastSteps else 1) * sign.toInt()
        }
        if (pending != 0f && 1f.withSign(pending) != sign) pending = 0f
        pending += px
        val steps = (pending / smoothPx).toInt()
        pending -= steps * smoothPx
        return steps
    }
}

/**
 * A number set with the bezel (or crown): each click moves one [step] with a haptic tick, the
 * digits roll in the direction of the change, and a ring of ticks along the screen edge turns
 * with the value like the bezel itself. [content] is laid over it (title, value, buttons).
 */
@Composable
fun RotaryNumberInput(
    value: Float,
    onValueChange: (Float) -> Unit,
    step: Float,
    range: ClosedFloatingPointRange<Float>,
    accent: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val focus = remember { FocusRequester() }
    val stepper = remember { RotaryStepper() }
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Box(
        modifier
            .fillMaxSize()
            .onRotaryScrollEvent { e ->
                val steps = stepper.onEvent(e.verticalScrollPixels, e.uptimeMillis)
                if (steps != 0) {
                    val next = snap(value + steps * step, step).coerceIn(range.start, range.endInclusive)
                    if (next != value) {
                        onValueChange(next)
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                    }
                }
                true
            }
            .focusRequester(focus)
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        // Bezel ring: ticks every 0.1 of a unit's worth of steps, turning with the value.
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2
            val c = Offset(size.width / 2, size.height / 2)
            val stepsPerTurn = 60
            val turn = (value / step) % stepsPerTurn
            for (i in 0 until stepsPerTurn) {
                val a = (2 * PI * (i - turn) / stepsPerTurn - PI / 2).toFloat()
                val major = i % 10 == 0
                val len = (if (major) 9 else 5).dp.toPx()
                val outer = r - 3.dp.toPx()
                // Ticks fade away from the top, where the marker is.
                val dist = abs(((i - turn + stepsPerTurn / 2) % stepsPerTurn + stepsPerTurn) % stepsPerTurn - stepsPerTurn / 2) / (stepsPerTurn / 2f)
                drawLine(
                    Color.White.copy(alpha = (0.55f * (1 - dist)).coerceAtLeast(0.08f)),
                    Offset(c.x + outer * cos(a), c.y + outer * sin(a)),
                    Offset(c.x + (outer - len) * cos(a), c.y + (outer - len) * sin(a)),
                    strokeWidth = (if (major) 2f else 1.2f).dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
            // Fixed marker at the top.
            val top = -PI.toFloat() / 2
            drawLine(
                accent,
                Offset(c.x + (r - 1.dp.toPx()) * cos(top), c.y + (r - 1.dp.toPx()) * sin(top)),
                Offset(c.x + (r - 15.dp.toPx()) * cos(top), c.y + (r - 15.dp.toPx()) * sin(top)),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
        content()
    }
}

/** The value text, rolling up or down when it changes. */
@Composable
fun RollingNumber(value: Float, format: (Float) -> String, style: TextStyle, modifier: Modifier = Modifier) {
    AnimatedContent(
        targetState = value,
        transitionSpec = {
            val up = targetState > initialState
            (slideInVertically(tween(160)) { h -> if (up) h / 2 else -h / 2 } + fadeIn(tween(160))) togetherWith
                (slideOutVertically(tween(160)) { h -> if (up) -h / 2 else h / 2 } + fadeOut(tween(120)))
        },
        modifier = modifier,
        label = "rolling",
    ) { v -> androidx.wear.compose.material3.Text(format(v), style = style) }
}

private fun snap(v: Float, step: Float): Float = (Math.round(v / step) * step).let { (it * 1000).toInt() / 1000f }
