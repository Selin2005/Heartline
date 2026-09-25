package com.heartline.shared.ecg

import com.heartline.shared.dsp.Biquad
import com.heartline.shared.dsp.filtFilt
import com.heartline.shared.hr.RrFeatures
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The recording-level features the learned rhythm model reads. Computed by the same Kotlin code
 * on the watch, the phone and in training (tools/ecg-eval), so they can't drift apart.
 */
object RhythmFeatures {
    val NAMES = listOf(
        "bpm", "nrmssd", "entropy", "tpr", "cov", "clean_nrmssd", "clean_entropy", "clean_cov", "clean_share",
        "p_ratio", "ectopic_share", "other_shape_share", "patterned", "noise", "usable_share", "valid_beat_share",
        "mean_b", "median_ln_k", "sd1_sd2", "near_median_share", "max_min_ratio", "pauses", "qrs_width_ms", "amplitude_mv"
    )

    fun of(
        bpm: Int?,
        runs: List<List<RrInterval>>,
        premature: Set<Int>,
        otherShape: Set<Int>,
        pRatio: Double?,
        patterned: Boolean,
        noise: Double,
        usableShare: Double,
        validBeatShare: Double,
        sqis: List<WindowSqi?>,
        pauses: Int,
        qrsWidthMs: Double,
        amplitudeMv: Double
    ): DoubleArray {
        val all = runs.flatten().map { it.ms }
        val f = RrFeatures.of(all)
        val clean = RhythmClassifier.filtered(runs, premature)
        val c = RrFeatures.of(clean)
        val v = sqis.filterNotNull()
        val pairs = runs.flatMap { run -> run.zipWithNext { a, b -> a.ms to b.ms } }
        val sd1sd2 = if (pairs.size < 3) {
            0.0
        } else {
            val d = pairs.map { (a, b) -> (b - a) / sqrt(2.0) }
            val s = pairs.map { (a, b) -> (b + a) / sqrt(2.0) }
            sd(d) / sd(s).coerceAtLeast(1e-9)
        }
        val median = all.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }
        return doubleArrayOf(
            bpm?.toDouble() ?: 0.0,
            f?.nRmssd ?: 0.0,
            f?.shannonEntropy ?: 0.0,
            f?.turningPointRatio ?: 0.0,
            f?.cov ?: 0.0,
            c?.nRmssd ?: 0.0,
            c?.shannonEntropy ?: 0.0,
            c?.cov ?: 0.0,
            if (all.isEmpty()) 0.0 else clean.size.toDouble() / all.size,
            pRatio ?: -1.0,
            premature.size.toDouble() / (all.size + 1),
            otherShape.size.toDouble() / (all.size + 1),
            if (patterned) 1.0 else 0.0,
            noise,
            usableShare,
            validBeatShare,
            if (v.isEmpty()) 0.0 else v.map { it.b }.average(),
            if (v.isEmpty()) 0.0 else EcgQuality.median(v.map { ln(it.k.coerceAtLeast(1e-3)) }),
            sd1sd2,
            if (all.isEmpty()) 0.0 else all.count { abs(it - median) <= 15.0 }.toDouble() / all.size,
            if (all.isEmpty()) 0.0 else all.max() / all.min(),
            pauses.toDouble(),
            qrsWidthMs,
            amplitudeMv
        )
    }

    private fun sd(x: List<Double>): Double {
        val m = x.average()
        return sqrt(x.sumOf { (it - m) * (it - m) } / (x.size - 1).coerceAtLeast(1))
    }
}

/**
 * Gradient-boosted decision trees over [RhythmFeatures], trained offline on PhysioNet/CinC 2017
 * and MIT-BIH (tools/ecg-eval/train_rhythm.py) and shipped as a JSON resource. Classes:
 * normal, AF, other rhythm, noisy. Pure Kotlin, so the watch runs it too.
 */
@Serializable
class RhythmModel(
    val features: List<String>,
    val classes: List<String>,
    val learningRate: Double,
    val init: List<Double>,
    /** trees[round][class] */
    val trees: List<List<Tree>>,
    /** Decision thresholds chosen by cross-validation (see train_rhythm.py). */
    val thresholds: Map<String, Double> = emptyMap(),
    val info: String = ""
) {
    @Serializable
    class Tree(val feature: IntArray, val threshold: DoubleArray, val left: IntArray, val right: IntArray, val value: DoubleArray) {
        fun predict(x: DoubleArray): Double {
            var n = 0
            while (left[n] >= 0) n = if (x[feature[n]] <= threshold[n]) left[n] else right[n]
            return value[n]
        }
    }

    /** Class probabilities in [classes] order. */
    fun predict(x: DoubleArray): DoubleArray {
        val raw = DoubleArray(classes.size) { init[it] }
        for (round in trees) for (k in round.indices) raw[k] += learningRate * round[k].predict(x)
        val max = raw.max()
        val e = raw.map { exp(it - max) }
        val sum = e.sum()
        return DoubleArray(raw.size) { e[it] / sum }
    }

    fun probability(x: DoubleArray, cls: String) = predict(x)[classes.indexOf(cls)]

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): RhythmModel = json.decodeFromString(text)

        /** The bundled model, or null if it isn't there (the rules alone then decide). */
        val bundled: RhythmModel? by lazy {
            runCatching {
                RhythmModel::class.java.getResourceAsStream("/ecg/rhythm_model.json")?.use {
                    json.decodeFromString<RhythmModel>(it.readBytes().decodeToString())
                }
            }.getOrNull()?.takeIf { it.features == RhythmFeatures.NAMES }
        }
    }
}

/**
 * Input windows for ECGFounder (Li et al., NEJM AI 2025): 500 Hz, 50 Hz notch (Q 30), up to three
 * 10 s windows, each z-scored — as in its reference preprocessing (tools/ecg-ml mirrors this).
 */
object EcgFounderInput {
    const val FS = 500
    const val WINDOW = 10 * FS

    fun windows(raw: FloatArray, fs: Int, max: Int = 3): List<FloatArray> {
        val x = if (fs == FS) {
            raw
        } else {
            FloatArray((raw.size.toLong() * FS / fs).toInt()) { k ->
                val pos = k.toDouble() * fs / FS
                val i = pos.toInt().coerceAtMost(raw.size - 2)
                (raw[i] + (raw[i + 1] - raw[i]) * (pos - i)).toFloat()
            }
        }
        if (x.size < WINDOW) return emptyList()
        val notched = x.filtFilt(Biquad.notch(50.0, FS.toDouble(), 30.0))
        return (0..minOf(notched.size, max * WINDOW) - WINDOW step WINDOW).map { start ->
            val w = notched.copyOfRange(start, start + WINDOW)
            val mean = w.average()
            val sd = sqrt(w.sumOf { (it - mean) * (it - mean) } / w.size)
            FloatArray(WINDOW) { ((w[it] - mean) / (sd + 1e-8)).toFloat() }
        }
    }
}
