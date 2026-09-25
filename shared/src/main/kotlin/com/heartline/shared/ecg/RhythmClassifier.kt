package com.heartline.shared.ecg

import com.heartline.shared.hr.RrFeatures
import com.heartline.shared.model.EcgNote
import com.heartline.shared.model.EcgPoorReason
import com.heartline.shared.model.EcgResult
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * One RR interval between two consecutive valid beats of the same contact segment.
 * [beat] is the index (into the peaks) of the beat that ends it.
 */
data class RrInterval(val beat: Int, val ms: Double)

/**
 * Wellness rhythm classification with SHM's categories (algorithm 3, docs/ECG_ALGORITHM.md):
 * 1. early beats are found (interval < 85 % of the local median) and labelled by shape (a
 *    different cluster with a wider QRS is "ventricular-like");
 * 2. irregularity is judged after removing early beats and their compensatory intervals
 *    (Petrėnas et al. 2015), so a few extra beats don't read as AFib;
 * 3. a Lorenz plot (RRᵢ, RRᵢ₊₁) that falls into 2–3 tight clusters is a patterned irregularity
 *    (bigeminy, frequent ectopy), not AFib ("regularity within irregularity", Sensors 2023);
 * 4. AFib is classified up to 150 bpm (as Apple ECG 2.0), before the heart-rate bounds.
 */
object RhythmClassifier {
    const val LOW_HR = 50
    const val HIGH_HR = 120
    const val AF_MAX_HR = 150
    const val SINUS_MAX_HR = 100

    /** P wave height relative to the whole beat (typically 0.1–0.2 in lead I; absent in AFib). */
    const val P_WAVE_PRESENT_RATIO = 0.03

    const val PREMATURE = 0.85
    const val EARLY_OTHER_SHAPE = 0.95
    const val FREQUENT_ECTOPY = 0.10

    /** Beats of another shape above this share: too much ectopy to judge the underlying rhythm. */
    const val MAX_OTHER_SHAPE = 0.2

    /** Lorenz cluster radius (fraction of the mean interval) that counts as "tight". */
    const val CLUSTER_RADIUS = 0.05

    data class Evidence(
        val quality: Double,
        val features: RrFeatures?,
        val pWaveMv: Double?,
        val pWaveRatio: Double? = null,
        val ectopicBeats: Int = 0,
        val ventricularLike: Int = 0,
        val patterned: Boolean = false,
        /** The learned model's class probabilities (normal, AF, other, noisy), when it ran. */
        val modelProbabilities: List<Double>? = null
    )

    data class Decision(val result: EcgResult, val note: EcgNote)

    /**
     * Early beats: an interval shorter than [PREMATURE] × the local median, followed by a
     * (compensatory) interval at least as long as the median. The short–long pair is what sets an
     * extra beat apart from AFib's random intervals and from gradual sinus arrhythmia.
     */
    fun prematureBeats(runs: List<List<RrInterval>>, differentShape: Set<Int> = emptySet()): Set<Int> = buildSet {
        for (run in runs) {
            for (i in run.indices) {
                // The local median of intervals ending in a normal-shaped beat (ectopic ones would drag it down).
                val local = run.subList(maxOf(0, i - 4), minOf(run.size, i + 5)).filter {
                    it.beat !in differentShape
                }.map { it.ms }.sorted()
                if (local.size < 3) continue
                val median = local[local.size / 2]
                val next = run.getOrNull(i + 1)?.ms
                val shortLong = run[i].ms < PREMATURE * median && (next == null || next >= median)
                // A beat of another shape that comes early is ectopic even without a full compensatory pause.
                val earlyOtherShape = run[i].beat in differentShape && run[i].ms < EARLY_OTHER_SHAPE * median
                if (shortLong || earlyOtherShape) add(run[i].beat)
            }
        }
    }

    /** Intervals without early beats and without the (compensatory) interval right after one. */
    fun filtered(runs: List<List<RrInterval>>, premature: Set<Int>): List<Double> = runs.flatMap { run ->
        run.filterIndexed { i, rr -> rr.beat !in premature && (i == 0 || run[i - 1].beat !in premature) }.map { it.ms }
    }

    /** Do successive-interval pairs fall into 1–3 tight clusters (≥ 85 % of points)? */
    fun patterned(runs: List<List<RrInterval>>): Boolean {
        val points = runs.flatMap { run -> run.zipWithNext { a, b -> a.ms to b.ms } }
        if (points.size < 6) return false
        val mean = points.map { it.first }.average()
        val p = points.map { (a, b) -> a / mean to b / mean }
        for (k in 2..3) {
            val centres = kMeans(p, k)
            val assigned = p.map { pt -> centres.minBy { dist(it, pt) } }
            val close = p.indices.count { dist(assigned[it], p[it]) <= 2 * CLUSTER_RADIUS }
            val radii = centres.map { c -> p.filter { pt -> centres.minBy { dist(it, pt) } == c }.map { dist(c, it) } }
            val tight = radii.all { r -> r.isEmpty() || sqrt(r.map { it * it }.average()) <= CLUSTER_RADIUS }
            val populated = radii.count { it.size >= 2 } >= 2
            val separated = centres.indices.all { a ->
                centres.indices.all { b ->
                    a == b ||
                        dist(centres[a], centres[b]) >= 4 * CLUSTER_RADIUS
                }
            }
            if (tight && populated && separated && close >= 0.9 * p.size) return true
        }
        return false
    }

