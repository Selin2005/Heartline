// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.bp

import com.heartline.shared.dsp.Biquad
import com.heartline.shared.dsp.filtFilt
import kotlin.math.abs

/** Turns one recording into a fixed-length vector for the learned model. */
fun interface PpgEmbedder {
    fun embed(ppg: FloatArray?, fs: Int, features: PpgFeatureVector): DoubleArray?
}

/**
 * Default embedder, pure Kotlin: the 32-point ensemble beat, its slope at 16 points, and the
 * core timing features scaled to similar ranges. The phone can swap in a pretrained PPG encoder
 * (PaPaGei, see tools/bp-ml) behind the same interface.
 */
object MorphologyEmbedder : PpgEmbedder {
    override fun embed(ppg: FloatArray?, fs: Int, features: PpgFeatureVector): DoubleArray? {
        val shape = features.shape
        if (shape.size != PpgFeatureVector.SHAPE_POINTS) return null
        val slope = List(16) { k ->
            val i = 1 + k * (shape.size - 3) / 15
            ((shape[i + 1] - shape[i - 1]) * 4).toDouble()
        }
        val timing = listOf(
            features.heartRateBpm / 60.0,
            features.upstrokeMs / 100.0,
            features.width50Ms / 300.0,
            features.reflectionDelayMs / 300.0,
            features.reflectionIndex
        )
        return (shape.map { it.toDouble() } + slope + timing).toDoubleArray()
    }
}

/**
 * Personal learned correction on top of the calibrated estimator (the "head" of the hybrid model).
 *
 * Target: cuff − classical estimate on this user's cuff checks, from an embedding of the pulse
 * wave. Ridge regression with the penalty chosen by leave-one-out (LOO) error, so with the few
 * samples one person has it stays close to "no correction". It is used only when its LOO error
 * beats the classical estimator's by [GATE] on this user's own data; otherwise the classical
 * estimate stands. Every learned model in the literature degrades on people and states it
 * wasn't trained on, so the gate is the point.
 */
class HybridBpModel(private val embedder: PpgEmbedder = MorphologyEmbedder) {
    /** One cuff check: the recording, the classical estimate made before this cuff reading was known, the cuff. */
    data class Sample(
        val features: PpgFeatureVector,
        val ppg: FloatArray?,
        val classicalSys: Int,
        val classicalDia: Int,
        val cuffSys: Int,
        val cuffDia: Int
    )

    class Trained internal constructor(
        private val embedder: PpgEmbedder,
        private val fs: Int,
        private val mean: DoubleArray,
        private val sysWeights: DoubleArray,
        private val diaWeights: DoubleArray,
        private val sysBias: Double,
        private val diaBias: Double,
        val lambda: Double,
        val looMaeClassical: Double,
        val looMaeHybrid: Double,
        /** LOO absolute errors of the hybrid, for a conformal interval. */
        val looErrors: List<Double>
    ) {
        /** @return the corrected (systolic, diastolic), or null when the recording can't be embedded. */
        fun correct(classical: BpEstimate, features: PpgFeatureVector, ppg: FloatArray?): Pair<Int, Int>? {
            val e = embedder.embed(ppg, fs, features) ?: return null
            val x = DoubleArray(e.size) { e[it] - mean[it] }
            val ds = sysBias + x.indices.sumOf { sysWeights[it] * x[it] }
            val dd = diaBias + x.indices.sumOf { diaWeights[it] * x[it] }
            val sys = (classical.systolic + ds.coerceIn(-MAX_CORRECTION, MAX_CORRECTION)).toInt().coerceIn(BpEstimator.SYSTOLIC_LIMITS)
            val dia = (classical.diastolic + dd.coerceIn(-MAX_CORRECTION, MAX_CORRECTION)).toInt().coerceIn(BpEstimator.DIASTOLIC_LIMITS)
            return sys to dia.coerceAtMost(sys - 15)
        }
    }

    /** @return a model that beat the classical estimator on LOO, or null (use the classical estimate). */
    fun train(samples: List<Sample>, fs: Int = BpCalibration.PPG_FS): Trained? {
        val rows = samples.mapNotNull { s -> embedder.embed(s.ppg, fs, s.features)?.let { it to s } }
        if (rows.size < MIN_SAMPLES) return null
        val dims = rows.first().first.size
        if (rows.any { it.first.size != dims }) return null
        val ySys = rows.map { (it.second.cuffSys - it.second.classicalSys).toDouble() }
        val yDia = rows.map { (it.second.cuffDia - it.second.classicalDia).toDouble() }
        val x = rows.map { it.first }
        val classicalMae = ySys.map { abs(it) }.average()

        var best: Pair<Double, List<Double>>? = null
        for (lambda in LAMBDAS) {
            val errors = x.indices.map { out ->
                val keep = x.indices.filter { it != out }
                val fitted = ridge(keep.map { x[it] }, keep.map { ySys[it] }, lambda)
                abs(ySys[out] - fitted.predict(x[out]))
            }
            if (best == null || errors.average() < best.second.average()) best = lambda to errors
        }
        val (lambda, looErrors) = best ?: return null
        val hybridMae = looErrors.average()
        // Algorithm 6.4: on a real user it switched on with 6 checks and made one reading 4 mmHg
        // worse. Now it must clearly beat the classical estimate, on the diastolic too.
        if (hybridMae > classicalMae * GATE || hybridMae > classicalMae - MIN_GAIN_MMHG) return null
        val diaLoo = x.indices.map { out ->
            val keep = x.indices.filter { it != out }
            abs(yDia[out] - ridge(keep.map { x[it] }, keep.map { yDia[it] }, lambda).predict(x[out]))
        }.average()
        if (diaLoo > yDia.map { abs(it) }.average()) return null
        val sys = ridge(x, ySys, lambda)
        val dia = ridge(x, yDia, lambda)
        return Trained(embedder, fs, sys.mean, sys.weights, dia.weights, sys.bias, dia.bias, lambda, classicalMae, hybridMae, looErrors)
    }

