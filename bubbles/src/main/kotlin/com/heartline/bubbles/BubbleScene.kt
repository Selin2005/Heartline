// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.bubbles

import com.heartline.shared.model.Metric
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/** What the measurement is doing; each phase has its own motion. */
enum class BubblePhase {
    /** Nothing measured yet: the bubble floats. */
    IDLE,

    /** Waiting for the watch or the sensor: small bubbles gather and merge into the big one. */
    FORMING,

    /** Measuring: the bubble fills with the progress and moves in the metric's own way. */
    MEASURING,

    /** Measuring but paused by a hint (hold still, wrist contact…): a jelly wobble, tinted amber. */
    HINT,

    /** Done: the bubble swells, bursts into small bubbles and settles again, full. */
    SUCCESS,

    /** Failed or cancelled: the bubble drains, softens and turns grey. */
    FAILED,
}

/** The look of one metric. */
enum class BubbleStyle {
    HEART,
    SPO2,
    TEMPERATURE,
    STRESS,
    BLOOD_PRESSURE,
    ECG,
    BODY,
    ;

    companion object {
        fun of(metric: Metric): BubbleStyle = when (metric) {
            Metric.HEART_RATE -> HEART
            Metric.SPO2 -> SPO2
            Metric.SKIN_TEMPERATURE -> TEMPERATURE
            Metric.STRESS -> STRESS
            Metric.BLOOD_PRESSURE -> BLOOD_PRESSURE
            Metric.ECG -> ECG
            Metric.BODY_COMPOSITION -> BODY
        }
    }
}

/** A small sphere around or inside the bubble, in bubble radii from its centre (y down); [z] > 0 is in front. */
data class Orb(val x: Float, val y: Float, val z: Float, val radius: Float, val alpha: Float)

/** A ring sent out by the bubble (heartbeat), in bubble radii. */
data class Ring(val radius: Float, val alpha: Float)

/**
 * One frame of the bubble. Distances are in bubble radii, so the renderer only scales them.
 *
 * @property radii the outline: [CONTOUR_POINTS] radius multipliers, evenly spaced from angle 0
 * @property level how full the bubble is, 0…1 (the liquid surface height)
 * @property layers for [BubbleStyle.BODY], the tops of the stacked liquids (each 0…1, rising)
 */