    /** Clearly regular, or a smooth (respiratory) sinus arrhythmia: gradual changes, no jumps. */
    fun regular(f: RrFeatures, rr: List<Double>): Boolean {
        if (f.isRegular) return true
        if (rr.size < RrFeatures.MIN_INTERVALS || f.cov > 0.15) return false
        val jumps = rr.zipWithNext { a, b -> abs(b - a) / f.meanMs }
        return jumps.max() < 0.15
    }

    fun classify(
        reason: EcgPoorReason,
        bpm: Int?,
        runs: List<List<RrInterval>>,
        pWaveRatio: Double?,
        pauses: Int,
        differentShape: Set<Int> = emptySet()
    ): Decision {
        val all = runs.flatten().map { it.ms }
        val allFeatures = RrFeatures.of(all)
        if (reason != EcgPoorReason.NONE || bpm == null || allFeatures == null) return Decision(EcgResult.POOR_RECORDING, EcgNote.NONE)
        val premature = prematureBeats(runs, differentShape)
        val clean = filtered(runs, premature)
        val cleanFeatures = RrFeatures.of(clean)
        val ectopicShare = premature.size.toDouble() / (all.size + 1)
        val otherShapeShare = differentShape.size.toDouble() / (all.size + 1)
        val isPatterned = allFeatures.isIrregular && patterned(runs)
        val pPresent = pWaveRatio != null && pWaveRatio >= P_WAVE_PRESENT_RATIO
        // With too few intervals left once early beats are removed, the underlying rhythm can't be judged.
        val judgeable = cleanFeatures != null && clean.size >= RrFeatures.MIN_INTERVALS && otherShapeShare < MAX_OTHER_SHAPE
        val regularBase = cleanFeatures != null && regular(cleanFeatures, clean)
        // Irregular even after removing early beats, and not in a patterned way.
        val afib = judgeable && allFeatures.isIrregular && !isPatterned && !regularBase && (!pPresent || allFeatures.nRmssd > 0.15)
        val note = when {
            pauses > 0 -> EcgNote.PAUSES
            premature.isNotEmpty() && (ectopicShare >= FREQUENT_ECTOPY || isPatterned || !judgeable) -> EcgNote.FREQUENT_EXTRA_BEATS
            premature.isNotEmpty() -> EcgNote.EXTRA_BEATS
            else -> EcgNote.NONE
        }
        return when {
            afib && bpm <= AF_MAX_HR -> Decision(EcgResult.AFIB_SIGNS, EcgNote.NONE)
            bpm > HIGH_HR -> Decision(EcgResult.HIGH_HEART_RATE, if (bpm > AF_MAX_HR) EcgNote.RATE_ABOVE_150 else note)
            bpm < LOW_HR -> Decision(EcgResult.LOW_HEART_RATE, note)
            (regular(allFeatures, all) || (regularBase && judgeable && ectopicShare < FREQUENT_ECTOPY && !isPatterned)) &&
                bpm <= SINUS_MAX_HR &&
                pPresent ->
                Decision(EcgResult.SINUS_RHYTHM, if (regular(allFeatures, all)) EcgNote.NONE else note)
            else -> Decision(
                EcgResult.INCONCLUSIVE,
                when {
                    note == EcgNote.FREQUENT_EXTRA_BEATS || note == EcgNote.PAUSES -> note
                    isPatterned || !regularBase -> EcgNote.IRREGULAR_PATTERN
                    bpm > SINUS_MAX_HR -> EcgNote.FAST_REGULAR
                    !pPresent -> EcgNote.NO_CLEAR_P_WAVE
                    else -> note
                }
            )
        }
    }

    /**
     * Peak-to-baseline amplitude of the averaged P wave: normal-shaped, non-early beats are aligned
     * on R, the median beat is formed, and the largest deflection 350–80 ms before R (a long PR
     * included) is measured against the PR baseline just before the QRS.
     */
    fun pWaveAmplitude(clean: FloatArray, peaks: IntArray, fs: Int): Double? {
        val pre = (0.40 * fs).toInt()
        val usable = peaks.filter { it - pre >= 0 }
        if (usable.size < 5) return null
        val template = DoubleArray(pre) { k -> median(usable.map { clean[it - pre + k].toDouble() }) }
        val baseline = median((pre - (0.08 * fs).toInt() until pre - (0.04 * fs).toInt()).map { template[it] })
        val from = pre - (0.35 * fs).toInt()
        val to = pre - (0.08 * fs).toInt()
        return (from until to).maxOf { abs(template[it] - baseline) }
    }

    private fun dist(a: Pair<Double, Double>, b: Pair<Double, Double>) = sqrt(
        (a.first - b.first).let { it * it } + (a.second - b.second).let { it * it }
    )

    /** k-means with farthest-point initialisation (deterministic), 20 iterations. */
    private fun kMeans(p: List<Pair<Double, Double>>, k: Int): List<Pair<Double, Double>> {
        val centres = mutableListOf(p.first())
        while (centres.size < k) centres += p.maxBy { pt -> centres.minOf { dist(it, pt) } }
        repeat(20) {
            val groups = p.groupBy { pt -> centres.indices.minBy { dist(centres[it], pt) } }
            for ((c, members) in groups) centres[c] = members.map { it.first }.average() to members.map { it.second }.average()
        }
        return centres
    }

    private fun median(v: List<Double>): Double {
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }
}
