// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared

import com.heartline.shared.bp.BpCalibration
import com.heartline.shared.bp.BpDataset
import com.heartline.shared.bp.BpDatasetEntry
import com.heartline.shared.bp.BpEstimate
import com.heartline.shared.bp.BpEstimator
import com.heartline.shared.bp.BpEvaluation
import com.heartline.shared.bp.BpOutcome
import com.heartline.shared.bp.BpProfile
import com.heartline.shared.bp.BpSafety
import com.heartline.shared.bp.CalibrationPoint
import com.heartline.shared.bp.MeasurementContext
import com.heartline.shared.bp.PpgFeatureVector
import com.heartline.shared.bp.PpgFeatures
import com.heartline.shared.bp.UnsteadyReason
import com.heartline.shared.sample.SyntheticPpg
import com.heartline.shared.sample.SyntheticPpg.Scenario
import com.heartline.shared.sync.Protocol
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Algorithm 5: the reading must follow the body's state, not just the pulse shape. The central
 * case is the one a user reported: after the toilet, dizzy and short of breath, pulse 120, the
 * watch showed 147/93 while the real pressure was at or below the usual 104/70.
 */
class BpAlgorithm5Test {
    private val fs = SyntheticPpg.SAMPLE_RATE_HZ
    private val atRest = listOf(0.0, 0.0, 9.8)

    private fun raw(s: Scenario) = SyntheticPpg.scenario(s)

    private fun features(s: Scenario): PpgFeatureVector = PpgFeatures.extract(raw(s), fs) ?: error("no features for $s")

    /** A user with a low-normal baseline, 104/70 at a resting pulse of about 70. */
    private fun calibration(profile: BpProfile = BpProfile.NONE) = BpCalibration(
        "c",
        0,
        listOf(
            CalibrationPoint(features(Scenario(heartRateStart = 70.0, stiffness = 0.3, seed = 1)), 104, 70, 70, gravity = atRest),
            CalibrationPoint(features(Scenario(heartRateStart = 72.0, stiffness = 0.32, seed = 2)), 106, 71, 72, gravity = atRest),
            CalibrationPoint(features(Scenario(heartRateStart = 68.0, stiffness = 0.28, seed = 3)), 102, 69, 68, gravity = atRest)
        ),
        profile = profile
    )

    private fun measure(s: Scenario, cal: BpCalibration = calibration(), context: MeasurementContext = MeasurementContext(atRest)) =
        BpEstimator.estimateRecording(cal, raw(s), fs, 1_000, context = context).second

    @Test
    fun extractorMeasuresRhythmAndPerfusion() {
        val f = features(Scenario(heartRateStart = 70.0, perfusionIndex = 1.2, seed = 4))
        assertEquals(PpgFeatureVector.VERSION, f.version)
        assertTrue("${f.perfusionIndex}", f.perfusionIndex in 0.6..2.5)
        assertTrue("${f.ibiCv}", f.ibiCv < 0.05)
        assertTrue("${f.hrSlopeBpmPerS}", abs(f.hrSlopeBpmPerS) < 0.2)
        assertEquals(0, f.ectopicCount)
        // A pulse speeding up over the recording.
        val ramp = features(Scenario(heartRateStart = 70.0, heartRateEnd = 100.0, seed = 5))
        assertTrue("${ramp.hrSlopeBpmPerS}", ramp.hrSlopeBpmPerS > 0.8)
        // Upright synthetic PPG without an offset has no perfusion index.
        assertEquals(0.0, PpgFeatures.extract(SyntheticPpg.generate(20.0), fs)!!.perfusionIndex, 0.0)
    }

    @Test
    fun bathroomEpisodeIsNeverReadAsHighPressure() {
        // Racing pulse settling from 125 to 110, weak and recovering wrist pulse (vasoconstriction).
        val episode = Scenario(
            heartRateStart = 125.0,
            heartRateEnd = 110.0,
            stiffness = 0.55,
            amplitudeStart = 0.8,
            amplitudeEnd = 1.2,
            perfusionIndex = 0.45,
            seed = 11
        )
        val out = measure(episode)
        assertTrue("$out", out is BpOutcome.Unsteady)
        out as BpOutcome.Unsteady
        assertEquals(UnsteadyReason.COMPENSATORY_RESPONSE, out.reason)
        assertTrue("a drop in pressure fits this pattern", out.lowPressureSuspected)
    }

