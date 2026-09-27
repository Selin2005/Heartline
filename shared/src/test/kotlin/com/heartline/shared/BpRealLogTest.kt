// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared

import com.heartline.shared.bp.BpCalibration
import com.heartline.shared.bp.BpChannel
import com.heartline.shared.bp.BpEstimator
import com.heartline.shared.bp.BpOutcome
import com.heartline.shared.bp.CalibrationPoint
import com.heartline.shared.bp.HemodynamicStateClassifier
import com.heartline.shared.bp.PpgFeatureVector
import com.heartline.shared.bp.RecentRhythm
import com.heartline.shared.dsp.PpgRepair
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests on the first real session logs (Galaxy Watch8 Classic, 2026-09-27): a precise
 * (ECG) calibration of 4 rounds, then two quick readings of 112/71 ±12 and 108/65 ±17 that read
 * too high with a too wide ±. The feature values are copied from the watch's log. The cuff values
 * were not in the log; the user's usual 104/70 stands in (these tests are about which data may
 * be compared, not about the number).
 */
class BpRealLogTest {
    private fun v4(
        hr: Double,
        upstroke: Double,
        width50: Double,
        width25: Double,
        areaRatio: Double,
        ba: Double,
        da: Double,
        reflectionDelay: Double,
        reflectionIndex: Double,
        quality: Double,
        beats: Int,
        rmssd: Double,
        ibiCv: Double,
        ectopic: Int,
        rejected: Double,
        hrSlope: Double,
        pi: Double,
        amplitudeTrend: Double,
        inverted: Boolean = true
    ) = PpgFeatureVector(
        heartRateBpm = hr,
        riseFraction = upstroke * hr / 60_000,
        widthFraction = width50 * hr / 60_000,
        areaRatio = areaRatio,
        beats = beats,
        quality = quality,
        upstrokeMs = upstroke,
        width50Ms = width50,
        width25Ms = width25,
        apgBa = ba,
        apgDa = da,
        inverted = inverted,
        version = 4,
        reflectionDelayMs = reflectionDelay,
        reflectionIndex = reflectionIndex,
        rmssdMs = rmssd,
        ibiCv = ibiCv,
        ectopicCount = ectopic,
        rejectedFraction = rejected,
        hrSlopeBpmPerS = hrSlope,
        perfusionIndex = pi,
        amplitudeTrend = amplitudeTrend
    )

    /** Precise rounds: shape from the ECG tracker's PPG, PAT from ECG; raw PPG not kept (500 Hz). */
    private val round1 =
        v4(72.46, 231.5, 452.0, 616.0, 2.472, -0.5606, -0.7438, 200.5, 0.8353, 0.6982, 11, 91.62, 0.09026, 0, 0.0, -0.6194, 4.139, 0.1645)
    private val round2 =
        v4(68.49, 247.5, 594.5, 717.0, 2.800, -0.3234, -0.1689, 189.0, 0.8545, 0.8164, 11, 62.89, 0.07924, 0, 0.0, 1.473, 11.14, 0.4240)
    private val round3 =
        v4(72.12, 260.0, 575.5, 683.5, 2.367, -0.3041, -0.09136, 159.0, 0.9203, 0.9969, 11, 54.20, 0.06123, 0, 0.0, 0.4730, 0.0, 0.4254)

    /** The standing round, detected upside down at 106 bpm. */
    private val round4 = v4(
        106.0, 350.0, 273.0, 390.5, 0.3027, -0.2907, -0.7796, 130.0, 0.0, 0.9918, 17, 12.99, 0.02021, 0, 0.0, 0.3888, 12.58, -0.3386,
        inverted = false
    )

    /** Quick readings (PPG_ON_DEMAND, 100 Hz). */
    private val reading1 =
        v4(74.07, 167.5, 262.5, 525.0, 2.966, -0.84, -0.2367, 262.5, 0.4054, 0.9974, 23, 51.35, 0.05255, 0, 0.0, -0.1072, 3.408, -0.294)
    private val reading2 =
        v4(74.07, 215.0, 442.5, 592.5, 2.504, -0.5439, -0.2612, 222.5, 0.6185, 0.9025, 33, 73.36, 0.1628, 2, 0.2979, -0.04264, 3.469, 1.899)

