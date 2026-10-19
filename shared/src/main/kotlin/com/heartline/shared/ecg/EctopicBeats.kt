// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.ecg

import com.heartline.shared.model.EcgNote
import kotlin.math.abs

/**
 * Early-beat kinds, beat by beat (algorithm 4, docs/algorithms/ECG_ALGORITHM.md): each beat's
 * timing against its neighbours and shape against the dominant beat go through a small learned
 * model (trained on the beat-annotated MIT-BIH, SVDB, INCART, LTAFDB and CPSC 2021 recordings,
 * tools/ecg-eval/train_beats.py) that tells normal-conducted (N) from supraventricular (S, early,
 * usually normal shape) and ventricular (V, different and often wider shape, no P wave) beats.
 * Wellness wording only: "extra beats from the upper / lower chambers", never a diagnosis.
 */
object EctopicBeats {
    val NAMES = listOf(
        "rr_prev_ratio", "rr_next_ratio", "rr_pair_ratio", "rr_prev_next_ratio", "rr_before_ratio", "rr_after_ratio",
        "corr_dominant", "corr_own", "width_diff_ms", "range_ratio", "other_shape", "noise_ratio",
        "p_corr", "local_cov", "bpm", "polarity"
    )

    /** Beats in the local window around each beat whose intervals set its expected interval. */
    const val LOCAL = 8

    /** A beat counts as S or V when its class probability reaches the model's threshold for it. */
    data class Counts(val supraventricular: Int, val ventricular: Int, val classified: Int) {
        /** Which kinds were seen, or null for none. */
        val note: EcgNote?
            get() = when {
                supraventricular > 0 && ventricular > 0 -> EcgNote.EXTRA_BEATS_BOTH
                ventricular > 0 -> EcgNote.EXTRA_BEATS_LOWER
                supraventricular > 0 -> EcgNote.EXTRA_BEATS_UPPER
                else -> null
            }

        /** At least [EcgAnalyzer.FREQUENT_EARLY] of the classified beats are early. */
        val frequent: Boolean get() = supraventricular + ventricular >= EcgAnalyzer.FREQUENT_EARLY * classified.coerceAtLeast(1)

        companion object {
            val NONE = Counts(0, 0, 0)
        }
    }