data class BubbleFrame(
    val centerX: Float,
    val centerY: Float,
    val scale: Float,
    val alpha: Float,
    val radii: FloatArray,
    val level: Float,
    val wavePhase: Float,
    val waveHeight: Float,
    val layers: FloatArray,
    val behind: List<Orb>,
    val front: List<Orb>,
    val inside: List<Orb>,
    val sparks: List<Orb>,
    val rings: List<Ring>,
    val amber: Float,
    val grey: Float,
    val warmth: Float,
    val shimmer: Float,
    val trace: Float,
    val glow: Float,
    val progress: Float,
    val showProgress: Boolean,
) {
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

/**
 * The bubble animation as a pure function of time, so the same inputs always give the same frame
 * (screenshots, tests) and nothing depends on Android. [BubbleOrb] draws it.
 *
 * @param seed varies the small bubbles and the outline between screens
 */
class BubbleScene(val style: BubbleStyle, seed: Int = 11) {
    private val random = Random(seed + style.ordinal * 101)
    private val shapePhases = FloatArray(HARMONICS) { random.nextFloat() * TAU }
    private val shapeSpeeds = FloatArray(HARMONICS) { 0.35f + random.nextFloat() * 0.5f }
    private val satellites = List(SATELLITES) {
        Satellite(
            angle = it * TAU / SATELLITES + random.nextFloat() * 0.6f,
            orbit = 1.32f + random.nextFloat() * 0.3f,
            tilt = 0.28f + random.nextFloat() * 0.22f,
            speed = (0.10f + random.nextFloat() * 0.08f) * if (it % 3 == 0) -1 else 1,
            size = 0.07f + random.nextFloat() * 0.07f,
        )
    }
    private val fizz = List(FIZZ) { Fizz(x = random.nextFloat() * 1.5f - 0.75f, size = 0.025f + random.nextFloat() * 0.045f, speed = 0.18f + random.nextFloat() * 0.22f, offset = random.nextFloat()) }
    private val burst = List(BURST) { Spark(angle = it * TAU / BURST + random.nextFloat() * 0.3f, speed = 0.9f + random.nextFloat() * 0.9f, size = 0.05f + random.nextFloat() * 0.08f) }

    private data class Satellite(val angle: Float, val orbit: Float, val tilt: Float, val speed: Float, val size: Float)
    private data class Fizz(val x: Float, val size: Float, val speed: Float, val offset: Float)
    private data class Spark(val angle: Float, val speed: Float, val size: Float)

    /**
     * @param timeMs a running clock (it drives the floating and the shapes)
     * @param phaseMs time since [phase] began (it drives the one-off moves: forming, bursting…)
     * @param progress 0…1 of the measurement
     * @param bpm the live heart rate, when known; the heart and blood-pressure bubbles beat with it
     */
    fun frame(phase: BubblePhase, timeMs: Long, phaseMs: Long, progress: Float, bpm: Int? = null): BubbleFrame {
        val t = timeMs / 1000f
        val p = phaseMs / 1000f
        val prog = progress.coerceIn(0f, 1f)
        val beatMs = 60_000f / (bpm ?: 72).coerceIn(35, 200)
        val beat = ((timeMs % beatMs.toLong()) / beatMs)

        // Floating: a slow figure-of-eight bob.
        var cx = sin(t * 0.9f) * 0.025f
        var cy = sin(t * 1.3f) * 0.04f
        var scale = 1f
        var alpha = 1f
        var wobble = 0.018f
        var amber = 0f
        var grey = 0f
        var level = 0f
        var gather = 0f
        var orbitSpeed = 1f
        var squeeze = 0f
        var glow = 0.35f

        when (phase) {
            BubblePhase.IDLE -> {
                level = 0.12f
                orbitSpeed = 0.6f
            }
            BubblePhase.FORMING -> {
                val grow = easeOut((p / 0.9f).coerceAtMost(1f))
                scale = 0.55f + 0.45f * grow
                wobble = 0.05f - 0.03f * grow
                // The small bubbles spiral in again and again, as if the big one keeps taking them in.
                gather = ((p % 1.6f) / 1.6f)
                orbitSpeed = 2.2f
                level = 0.08f
                glow = 0.25f + 0.2f * sin(t * 4f).coerceAtLeast(0f)
            }
            BubblePhase.MEASURING -> {
                level = 0.1f + 0.85f * prog
            }
            BubblePhase.HINT -> {
                level = 0.1f + 0.85f * prog
                // A jelly wobble every 1.6 s that dies away.
                val k = (p % 1.6f)
                wobble = 0.02f + 0.09f * exp(-k * 3.2f) * abs(sin(k * 14f))
                cx += sin(p * 18f) * 0.03f * exp(-k * 3f)
                amber = (p / 0.35f).coerceAtMost(1f) * 0.45f
                orbitSpeed = 0.4f
            }
            BubblePhase.SUCCESS -> {
                level = 1f
                when {
                    p < 0.25f -> scale = 1f + 0.14f * easeOut(p / 0.25f)
                    p < 0.75f -> {
                        val k = (p - 0.25f) / 0.5f
                        scale = 1.14f + 0.3f * easeOut(k)
                        alpha = 1f - easeIn(k)
                    }
                    else -> {
                        val k = ((p - 0.75f) / 0.6f).coerceAtMost(1f)
                        scale = 0.6f + 0.3f * easeOut(k)
                        alpha = easeOut(k)
                    }
                }
                glow = 0.6f
                orbitSpeed = 0.5f
            }
            BubblePhase.FAILED -> {
                val k = easeOut((p / 0.7f).coerceAtMost(1f))
                level = 0.5f * (1f - k)
                scale = 1f - 0.12f * k
                grey = k
                wobble = 0.012f
                orbitSpeed = 0.3f
                cy += 0.04f * k
            }
        }

        // The metric's own motion.
        var warmth = 0f
        var shimmer = 0f
        var trace = 0f
        val rings = mutableListOf<Ring>()
        val moving = phase == BubblePhase.MEASURING || phase == BubblePhase.HINT || phase == BubblePhase.IDLE
        when (style) {
            BubbleStyle.HEART -> if (moving || phase == BubblePhase.FORMING) {
                scale *= beatScale(beat, 0.075f)
                // Each beat sends a ring out; the previous one is still fading.
                for (back in 0..1) {
                    val age = (beat + back) * beatMs / 1100f
                    if (age < 1f) rings += Ring(radius = 1.05f + 0.6f * easeOut(age), alpha = 0.35f * (1f - age))
                }
            }
            BubbleStyle.BLOOD_PRESSURE -> if (moving) {
                // A cuff-like squeeze in step with the pulse: wider, then taller.
                squeeze = beatScale(beat, 0.07f) - 1f
                scale *= 1f + 0.02f * sin(t * 1.4f)
            }
            BubbleStyle.STRESS -> if (moving) {
                // Breathe: about 5 s in, 5 s out.
                scale *= 1f + 0.07f * sin(t * TAU / 10f)
                orbitSpeed *= 0.5f
            }
            BubbleStyle.TEMPERATURE -> {
                warmth = if (phase == BubblePhase.MEASURING || phase == BubblePhase.HINT) prog else if (phase == BubblePhase.SUCCESS) 1f else 0.15f
                shimmer = (t * 0.45f) % 1f
                wobble += 0.008f * (1f + sin(t * 9f))
            }
            BubbleStyle.ECG -> {
                trace = (t * 0.55f) % 1f
                glow = if (moving) 0.55f + 0.15f * beatScale(beat, 1f) else glow
            }
            BubbleStyle.SPO2, BubbleStyle.BODY -> Unit
        }

        // The outline: a few slow harmonics, plus the squeeze.
        val radii = FloatArray(CONTOUR_POINTS) { i ->
            val a = i * TAU / CONTOUR_POINTS
            var r = 1f
            for (h in 0 until HARMONICS) {
                r += wobble / (h + 1) * sin((h + 2) * a + shapePhases[h] + t * shapeSpeeds[h] * TAU / 4f)
            }
            r + squeeze * cos(2 * a)
        }

        // Satellites: an orbit tilted towards the viewer, so they pass in front of and behind the bubble.
        val behind = mutableListOf<Orb>()
        val front = mutableListOf<Orb>()
        satellites.forEachIndexed { i, s ->
            val a = s.angle + t * s.speed * TAU * orbitSpeed
            var orbit = s.orbit
            var size = s.size
            var a2 = alpha
            if (phase == BubblePhase.FORMING) {
                // Spiral inwards and shrink into the surface.
                val g = ((gather + i / SATELLITES.toFloat()) % 1f)
                orbit = 2.3f - 1.4f * easeIn(g)
                size *= 1f - 0.6f * g
                a2 = (1f - g) * 1.6f
            }
            if (style == BubbleStyle.BODY && i < 2) {
                // Two "fingers" on the keys, flowing towards the bubble from both sides.
                val k = (t * 0.7f + i * 0.5f) % 1f
                val side = if (i == 0) -1f else 1f
                behind += Orb(side * (1.75f - 0.5f * k), 0.15f, 0.2f, 0.16f * (1f - 0.4f * k), (1f - k) * a2.coerceAtMost(1f))
                return@forEachIndexed
            }
            val x = cos(a) * orbit
            val z = sin(a)
            val y = sin(a) * orbit * s.tilt - 0.15f
            val depth = 0.75f + 0.25f * z
            val orb = Orb(x, y, z, size * depth, (a2 * (0.55f + 0.45f * depth)).coerceIn(0f, 1f))
            if (z >= 0) front += orb else behind += orb
        }

        // Fizz inside: small bubbles rising through the liquid (oxygen for SpO2, fewer elsewhere).
        val inside = mutableListOf<Orb>()
        val fizzCount = when (style) {
            BubbleStyle.SPO2 -> FIZZ
            BubbleStyle.BODY, BubbleStyle.TEMPERATURE -> FIZZ / 3
            else -> FIZZ / 2
        }
        val surface = 1f - 2f * level
        if (phase != BubblePhase.FAILED && level > 0.05f) {
            for (f in fizz.take(fizzCount)) {
                val k = (t * f.speed + f.offset) % 1f
                val y = 1f - k * (1f - surface + 0.1f) * 1.05f
                if (y < surface) continue
                val fade = ((y - surface) / 0.15f).coerceIn(0f, 1f)
                inside += Orb(f.x * (0.6f + 0.4f * (1f - k)), y, 0f, f.size, 0.75f * fade * alpha)
            }
        }

        // The burst.
        val sparks = mutableListOf<Orb>()
        if (phase == BubblePhase.SUCCESS && p in 0.25f..1.6f) {
            val k = ((p - 0.25f) / 1.35f).coerceIn(0f, 1f)
            for (s in burst) {
                val d = 1f + s.speed * easeOut(k) * 1.3f
                sparks += Orb(cos(s.angle) * d, sin(s.angle) * d + 0.6f * k * k, 0f, s.size * (1f - 0.5f * k), 1f - k)
            }
        }

        val layers = if (style == BubbleStyle.BODY) floatArrayOf(level * 0.38f, level * 0.72f, level) else FloatArray(0)
        return BubbleFrame(
            centerX = cx,
            centerY = cy,
            scale = scale,
            alpha = alpha,
            radii = radii,
            level = level,
            wavePhase = t * 2.2f,
            waveHeight = if (phase == BubblePhase.HINT) 0.07f else 0.04f,
            layers = layers,
            behind = behind,
            front = front,
            inside = inside,
            sparks = sparks,
            rings = rings,
            amber = amber,
            grey = grey,
            warmth = warmth,
            shimmer = shimmer,
            trace = trace,
            glow = glow,
            progress = prog,
            showProgress = phase == BubblePhase.MEASURING || phase == BubblePhase.HINT,
        )
    }

    companion object {
        const val CONTOUR_POINTS = 64
        private const val HARMONICS = 3
        private const val SATELLITES = 7
        private const val FIZZ = 14
        private const val BURST = 16
        private const val TAU = (2 * PI).toFloat()

        /** A heartbeat: a quick squeeze up, a slower release. [beat] is 0…1 through one beat. */
        fun beatScale(beat: Float, amount: Float): Float = when {
            beat < 0.12f -> 1f + amount * easeOut(beat / 0.12f)
            beat < 0.3f -> 1f + amount - amount * 1.35f * easeIn((beat - 0.12f) / 0.18f)
            beat < 0.5f -> 1f - amount * 0.35f + amount * 0.35f * easeOut((beat - 0.3f) / 0.2f)
            else -> 1f
        }

        private fun easeOut(x: Float) = 1f - (1f - x) * (1f - x) * (1f - x)
        private fun easeIn(x: Float) = x * x * x
    }
}
