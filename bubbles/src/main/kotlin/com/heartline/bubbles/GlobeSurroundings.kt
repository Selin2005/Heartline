// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.bubbles

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * What surrounds the particle globe where there is room for it (the phone's screen): two tilted
 * orbits of points circling it in 3D that light up with the progress, a field of dust drifting
 * across the whole area that streams into the globe while it forms and is blown out by the
 * result, and ripples spreading from the globe with every heartbeat.
 *
 * Pure and deterministic like [ParticleGlobe], in globe radii from its centre (y down), and
 * written into arrays it owns so a frame allocates nothing.
 */
class GlobeSurroundings(val style: BubbleStyle, seed: Int = 13) {
    /** Orbit points: position, size, opacity, lit (front or back is in [orbitFront]). */
    val orbitX = FloatArray(ORBIT_POINTS)
    val orbitY = FloatArray(ORBIT_POINTS)
    val orbitSize = FloatArray(ORBIT_POINTS)
    val orbitAlpha = FloatArray(ORBIT_POINTS)
    val orbitLit = BooleanArray(ORBIT_POINTS)

    /** True for orbit points in front of the globe (drawn after it). */
    val orbitFront = BooleanArray(ORBIT_POINTS)

    /** Dust: position, size, opacity, and how far it has taken the metric's colour (0…1). */
    val dustX = FloatArray(DUST)
    val dustY = FloatArray(DUST)
    val dustSize = FloatArray(DUST)
    val dustAlpha = FloatArray(DUST)
    val dustTint = FloatArray(DUST)

    /** Heartbeat ripples: radius and opacity (0 when none). */
    val rippleRadius = FloatArray(RIPPLES)
    val rippleAlpha = FloatArray(RIPPLES)

    private val dustHome = Array(DUST) { FloatArray(4) }

    init {
        val random = Random(seed + style.ordinal * 17)
        for (d in dustHome) {
            // Angle, distance from the centre (outside the globe), drift speed, phase.
            d[0] = random.nextFloat() * TAU
            d[1] = 1.25f + random.nextFloat() * (EXTENT - 1.25f)
            d[2] = 0.04f + random.nextFloat() * 0.1f
            d[3] = random.nextFloat()
        }
    }

    /** Computes the surroundings at the same moment as the globe's [ParticleGlobe.frame]. */
    fun frame(phase: BubblePhase, timeMs: Long, phaseMs: Long, progress: Float, bpm: Int?, globeScale: Float) {
        val t = timeMs / 1000f
        val p = phaseMs / 1000f
        val prog = progress.coerceIn(0f, 1f)
        val form = if (phase == BubblePhase.FORMING) easeOut((p / 1.6f).coerceAtMost(1f)) else 1f
        val done = phase == BubblePhase.SUCCESS
        val failed = phase == BubblePhase.FAILED
        val failK = if (failed) easeOut((p / 0.8f).coerceAtMost(1f)) else 0f
        val burst = if (done) (p / 1.4f).coerceIn(0f, 1f) else 0f
        val lit = when {
            done || phase == BubblePhase.IDLE -> 1f
            failed -> 0f
            else -> prog
        }

        // Two orbits, tilted differently and turning in opposite directions.
        for (ring in 0 until 2) {
            val radius = (if (ring == 0) 1.42f else 1.72f) * (0.6f + 0.4f * form) * (1f + 0.08f * burst)
            val tiltX = if (ring == 0) 0.42f else 2.55f
            val tiltZ = if (ring == 0) 0.38f else -0.55f
            val spin = (if (ring == 0) 0.32f else -0.22f) * (if (phase == BubblePhase.HINT) 0.3f else 1f) * (1f - 0.8f * failK)
            val cx = cos(tiltX)
            val sx = sin(tiltX)
            val cz = cos(tiltZ)
            val sz = sin(tiltZ)
            for (k in 0 until PER_ORBIT) {
                val i = ring * PER_ORBIT + k
                val share = k / PER_ORBIT.toFloat()
                val a = share * TAU + t * spin
                // A circle in the xz plane, tilted about x, then turned about z.
                var x = cos(a) * radius
                var y = 0f
                var z = sin(a) * radius
                val y1 = y * cx - z * sx
                val z1 = y * sx + z * cx
                y = y1
                z = z1
                val x2 = x * cz - y * sz
                val y2 = x * sz + y * cz
                x = x2
                y = y2
                val f = 1f / (1f + z * 0.18f)
                val depth = (1f - z / radius) * 0.5f
                orbitX[i] = x * f
                orbitY[i] = y * f
                orbitFront[i] = z < 0f
                // The orbit lights up as an arc from its start, with the progress.
                orbitLit[i] = share < lit
                orbitSize[i] = (0.012f + 0.016f * depth) * (if (orbitLit[i]) 1.3f else 1f)
                orbitAlpha[i] = (if (orbitLit[i]) 0.35f + 0.6f * depth else 0.08f + 0.18f * depth) * form * (1f - 0.6f * failK)
            }
        }

        // Dust drifting slowly round and in; it streams into the globe while forming, is blown
        // out by a result, and settles when a measurement fails.
        for (j in 0 until DUST) {
            val d = dustHome[j]
            var r = d[1]
            var a = d[0] + t * d[2]
            if (phase == BubblePhase.FORMING) {
                // Pulled in, again and again, as the globe gathers its points.
                val k = ((p * 0.9f + d[3]) % 1f)
                r = 1.05f + (r - 1.05f) * (1f - easeIn(k))
                a += k * 1.5f
            }
            if (done) r += burst * (1.2f + d[3] * 1.5f)
            val bob = sin(t * (0.6f + d[3]) + d[3] * 9f) * 0.05f
            dustX[j] = cos(a) * r
            dustY[j] = sin(a) * r * 0.86f + bob - (if (failed) 0.25f * failK * d[3] else 0f)
            dustSize[j] = 0.008f + 0.016f * d[3]
            val twinkle = 0.55f + 0.45f * sin(t * (1.3f + 2f * d[3]) + d[3] * 20f)
            val fadeEdge = (1f - ((r - 1.1f) / (EXTENT + 1.5f - 1.1f)).coerceIn(0f, 1f))
            dustAlpha[j] = (0.12f + 0.35f * twinkle) * fadeEdge * (if (done) 1f - burst * 0.9f else 1f) * (1f - 0.5f * failK)
            // Near the globe the dust takes its colour as the progress grows.
            dustTint[j] = (lit * (1f - ((r - 1.1f) / 1.2f).coerceIn(0f, 1f))).coerceIn(0f, 1f)
        }

        // Heartbeat ripples (heart, blood pressure, ECG): one per beat, spreading to the edge.
        val beats = style == BubbleStyle.HEART || style == BubbleStyle.BLOOD_PRESSURE || style == BubbleStyle.ECG
        val moving = phase == BubblePhase.MEASURING || phase == BubblePhase.IDLE || phase == BubblePhase.HINT
        val periodMs = 60_000f / (bpm ?: 72).coerceIn(35, 200)
        for (k in 0 until RIPPLES) {
            if (!beats || !moving) {
                rippleAlpha[k] = 0f
                continue
            }
            val age = ((timeMs % periodMs.toLong()) + k * periodMs) / RIPPLE_MS
            if (age >= 1f) {
                rippleAlpha[k] = 0f
                continue
            }
            rippleRadius[k] = globeScale * (1.05f + (EXTENT - 1.05f) * easeOut(age))
            rippleAlpha[k] = 0.3f * (1f - age) * (1f - age) * (if (phase == BubblePhase.HINT) 0.4f else 1f)
        }
        // Calm, slow breaths for stress instead.
        if (style == BubbleStyle.STRESS && moving) {
            val breath = (t % 10f) / 10f
            rippleRadius[0] = globeScale * (1.1f + 0.9f * easeOut(breath))
            rippleAlpha[0] = 0.18f * sin(breath * PI.toFloat())
        }
    }

    companion object {
        /** How far the surroundings reach, in globe radii. */
        const val EXTENT = 2.7f
        private const val PER_ORBIT = 96
        const val ORBIT_POINTS = PER_ORBIT * 2
        const val DUST = 140
        const val RIPPLES = 3
        private const val RIPPLE_MS = 2_400f
        private const val TAU = (2 * PI).toFloat()

        private fun easeOut(x: Float) = 1f - (1f - x) * (1f - x) * (1f - x)
        private fun easeIn(x: Float) = x * x * x
    }
}