    @Test
    fun compensatingResponseWithoutATrendIsStillNotANumber() {
        val out = measure(Scenario(heartRateStart = 118.0, stiffness = 0.5, perfusionIndex = 0.5, seed = 12))
        assertEquals(BpOutcome.Unsteady(UnsteadyReason.COMPENSATORY_RESPONSE, false), out)
    }

    @Test
    fun steadyFastPulseMovesTheEstimateOnlyALittle() {
        // Same arteries, pulse 40 bpm faster (fever, anaemia, a stimulant): the old linear rate
        // term alone added ≈ 18 mmHg here and the narrower wave added more.
        val e = (measure(Scenario(heartRateStart = 110.0, stiffness = 0.3, seed = 13)) as BpOutcome.Ok).estimate
        assertTrue("$e", abs(e.systolic - 104) <= 12)
        // Mostly rate-driven: a range without a category, flagged for a second reading.
        assertTrue("$e", e.rangeOnly && e.beyondCalibration)
    }

    @Test
    fun aRealRiseInPressureIsStillShown() {
        // Stiffer, faster-returning wave at an unchanged pulse: pressure really went up.
        val stiff = Scenario(heartRateStart = 72.0, stiffness = 0.85, seed = 14)
        val base = (measure(Scenario(heartRateStart = 72.0, stiffness = 0.3, seed = 24)) as BpOutcome.Ok).estimate
        val e = (measure(stiff) as BpOutcome.Ok).estimate
        assertTrue("base=$base stiff=$e", e.systolic > base.systolic)
        assertFalse("$e", e.heartRateDominated)
        // Once cuff checks have shown this user's pressure follows the shape, the rise is tracked in full.
        val checked = calibration()
            .withExtraPoint(
                CalibrationPoint(features(Scenario(heartRateStart = 71.0, stiffness = 0.6, seed = 25)), 124, 80, 71, atMs = 500)
            )
            .withExtraPoint(
                CalibrationPoint(features(Scenario(heartRateStart = 73.0, stiffness = 0.62, seed = 26)), 126, 81, 73, atMs = 600)
            )
        val tracked = (measure(stiff, checked) as BpOutcome.Ok).estimate
        assertTrue("$tracked", tracked.systolic >= 124)
        assertFalse("$tracked", tracked.heartRateDominated)
    }

    @Test
    fun aChangingPulseMeansMeasureAgainLater() {
        val out = measure(Scenario(heartRateStart = 70.0, heartRateEnd = 98.0, seed = 15))
        assertEquals(BpOutcome.Unsteady(UnsteadyReason.HEART_RATE_CHANGING, false), out)
    }

    @Test
    fun irregularRhythmGivesNoNumber() {
        val out = measure(Scenario(heartRateStart = 85.0, irregular = 0.3, seed = 16))
        assertEquals(BpOutcome.Unsteady(UnsteadyReason.IRREGULAR_RHYTHM), out)
    }

    @Test
    fun withKnownAtrialFibrillationALongerRecordingGivesAWiderReading() {
        val cal = calibration(BpProfile(atrialFibrillation = true))
        val s = Scenario(seconds = 45.0, heartRateStart = 75.0, irregular = 0.15, stiffness = 0.3, seed = 17)
        val out = measure(s, cal)
        val e = (out as? BpOutcome.Ok)?.estimate ?: error("$out")
        assertTrue("$e", abs(e.systolic - 104) <= 12)
        assertTrue("$e", e.uncertaintySys >= 7)
    }

    @Test
    fun aPrematureBeatIsLeftOutAndDoesNotMoveTheReading() {
        val clean = features(Scenario(heartRateStart = 70.0, stiffness = 0.3, seed = 18))
        val withPvc = features(Scenario(heartRateStart = 70.0, stiffness = 0.3, ectopicBeats = setOf(9), seed = 18))
        assertEquals(1, withPvc.ectopicCount)
        assertTrue(withPvc.beats < clean.beats)
        val a = (BpEstimator.estimate(calibration(), clean, 1_000) as BpOutcome.Ok).estimate
        val b = (BpEstimator.estimate(calibration(), withPvc, 1_000) as BpOutcome.Ok).estimate
        assertTrue("clean=$a pvc=$b", abs(a.systolic - b.systolic) <= 3)
        assertEquals(1, b.ectopicBeats)
        // Frequent premature beats: no number.
        val many = measure(Scenario(heartRateStart = 70.0, ectopicBeats = setOf(4, 9, 14, 19), seed = 19))
        assertEquals(BpOutcome.Unsteady(UnsteadyReason.IRREGULAR_RHYTHM), many)
    }