    /**
     * Feature rows per beat (null where the beat or a neighbour isn't trustworthy, or the
     * intervals around it span a gap).
     */
    fun features(
        clean: FloatArray,
        peaks: IntArray,
        fs: Int,
        beats: BeatClusters.Beats,
        valid: BooleanArray,
        segmentOf: (Int) -> Int
    ): List<DoubleArray?> {
        val n = peaks.size
        // Interval ending at beat i, where both ends are trustworthy and in the same segment.
        val rr = DoubleArray(n) { i ->
            if (i == 0 || !valid[i] || !valid[i - 1] || segmentOf(peaks[i]) != segmentOf(peaks[i - 1])) {
                Double.NaN
            } else {
                ((peaks[i] - peaks[i - 1]) * 1000.0 / fs).takeIf { it in 200.0..3000.0 } ?: Double.NaN
            }
        }
        val dominantWidth = beats.qrsWidthMs.firstOrNull() ?: 0.0
        val pre = (0.10 * fs).toInt()
        val post = (0.15 * fs).toInt()
        val dominantBeats = peaks.indices.filter { valid[it] && beats.cluster[it] == 0 }
        val range = { b: Int ->
            val from = (peaks[b] - (0.06 * fs).toInt()).coerceAtLeast(0)
            val to = (peaks[b] + (0.08 * fs).toInt()).coerceAtMost(clean.size - 1)
            if (to <= from) 0.0 else (from..to).maxOf { clean[it].toDouble() } - (from..to).minOf { clean[it].toDouble() }
        }
        val dominantRange = EcgQuality.median(dominantBeats.map(range)).coerceAtLeast(1e-6)
        // The stretch before the QRS where the P wave sits (250–60 ms before R), on the dominant beat.
        val pFrom = (0.25 * fs).toInt()
        val pTo = (0.06 * fs).toInt()
        val pTemplate = dominantBeats.filter { peaks[it] - pFrom >= 0 }.let { members ->
            if (members.size < 3) {
                null
            } else {
                DoubleArray(pFrom - pTo) { k -> EcgQuality.median(members.map { clean[peaks[it] - pFrom + k].toDouble() }) }
            }
        }
        val inverted = beats.inverted
        return List(n) { i ->
            val prev = rr[i]
            val next = rr.getOrElse(i + 1) { Double.NaN }
            if (prev.isNaN() || next.isNaN() || peaks[i] - pre < 0 || peaks[i] + post >= clean.size) return@List null
            val local = (maxOf(1, i - LOCAL)..minOf(n - 1, i + LOCAL)).map { rr[it] }.filter { !it.isNaN() }
            if (local.size < 4) return@List null
            val median = EcgQuality.median(local)
            val mean = local.average()
            val sd = kotlin.math.sqrt(local.sumOf { (it - mean) * (it - mean) } / local.size)
            val pCorr = pTemplate?.let { t ->
                if (peaks[i] - pFrom < 0) {
                    0.0
                } else {
                    BeatClusters.correlation(t, DoubleArray(t.size) { k -> clean[peaks[i] - pFrom + k].toDouble() })
                }
            } ?: 0.0
            // R against the beat's own surroundings (the filtered signal has no fixed baseline).
            val height = clean[peaks[i]] - EcgQuality.median((peaks[i] - pre until peaks[i] + post).map { clean[it].toDouble() })
            // R deflection on the dominant beat's side (+1) or the other (−1).
            val polarity = if (abs(height) < 1e-9) {
                0.0
            } else if ((height < 0) == inverted) {
                1.0
            } else {
                -1.0
            }
            doubleArrayOf(
                prev / median,
                next / median,
                (prev + next) / (2 * median),
                prev / next,
                // The intervals one further out: a gradual (sinus) change also shortens these.
                rr.getOrElse(i - 1) { Double.NaN }.let { if (it.isNaN()) 1.0 else it / median },
                rr.getOrElse(i + 2) { Double.NaN }.let { if (it.isNaN()) 1.0 else it / median },
                beats.dominantCorrelation.getOrElse(i) { 1.0 },
                beats.correlation.getOrElse(i) { 1.0 },
                (beats.qrsWidthMs.getOrNull(beats.cluster[i]) ?: dominantWidth) - dominantWidth,
                range(i) / dominantRange,
                if (beats.isOtherShape(i)) 1.0 else 0.0,
                beats.noiseRatio.getOrElse(i) { 1.0 },
                pCorr,
                sd / mean,
                60_000 / median,
                polarity
            )
        }
    }

    /** Beat class probabilities (N, S, V) per beat, null where unclassified. */
    fun classify(model: RhythmModel, rows: List<DoubleArray?>): List<DoubleArray?> = rows.map { it?.let(model::predict) }

    fun count(model: RhythmModel, probabilities: List<DoubleArray?>): Counts {
        val s = model.classes.indexOf("S")
        val v = model.classes.indexOf("V")
        val ts = model.thresholds["S"] ?: 0.5
        val tv = model.thresholds["V"] ?: 0.5
        var ns = 0
        var nv = 0
        var classified = 0
        for (p in probabilities) {
            if (p == null) continue
            classified++
            when {
                p[v] >= tv && p[v] / tv >= p[s] / ts -> nv++
                p[s] >= ts -> ns++
            }
        }
        return Counts(ns, nv, classified)
    }

    /** The bundled beat model, or null if it isn't there (then no beat kinds are reported). */
    val bundled: RhythmModel? by lazy { RhythmModel.resource("/ecg/beat_model.json", NAMES) }
}
