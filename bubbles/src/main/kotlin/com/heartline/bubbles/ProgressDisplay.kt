// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.bubbles

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.platform.LocalInspectionMode
import kotlin.math.exp

/**
 * The progress a measurement shows, as opposed to the one its sensor reports: it glides towards
 * the reported value instead of stepping, never goes back (unless the measurement really started
 * over: a drop of more than [restartDrop]), stands still while a hint is up, and stops at 99 %
 * until there is a result. Pure, so the watch and the phone show the same and tests can drive it.
 */
class ProgressDisplay(
    private val restartDrop: Float = 0.2f,
    private val timeConstantMs: Float = 280f,
) {
    var shown = 0f
        private set

    /** Going back after a restart, until the shown value has caught up with the new one. */
    private var falling = false

    /** The shown progress as a whole percentage. */
    val percent: Int get() = (shown * 100f + 1e-3f).toInt().coerceIn(0, 100)

    /**
     * One step of [dtMs]: towards [target] (0…1), held while [paused] (a hint), and up to 100 %
     * only when [done].
     */
    fun update(target: Float, paused: Boolean, done: Boolean, dtMs: Long): Float {
        val goal = if (done) 1f else target.coerceIn(0f, 1f).coerceAtMost(MAX_BEFORE_DONE)
        if (!done && goal < shown - restartDrop) falling = true
        if (done || goal >= shown - 0.01f) falling = false
        val restart = falling
        val next = when {
            restart -> goal
            paused && !done -> shown
            else -> maxOf(goal, shown)
        }
        val k = 1f - exp(-dtMs.coerceIn(0, 1_000) / timeConstantMs)
        shown += (next - shown) * k
        if (!restart) shown = shown.coerceAtLeast(0f)
        if (done && 1f - shown < 0.004f) shown = 1f
        return shown
    }

    /** Jumps straight to [value] (a still frame, or a new measurement). */
    fun reset(value: Float = 0f) {
        shown = value.coerceIn(0f, 1f)
    }

    companion object {
        const val MAX_BEFORE_DONE = 0.99f
    }
}

/**
 * The shown percentage for a measurement whose sensor reports [progress]: glides every frame (see
 * [ProgressDisplay]). [target] may also be a function of the time (the phone predicts between
 * the watch's messages). In previews and screenshots it is simply the progress.
 */
@Composable
fun rememberShownProgress(progress: Float, paused: Boolean, done: Boolean, target: ((Long) -> Float)? = null): Float {
    if (LocalInspectionMode.current) return if (done) 1f else progress.coerceIn(0f, ProgressDisplay.MAX_BEFORE_DONE)
    val display = remember { ProgressDisplay().apply { reset(progress.coerceIn(0f, 1f)) } }
    var shown by remember { mutableFloatStateOf(display.shown) }
    val latest = rememberUpdatedState(Triple(progress, paused, done))
    val predict = rememberUpdatedState(target)
    LaunchedEffect(display) {
        var last = withFrameMillis { it }
        while (true) {
            val now = withFrameMillis { it }
            val (p, hold, finished) = latest.value
            val goal = predict.value?.invoke(System.currentTimeMillis()) ?: p
            shown = display.update(goal, hold, finished, now - last)
            last = now
        }
    }
    return shown
}
