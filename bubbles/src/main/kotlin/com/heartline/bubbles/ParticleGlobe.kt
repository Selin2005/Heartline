// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.bubbles

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The watch's measuring animation: a globe of points turning in 3D. The points light up from the
 * bottom as the measurement progresses and the globe beats with the pulse; while waiting they
 * gather from all around, a hint makes them tremble in amber, and a result makes the globe swell
 * once and settle, fully lit.
 *
 * A pure function of time, like [BubbleScene]: the same inputs give the same frame (screenshots,
 * tests). The frame is written into arrays this object owns, so drawing 30 frames a second
 * allocates nothing; read them before asking for the next frame.
 *
 * Coordinates are in globe radii from the centre, y down.
 */
class ParticleGlobe(val style: BubbleStyle, val count: Int = DEFAULT_COUNT, seed: Int = 7) {
    private val px = FloatArray(count)
    private val py = FloatArray(count)
    private val pz = FloatArray(count)
    private val noise = FloatArray(count)

    /** Screen position (globe radii), dot radius (globe radii), opacity and lightness (0…1 towards white). */
    val x = FloatArray(count)
    val y = FloatArray(count)
    val size = FloatArray(count)
    val alpha = FloatArray(count)
    val light = FloatArray(count)

    /** Lit by the progress (in the metric's colour) or not (faint white). */
    val lit = BooleanArray(count)

    /** Body composition: 0 water, 1 muscle, 2 fat, by height. */
    val layer = IntArray(count)

    /** Indices from the back to the front: draw in this order. */
    val order = IntArray(count)
    private val depthKey = FloatArray(count)

    /** How far the colour has turned amber (hint) or grey (failed), 0…1. */
    var amber = 0f
        private set
    var grey = 0f
        private set

    /** Skin temperature: how warm the lit points are, 0 (cool) … 1. */
    var warmth = 0f
        private set

    /** The glowing ring at the fill line: its height (y), half width and half height, and opacity. */
    var fillY = 0f
        private set
    var fillRx = 0f
        private set
    var fillRy = 0f
        private set
    var fillAlpha = 0f
        private set

    /** The globe's scale this frame (the pulse, the swell). */
    var scale = 1f
        private set

    init {
        val random = Random(seed + style.ordinal * 31)
        for (i in 0 until count) {
            // A Fibonacci lattice: evenly spread points, from the top (y = -1) to the bottom.
            val yy = -1f + 2f * (i + 0.5f) / count
            val r = sqrt(1f - yy * yy)
            val a = i * GOLDEN_ANGLE
            px[i] = cos(a) * r
            py[i] = yy
            pz[i] = sin(a) * r
            noise[i] = random.nextFloat()
        }
    }

