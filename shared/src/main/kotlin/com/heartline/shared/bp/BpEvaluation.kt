// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.bp

import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.serialization.Serializable

/** One watch reading (its raw PPG) with the cuff reading taken right after it. */
@Serializable
data class BpDatasetEntry(
    val atMs: Long,
    val ppg: List<Float>,
    val cuffSystolic: Int,
    val cuffDiastolic: Int,
    /** What the watch showed at the time (for reference), if anything. */
    val watchSystolic: Int? = null,
    val watchDiastolic: Int? = null,
    /** The reading's raw session log (every sensor) in the same export, if the watch recorded one. */
    val sessionId: String? = null
)

/**
 * A user's BP data as exported from the phone (Settings → Export BP data): the calibration with
 * its raw PPG, and every cuff-checked reading with its raw PPG. Algorithms are replayed on it
 * offline by [BpEvaluation] and tools/bp-ml.
 */
@Serializable
data class BpDataset(
    val format: Int = FORMAT,
    val sampleRateHz: Int = BpCalibration.PPG_FS,
    val calibration: BpCalibration,
    val entries: List<BpDatasetEntry>
) {
    companion object {
        const val FORMAT = 1
    }
}

/**
 * Agreement report in the terms used by cuffless validation studies:
 * - mean difference ± SD (ISO 81060-2 asks ≤ 5 ± 8 mmHg across many people),
 * - MAE and the share within 10 mmHg,
 * - the proportional-bias slope of (watch − cuff) against cuff: 0 is ideal; a negative slope
 *   means readings are pulled towards the calibration (Falter et al. 2022 found this for
 *   Galaxy Watch). Relating the difference to the cuff, not the mean, keeps it interpretable.
 * - [refused]: readings the algorithm gave no number for; [unsteady]: readings taken while the
 *   body was not in a steady state (algorithm 6 still gives a number, weighed for the state).
 * - [maeSysFastPulse]: MAE of readings whose pulse was more than [FAST_PULSE_RISE] bpm above the
 *   calibration's (where the old model read high); null without such readings.
 */
data class BpEvaluationReport(
    val count: Int,
    val refused: Int,
    val meanDiffSys: Double,
    val sdSys: Double,
    val maeSys: Double,
    val meanDiffDia: Double,
    val sdDia: Double,
    val maeDia: Double,
    val within10Percent: Int,
    val biasSlopeSys: Double,
    val beyondCalibration: Int,
    val unsteady: Int = 0,
    val maeSysFastPulse: Double? = null
) {
    companion object {
        const val FAST_PULSE_RISE = 15.0
    }
}

object BpEvaluation {
    /** A replayable algorithm: calibration known before the reading, the reading's features, its time. */
    fun interface Algorithm {
        fun estimate(calibration: BpCalibration, features: PpgFeatureVector?, atMs: Long): BpOutcome
    }

    val current = Algorithm { cal, f, at -> BpEstimator.estimate(cal, f, at) }

    /** The full algorithm-6 pipeline on a raw recording (window selection, state, fusion). */
    fun interface RawAlgorithm {
        fun estimate(calibration: BpCalibration, ppg: FloatArray, fs: Int, atMs: Long): BpOutcome
    }

    val pipeline = RawAlgorithm { cal, ppg, fs, at -> BpPipeline.run(cal, BpSessionInput(ppg, fs), at).outcome }

    /**
     * Replays [algorithm] on every entry. With [incremental], each earlier cuff check becomes a
     * calibration point for the later readings (as the app does); the entry being evaluated is
     * never part of its own calibration.
     */
    fun evaluate(dataset: BpDataset, algorithm: Algorithm = current, incremental: Boolean = true): BpEvaluationReport =
        evaluateRaw(dataset, { cal, ppg, fs, at -> algorithm.estimate(cal, PpgFeatures.extract(ppg, fs), at) }, incremental)

    /** Like [evaluate], with an algorithm that takes the raw recording ([pipeline]). */
    fun evaluateRaw(dataset: BpDataset, algorithm: RawAlgorithm = pipeline, incremental: Boolean = true): BpEvaluationReport {
        val fs = dataset.sampleRateHz
        val base = dataset.calibration.upgraded(fs).copy(extraPoints = emptyList())
        val sorted = dataset.entries.sortedBy { it.atMs }
        val pairs = mutableListOf<Pair<BpEstimate, BpDatasetEntry>>()
        var refused = 0
        val fastPulse = mutableSetOf<BpDatasetEntry>()
        var cal = base
        for (entry in sorted) {
            val raw = entry.ppg.toFloatArray()
            val features = PpgFeatures.extract(raw, fs)
            when (val out = algorithm.estimate(cal, raw, fs, entry.atMs)) {
                is BpOutcome.Ok -> {
                    pairs += out.estimate to entry
                    if (out.estimate.pulse - cal.referenceHeartRate() > BpEvaluationReport.FAST_PULSE_RISE) fastPulse += entry
                }
                else -> refused++
            }
            if (incremental && features != null) {
                cal = cal.withExtraPoint(CalibrationPoint(features, entry.cuffSystolic, entry.cuffDiastolic, null, null, entry.atMs))
            }
        }
        val fast = pairs.filter { it.second in fastPulse }.map { (e, c) -> abs(e.systolic - c.cuffSystolic).toDouble() }
        return report(pairs, refused).copy(
            unsteady = pairs.count {
                !it.first.state.steady
            },
            maeSysFastPulse = fast.takeIf { it.isNotEmpty() }?.average()
        )
    }

    internal fun report(pairs: List<Pair<BpEstimate, BpDatasetEntry>>, refused: Int): BpEvaluationReport {
        fun stats(d: List<Double>): Pair<Double, Double> {
            if (d.isEmpty()) return 0.0 to 0.0
            val m = d.average()
            return m to if (d.size < 2) 0.0 else sqrt(d.sumOf { (it - m) * (it - m) } / (d.size - 1))
        }
        val ds = pairs.map { (e, c) -> (e.systolic - c.cuffSystolic).toDouble() }
        val dd = pairs.map { (e, c) -> (e.diastolic - c.cuffDiastolic).toDouble() }
        val (ms, ss) = stats(ds)
        val (md, sd) = stats(dd)
        val cuff = pairs.map { it.second.cuffSystolic.toDouble() }
        val slope = if (cuff.size < 3) {
            0.0
        } else {
            val cm = cuff.average()
            val varC = cuff.sumOf { (it - cm) * (it - cm) }
            if (varC <= 1e-9) 0.0 else cuff.indices.sumOf { (cuff[it] - cm) * (ds[it] - ms) } / varC
        }
        val within = pairs.count { (e, c) -> abs(e.systolic - c.cuffSystolic) <= 10 && abs(e.diastolic - c.cuffDiastolic) <= 10 }
        return BpEvaluationReport(
            count = pairs.size,
            refused = refused,
            meanDiffSys = ms,
            sdSys = ss,
            maeSys = ds.map { abs(it) }.average().takeIf { !it.isNaN() } ?: 0.0,
            meanDiffDia = md,
            sdDia = sd,
            maeDia = dd.map { abs(it) }.average().takeIf { !it.isNaN() } ?: 0.0,
            within10Percent = if (pairs.isEmpty()) 0 else within * 100 / pairs.size,
            biasSlopeSys = slope,
            beyondCalibration = pairs.count { it.first.beyondCalibration }
        )
    }
}