    @Test
    fun rateSettingMedicineRemovesTheRateFromTheModel() {
        val f = features(Scenario(heartRateStart = 92.0, stiffness = 0.3, seed = 20))
        val plain = (BpEstimator.estimate(calibration(), f, 1_000) as BpOutcome.Ok).estimate
        val blocked = (BpEstimator.estimate(calibration(BpProfile(betaBlocker = true)), f, 1_000) as BpOutcome.Ok).estimate
        assertTrue("plain=$plain blocked=$blocked", blocked.systolic <= plain.systolic)
        assertFalse(blocked.heartRateDominated)
        assertTrue("$blocked", abs(blocked.systolic - 104) <= 8)
    }

    @Test
    fun armFarFromEveryCalibrationPositionIsNotMeasured() {
        val s = Scenario(heartRateStart = 70.0, stiffness = 0.3, seed = 21)
        assertTrue(measure(s) is BpOutcome.Ok)
        val hanging = MeasurementContext(listOf(0.0, 9.8, 0.0))
        assertEquals(BpOutcome.Unsteady(UnsteadyReason.ARM_POSITION), measure(s, context = hanging))
        // Unknown position: measured as before.
        assertTrue(measure(s, context = MeasurementContext.NONE) is BpOutcome.Ok)
    }

    @Test
    fun aRateDrivenReadingIsARangeAndNeverVeryHigh() {
        val e = BpEstimate(186, 104, 125, 14, 10, beyondCalibration = true, heartRateDominated = true, rangeOnly = true)
        assertEquals(BpSafety.NONE, e.safety)
        assertEquals(172..200, e.systolicRange)
        assertEquals(BpSafety.VERY_HIGH, e.copy(heartRateDominated = false).safety)
        assertEquals(BpSafety.LOW, BpEstimate(84, 55, 120, heartRateDominated = true).safety)
    }

    @Test
    fun profileChangesValidityAndIsCarriedWithTheCalibration() {
        val cal = calibration(BpProfile(diabetesOrKidney = true, pregnancy = true))
        assertEquals(14 * BpCalibration.DAY_MS, cal.validUntilMs)
        val json = Protocol.json.encodeToString(BpCalibration.serializer(), cal)
        assertEquals(cal, Protocol.json.decodeFromString(BpCalibration.serializer(), json))
        val e = (measure(Scenario(heartRateStart = 70.0, stiffness = 0.3, seed = 22), cal) as BpOutcome.Ok).estimate
        assertTrue(e.notValidated)
        // A calibration saved before algorithm 5 still decodes, with no profile.
        val old = json.replace(Regex(""","profile":\{[^}]*\}"""), "")
        assertEquals(BpProfile.NONE, Protocol.json.decodeFromString(BpCalibration.serializer(), old).profile)
    }

    @Test
    fun olderCalibrationsGainTheStateFeaturesFromTheirRawPpg() {
        val signals = (1..3).map { raw(Scenario(heartRateStart = 70.0, stiffness = 0.3, seed = 30 + it)) }
        val v3 = BpCalibration(
            "c",
            0,
            signals.map { r ->
                val f = PpgFeatures.extract(r, fs)!!
                CalibrationPoint(f.copy(version = 3, perfusionIndex = 0.0, ibiCv = 0.0), 104, 70, 70, r.toList())
            }
        )
        assertEquals(0.0, v3.referencePerfusionIndex(), 0.0)
        val up = v3.upgraded()
        assertTrue(up.points.all { it.features.version == PpgFeatureVector.VERSION })
        assertTrue(up.referencePerfusionIndex() > 0)
    }

    @Test
    fun evaluationCountsUnsteadyReadingsSeparately() {
        val cal = calibration()
        val entries = listOf(
            BpDatasetEntry(1_000, raw(Scenario(heartRateStart = 70.0, stiffness = 0.3, seed = 40)).toList(), 104, 70),
            BpDatasetEntry(2_000, raw(Scenario(heartRateStart = 118.0, perfusionIndex = 0.5, seed = 41)).toList(), 96, 64)
        )
        val report = BpEvaluation.evaluate(BpDataset(calibration = cal, entries = entries), incremental = false)
        assertEquals(1, report.count)
        assertEquals(1, report.unsteady)
        assertNotNull(report)
    }
}
