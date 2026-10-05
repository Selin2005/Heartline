// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared

import com.heartline.shared.bp.BpCalibration
import com.heartline.shared.bp.BpChannel
import com.heartline.shared.bp.BpEstimate
import com.heartline.shared.bp.BpEstimator
import com.heartline.shared.bp.BpOutcome
import com.heartline.shared.bp.BpPipeline
import com.heartline.shared.bp.CalibrationPoint
import com.heartline.shared.bp.HybridBpModel
import com.heartline.shared.bp.PpgEmbedder
import com.heartline.shared.bp.PpgFeatures
import com.heartline.shared.bp.TransitEstimator
import com.heartline.shared.sample.SyntheticPpg
import com.heartline.shared.sample.SyntheticSession
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Algorithm 6.5: the open points found while writing the complete specification
 * (docs/algorithms/BP_ALGORITHM.md, §15.4), each fixed and measured with bpEval.
 */
class BpAlgorithm65Test {
    private val upright = PpgFeatures.extract(SyntheticPpg.generate(25.0, 72.0, 0.6, seed = 1), 100)!!.inverted

    private fun round(seed: Int, sys: Int, dia: Int): CalibrationPoint {
        val raw = SyntheticPpg.generate(25.0, 72.0, 0.6, seed = seed)
        return CalibrationPoint(PpgFeatures.extract(raw, 100, upright)!!, sys, dia, 72, raw.toList())
    }

    @Test
    fun theTransitNoiseCountsTheRoundsMisfitOnce() {
        // Rounds that scatter well beyond the 5 ms floor.
        val base = round(1, 140, 80)
        val cal = BpCalibration(
            "c",
            0,
            listOf(
                base.copy(bcgPttMs = 150.0, cuffSystolic = 136),
                base.copy(bcgPttMs = 180.0, cuffSystolic = 128),
                base.copy(bcgPttMs = 120.0, cuffSystolic = 151),
                base.copy(bcgPttMs = 165.0, cuffSystolic = 146)
            )
        )
        val c = TransitEstimator.estimate(cal, BpChannel.BCG_PTT, 155.0, 1_000)
        assertNotNull(c)
        val residual = c!!.parts.getValue("residual")
        val spread = c.parts.getValue("transitSpreadMs")
        val slope = c.parts.getValue("slope")
        assertTrue("spread $spread", spread > TransitEstimator.MIN_TRANSIT_NOISE_MS)
        // The slope times the spread is the misfit itself…
        assertEquals(residual, abs(slope) * spread, 1e-6)
        // …and the noise holds it once: base² + misfit² (no drift at day 0).
        assertEquals(sqrt(6.0.pow(2) + residual.pow(2)), c.noiseSys, 1e-6)
    }

    @Test
    fun theCoupledDiastolicBaselineMovesAlongTheSlopesItsChangeUses() {
        // An older calibration and two recent cuff checks at a higher pressure.
        val day = BpCalibration.DAY_MS
        val cal = BpCalibration(
            "c",
            0,
            listOf(round(1, 130, 78), round(2, 132, 79), round(3, 131, 78)),
            extraPoints = listOf(round(4, 150, 92).copy(atMs = 10 * day), round(5, 148, 90).copy(atMs = 10 * day + 60_000))
        )
        val now = 10 * day + 120_000
        val m = BpEstimator.fit(cal, now)
        val timed = cal.timedPoints()
        val x = timed.map { BpEstimator.corrected(it.first.features) }
        val anchor = timed.map { (_, at) -> maxOf(0.05, 0.5.pow((now - at) / day.toDouble() / 5.0)) }
        val expected = timed.indices.sumOf { i ->
            val moved = x[i].indices.sumOf { j -> m.diaRatio * m.sysWeights[j] * (x[i][j] - m.refFeatures[j]) }
            anchor[i] * (timed[i].first.cuffDiastolic - moved)
        } / anchor.sum()
        assertEquals(expected, m.refDia, 1e-9)
        // A reading identical to the reference sits on the diastolic baseline, coupled to the systolic.
        val f = cal.extraPoints.last().features
        val e = (BpEstimator.estimate(cal, f, now) as BpOutcome.Ok).estimate
        assertEquals(m.refDia + m.diaRatio * e.deltaSystolic, e.channels.single().diastolic, 1e-9)
    }

    @Test
    fun aPlusMinusTooWideForACategoryIsMarked() {
        assertFalse(BpEstimate(140, 85, 70, uncertaintySys = BpEstimator.RANGE_ONLY_SD).wideRange)
        assertTrue(BpEstimate(140, 85, 70, uncertaintySys = BpEstimator.RANGE_ONLY_SD + 1).wideRange)
    }

    @Test
    fun theChangeIsMeasuredFromTheModelsOwnReference() {
        val spec = SyntheticSession.Spec(systolic = 139.0, diastolic = 79.0, refSystolic = 139.0, heartRateStart = 80.0)
        val day = BpCalibration.DAY_MS
        // Old rounds near 130, a recent cuff check at 150: the plain mean (≈ 135) and the anchored
        // reference (near the recent check) differ by far more than the "no direction" band.
        val cal = BpCalibration(
            "c",
            0,
            (1..3).map { i ->
                CalibrationPoint.of(BpPipeline.capture(SyntheticSession.generate(spec.copy(seed = i)).input)!!, 130, 78, 80)
            },
            extraPoints = listOf(
                CalibrationPoint.of(
                    BpPipeline.capture(SyntheticSession.generate(spec.copy(seed = 4)).input)!!,
                    150,
                    88,
                    80,
                    atMs = 20 * day
                )
            )
        )
        val now = 20 * day + 60_000
        val e = (BpPipeline.run(cal, SyntheticSession.generate(spec.copy(seed = 30)).input, now).outcome as BpOutcome.Ok).estimate
        val m = BpEstimator.fit(cal, now, select = BpEstimator.shapeSelector(cal, BpChannel.PWA_GREEN, 100))
        val plainMean = cal.timedPoints().map { it.first.cuffSystolic }.average()
        assertTrue("reference ${m.refSys}, plain mean $plainMean", abs(m.refSys - plainMean) > 5)
        // The fused value (shown rounded) minus the anchored reference, not minus the plain mean.
        assertEquals(e.systolic - m.refSys, e.deltaSystolic, 0.5 + 1e-9)
    }

    @Test
    fun theLearnedCorrectionIsRoundedNotTruncated() {
        val f = PpgFeatures.extract(SyntheticPpg.generate(25.0, 72.0, 0.6, seed = 1), 100)!!
        // Every check needs +3 mmHg systolic and −3 diastolic, whatever the wave.
        val samples = (1..14).map { i ->
            val g = PpgFeatures.extract(SyntheticPpg.generate(25.0, 66.0 + i, 0.6, seed = i), 100)!!
            HybridBpModel.Sample(g, null, 140, 85, 143, 82)
        }
        val model = HybridBpModel(PpgEmbedder { _, _, features -> doubleArrayOf(features.heartRateBpm / 60.0) }).train(samples)
        assertNotNull(model)
        val (sys, dia) = model!!.correct(BpEstimate(140, 85, 72), f, null)!!
        // The bias is shrunk to 3·14/(14 + λ) ≈ 2.94 at λ = 0.3: rounding gives +3 / −3 (truncation gave +2 / −2).
        assertEquals(143, sys)
        assertEquals(82, dia)
    }
}