    private val preciseCalibration = BpCalibration(
        "real",
        0,
        listOf(
            CalibrationPoint(round1, 104, 70, 72, patMs = 296.2, pepMs = 103.0, pttMs = 193.2),
            CalibrationPoint(round2, 104, 70, 68, patMs = 258.3, pepMs = 103.8, pttMs = 154.5),
            CalibrationPoint(round3, 104, 70, 72, patMs = 268.7, pepMs = 140.0, pttMs = 128.7),
            CalibrationPoint(round4, 104, 70, 106, patMs = 365.3, pepMs = 88.6, pttMs = 276.7, standing = true)
        )
    )

    @Test
    fun aPreciseCalibrationIsNeverUsedForQuickReadings() {
        // Before: 112/71 ±12, "beyond calibration" (the ECG channel's wave looked "stiffer").
        assertTrue(preciseCalibration.points.all { it.featureFs == CalibrationPoint.PRECISE_FS })
        assertFalse("quick readings need quick rounds", preciseCalibration.isValid(1_000))
        assertEquals(BpOutcome.NeedsCalibration, BpEstimator.estimate(preciseCalibration, reading1, 1_000))
    }

    @Test
    fun breathingRelatedPulseVariationIsNotAnIrregularRhythm() {
        // Before: ±17, weighed as atrial fibrillation (interval CV 0.16 > 0.15).
        assertFalse(HemodynamicStateClassifier.irregular(reading2))
        assertFalse(HemodynamicStateClassifier.irregular(reading2, recentSinus = true))
        // The same variation, beat-to-beat random (as in AF), is irregular.
        assertTrue(HemodynamicStateClassifier.irregular(reading2.copy(rmssdMs = 130.0)))
    }

    @Test
    fun theUpsideDownStandingRoundAndPolarityOutliersStayOutOfTheShapeFit() {
        // The same rounds as if they had been quick rounds.
        val quick = BpCalibration("q", 0, preciseCalibration.points.map { it.copy(patMs = null, ppg = List(10) { 0f }) })
        val select = BpEstimator.shapeSelector(quick, BpChannel.PWA_GREEN, BpCalibration.PPG_FS)
        val used = quick.points.mapNotNull(select)
        assertEquals(3, used.size)
        assertTrue(used.all { it.inverted })
        // A seated round detected upside down is left out too.
        val flipped = quick.copy(points = quick.points.take(3) + quick.points[0].copy(features = round1.copy(inverted = false)))
        assertEquals(3, flipped.points.mapNotNull(BpEstimator.shapeSelector(flipped, BpChannel.PWA_GREEN, BpCalibration.PPG_FS)).size)
    }

    @Test
    fun theEcgTrackersPpgIsRepaired() {
        // The start of round 1's PPG from the ECG tracker: one value in five, the rest -1, and a
        // gain switch from about 240 400 to about -39 700 counts.
        val real = floatArrayOf(
            240253f, 240230f, 240257f, 240221f, 240274f, 240272f, 240286f, 240304f, 240197f, 240177f,
            240212f, 240315f, 240274f, 240208f, 240204f, 240261f, 240308f, 240289f, 240344f, 240380f,
            240420f, 240458f, 240420f, -39745f, -39713f, -39752f, -39712f, -39709f, -39735f, -39666f
        )
        val raw = FloatArray(real.size * 5) { if (it % 5 == 0) real[it / 5] else PpgRepair.PLACEHOLDER }
        assertEquals(0.2, PpgRepair.validShare(raw), 1e-9)
        val fixed = PpgRepair.repair(raw)
        assertNotNull(fixed)
        fixed!!
        assertTrue(fixed.none { it == PpgRepair.PLACEHOLDER || it.isNaN() })
        // No sample-to-sample change is anywhere near the 280 000-count gain jump any more.
        assertTrue(fixed.toList().zipWithNext().all { (a, b) -> abs(b - a) < 200 })
        // A signal with too few real values is not a signal.
        assertNull(PpgRepair.repair(FloatArray(100) { if (it % 10 == 0) 1f else PpgRepair.PLACEHOLDER }))
    }

    @Test
    fun aRecentSinusEcgMakesTheIrregularCallStricter() {
        // Moderately random variation: irregular without an ECG, not with a recent sinus ECG.
        val borderline = reading2.copy(ectopicCount = 0, rrCount = 30, rrNRmssd = 0.12, rrEntropy = 0.7, rrTurningPoint = 0.7)
        assertTrue(HemodynamicStateClassifier.irregular(borderline))
        assertFalse(HemodynamicStateClassifier.irregular(borderline, recentSinus = true))
        assertEquals(RecentRhythm.SINUS, RecentRhythm.valueOf("SINUS"))
    }
}