    /**
     * Computes the frame at [timeMs] (a running clock), [phaseMs] into [phase], with [progress]
     * 0…1 and the live pulse [bpm] when known.
     */
    fun frame(phase: BubblePhase, timeMs: Long, phaseMs: Long, progress: Float, bpm: Int? = null) {
        val t = timeMs / 1000f
        val p = phaseMs / 1000f
        val prog = progress.coerceIn(0f, 1f)
        val beatMs = 60_000f / (bpm ?: 72).coerceIn(35, 200)
        val beat = BubbleScene.beatScale((timeMs % beatMs.toLong()) / beatMs, 1f) - 1f

        var form = 1f
        var lift = 0f
        var jitter = 0f
        var spin = 0.55f
        var allLit = false
        var dim = 1f
        amber = 0f
        grey = 0f
        scale = 1f
        var level = prog
        when (phase) {
            BubblePhase.IDLE -> allLit = true
            BubblePhase.FORMING -> {
                form = easeOut((p / 1.6f).coerceAtMost(1f))
                spin = 0.9f
                level = 0f
            }
            BubblePhase.MEASURING -> Unit
            BubblePhase.HINT -> {
                jitter = 0.06f
                amber = (p / 0.35f).coerceAtMost(1f) * 0.6f
                spin = 0.25f
            }
            BubblePhase.SUCCESS -> {
                allLit = true
                level = 1f
                lift = sin((p / 0.9f).coerceAtMost(1f) * PI.toFloat())
                scale = 1f + 0.32f * lift
            }
            BubblePhase.FAILED -> {
                val k = easeOut((p / 0.8f).coerceAtMost(1f))
                grey = k
                dim = 1f - 0.45f * k
                spin = 0.55f - 0.4f * k
                scale = 1f - 0.08f * k
                level = prog * (1f - k)
            }
        }
        val moving = phase == BubblePhase.MEASURING || phase == BubblePhase.HINT || phase == BubblePhase.IDLE || phase == BubblePhase.FORMING
        if (moving) {
            scale *= when (style) {
                BubbleStyle.HEART, BubbleStyle.BLOOD_PRESSURE, BubbleStyle.ECG -> 1f + 0.05f * beat
                // Breathe: about 5 s in, 5 s out.
                BubbleStyle.STRESS -> 1f + 0.06f * sin(t * TAU / 10f)
                else -> 1f + 0.02f * beat
            }
        }
        warmth = if (style == BubbleStyle.TEMPERATURE) (if (allLit) 1f else level) else 0f

        val yaw = t * spin
        val cy = cos(yaw)
        val sy = sin(yaw)
        val cp = cos(PITCH)
        val sp = sin(PITCH)
        // Points at or below this height are lit (y down: the bottom fills first).
        val threshold = 1f - 2f * level
        for (i in 0 until count) {
            val n = noise[i]
            val scatter = (1f - form) * 1.8f * (0.5f + n) + jitter * sin(t * 30f + n * 9f)
            val k = 1f + scatter
            var ox = px[i] * k
            var oy = py[i] * k
            var oz = pz[i] * k
            // Turn about the vertical axis, then tilt towards the viewer.
            val rx = ox * cy + oz * sy
            val rz0 = -ox * sy + oz * cy
            val ry = oy * cp - rz0 * sp
            val rz = oy * sp + rz0 * cp
            ox = rx
            oy = ry
            oz = rz
            val f = 1f / (1f + oz * PERSPECTIVE)
            val depth = (1f - oz) * 0.5f
            val isLit = allLit || (level > 0f && py[i] >= threshold)
            x[i] = ox * scale * f
            y[i] = oy * scale * f
            lit[i] = isLit
            val wave = sin(py[i] * 8f - t * 3f) * 0.5f + 0.5f
            light[i] = if (isLit) (0.35f * wave * depth + if (style == BubbleStyle.SPO2) 0.15f * wave else 0f) else 0f
            alpha[i] = (if (isLit) 0.45f + 0.55f * depth else (0.1f + 0.25f * depth) * 0.6f) * form * dim
            size[i] = (0.013f + 0.02f * depth) * (if (isLit) 1.25f else 1f) * (0.85f + 0.3f * n)
            layer[i] = when {
                py[i] < 0.25f -> 2
                py[i] < 0.65f -> 1
                else -> 0
            }
            depthKey[i] = oz
        }
        sortByDepth()

        // The fill line glows as a ring around the globe where the light stops.
        val inFill = level > 0.01f && level < 0.99f && !allLit
        fillY = threshold * cp * scale
        val rr = sqrt((1f - threshold * threshold).coerceAtLeast(0f))
        fillRx = rr * scale
        fillRy = rr * scale * 0.3f
        fillAlpha = if (inFill) 0.5f * dim else 0f
    }

    /** Back (largest z) to front, by insertion into [order] (the order changes little between frames). */
    private fun sortByDepth() {
        if (order[0] == order[1] && count > 1) for (i in 0 until count) order[i] = i
        for (i in 1 until count) {
            val v = order[i]
            val key = depthKey[v]
            var j = i - 1
            while (j >= 0 && depthKey[order[j]] < key) {
                order[j + 1] = order[j]
                j--
            }
            order[j + 1] = v
        }
    }

    companion object {
        const val DEFAULT_COUNT = 560
        private const val GOLDEN_ANGLE = 2.39996323f
        private const val PITCH = -0.35f
        private const val PERSPECTIVE = 0.32f
        private const val TAU = (2 * PI).toFloat()

        private fun easeOut(x: Float) = 1f - (1f - x) * (1f - x) * (1f - x)
    }
}