    private class Ridge(val mean: DoubleArray, val weights: DoubleArray, val bias: Double) {
        fun predict(e: DoubleArray) = bias + e.indices.sumOf { weights[it] * (e[it] - mean[it]) }
    }

    /**
     * Ridge in its dual form (n ≪ d): w = Xᵀ(XXᵀ + λI)⁻¹(y − b). The bias is itself shrunk
     * towards 0 (b = Σy / (n + λ)), so a handful of checks can't impose a big offset.
     */
    private fun ridge(x: List<DoubleArray>, y: List<Double>, lambda: Double): Ridge {
        val n = x.size
        val d = x.first().size
        val mean = DoubleArray(d) { j -> x.sumOf { it[j] } / n }
        val c = x.map { row -> DoubleArray(d) { row[it] - mean[it] } }
        val bias = y.sum() / (n + lambda)
        val k = Array(n) { i -> DoubleArray(n) { j -> c[i].indices.sumOf { c[i][it] * c[j][it] } + if (i == j) lambda else 0.0 } }
        val alpha = solve(k, DoubleArray(n) { y[it] - bias }) ?: DoubleArray(n)
        val w = DoubleArray(d) { j -> (0 until n).sumOf { alpha[it] * c[it][j] } }
        return Ridge(mean, w, bias)
    }

    private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        val m = Array(n) { i -> a[i].copyOf(n + 1).also { it[n] = b[i] } }
        for (col in 0 until n) {
            val pivot = (col until n).maxBy { abs(m[it][col]) }
            if (abs(m[pivot][col]) < 1e-12) return null
            val tmp = m[col]
            m[col] = m[pivot]
            m[pivot] = tmp
            for (r in 0 until n) {
                if (r == col) continue
                val factor = m[r][col] / m[col][col]
                for (cc in col..n) m[r][cc] -= factor * m[col][cc]
            }
        }
        return DoubleArray(n) { m[it][n] / m[it][it] }
    }

    companion object {
        const val MIN_SAMPLES = 12

        /** The hybrid must also cut the LOO systolic error by at least this much, mmHg. */
        const val MIN_GAIN_MMHG = 1.5

        /** The hybrid must cut the LOO error by at least 10 %. */
        const val GATE = 0.9
        const val MAX_CORRECTION = 25.0
        private val LAMBDAS = doubleArrayOf(0.3, 1.0, 3.0, 10.0, 30.0, 100.0)
    }
}

/**
 * Input for the PaPaGei PPG encoder (Pillai et al., ICLR 2025): upright, band-passed 0.5–12 Hz,
 * 50 ms smoothed, resampled to 125 Hz and cut into z-scored 10 s segments of 1250 samples, as
 * in its reference preprocessing (tools/bp-ml/ppg_prep.py mirrors this).
 */
object PapageiInput {
    const val FS = 125
    const val SEGMENT = FS * 10

    fun segments(raw: FloatArray, fs: Int): List<FloatArray> {
        if (raw.size < fs * 10) return emptyList()
        val filtered = raw.filtFilt(Biquad.highPass(0.5, fs.toDouble()), Biquad.lowPass(12.0, fs.toDouble()))
        val x = if (PpgFeatures.isInverted(filtered)) FloatArray(filtered.size) { -filtered[it] } else filtered
        val w = maxOf(1, (0.05 * fs).toInt())
        val smooth = FloatArray(x.size) { i ->
            val from = maxOf(0, i - w / 2)
            val to = minOf(x.size - 1, i + (w - 1) / 2)
            var s = 0f
            for (k in from..to) s += x[k]
            s / (to - from + 1)
        }
        val n = (smooth.size.toLong() * FS / fs).toInt()
        val resampled = FloatArray(n) { k ->
            val pos = k.toDouble() * fs / FS
            val i = pos.toInt().coerceAtMost(smooth.size - 2)
            val f = (pos - i).toFloat()
            smooth[i] * (1 - f) + smooth[i + 1] * f
        }
        return (0..resampled.size - SEGMENT step SEGMENT).map { start ->
            val seg = resampled.copyOfRange(start, start + SEGMENT)
            val mean = seg.average().toFloat()
            val sd = kotlin.math.sqrt(seg.sumOf { ((it - mean) * (it - mean)).toDouble() } / seg.size).toFloat().takeIf { it > 1e-9f } ?: 1f
            FloatArray(seg.size) { (seg[it] - mean) / sd }
        }
    }
}
