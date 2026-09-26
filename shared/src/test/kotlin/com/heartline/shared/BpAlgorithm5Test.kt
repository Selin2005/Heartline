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
import com.heartline.shared.bp.BpPipeline
import com.heartline.shared.bp.BpProfile
import com.heartline.shared.bp.BpSafety
import com.heartline.shared.bp.BpSessionInput
import com.heartline.shared.bp.CalibrationPoint
import com.heartline.shared.bp.HemodynamicState
import com.heartline.shared.bp.PpgFeatureVector
import com.heartline.shared.bp.PpgFeatures
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

    private fun measure(s: Scenario, cal: BpCalibration = calibration()) = BpPipeline.run(cal, BpSessionInput(raw(s), fs), 1_000).outcome

    private fun ok(out: BpOutcome) = (out as? BpOutcome.Ok)?.estimate ?: error("no number: $out")

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
    fun bathroomEpisodeWithOnlyGreenPpgIsNotReadAsHighPressure() {
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
        // Algorithm 6: always a number. With only the green PPG the shape change is mostly
        // vasoconstriction, so little of it is trusted and the ± shows the doubt (the old model read 147/93).
        val e = ok(measure(episode))
        assertEquals(HemodynamicState.COMPENSATORY, e.state.state)
        assertTrue("$e", e.systolic <= 115)
        assertTrue("$e", e.uncertaintySys >= 8)
    }

    @Test
    fun compensatingResponseStillGivesANumber() {
        val e = ok(measure(Scenario(heartRateStart = 118.0, stiffness = 0.5, perfusionIndex = 0.5, seed = 12)))
        assertEquals(HemodynamicState.COMPENSATORY, e.state.state)
        assertTrue("$e", e.systolic <= 115)
    }

    @Test
    fun steadyFastPulseMovesTheEstimateOnlyALittle() {
        // Same arteries, pulse 40 bpm faster (fever, anaemia, a stimulant): the old linear rate
        // term alone added ≈ 18 mmHg here and the narrower wave added more.
        val e = (measure(Scenario(heartRateStart = 110.0, stiffness = 0.3, seed = 13)) as BpOutcome.Ok).estimate
        assertTrue("$e", abs(e.systolic - 104) <= 12)
        // Mostly rate-driven: flagged for a second reading.
        assertTrue("$e", e.heartRateDominated && e.beyondCalibration)
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
    fun aChangingPulseStillGivesANumberWithItsState() {
        val e = ok(measure(Scenario(heartRateStart = 70.0, heartRateEnd = 98.0, stiffness = 0.3, seed = 15)))
        assertEquals(HemodynamicState.TRANSIENT, e.state.state)
        assertTrue("$e", abs(e.systolic - 104) <= 12)
    }

    @Test
    fun irregularRhythmGivesAWiderNumber() {
        val e = ok(measure(Scenario(seconds = 45.0, heartRateStart = 85.0, irregular = 0.3, stiffness = 0.3, seed = 16)))
        assertEquals(HemodynamicState.IRREGULAR, e.state.state)
        assertTrue("$e", e.uncertaintySys >= 8)
        assertTrue("$e", abs(e.systolic - 104) <= 15)
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
        // Frequent premature beats: still a number, weighed as an irregular rhythm.
        val many = ok(measure(Scenario(heartRateStart = 70.0, stiffness = 0.3, ectopicBeats = setOf(4, 9, 14, 19), seed = 19)))
        assertEquals(HemodynamicState.IRREGULAR, many.state.state)
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
    fun aRateDrivenReadingIsNeverVeryHigh() {
        val e = BpEstimate(186, 104, 125, 14, 10, beyondCalibration = true, heartRateDominated = true)
        assertEquals(BpSafety.NONE, e.safety)
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
        val report = BpEvaluation.evaluateRaw(BpDataset(calibration = cal, entries = entries), incremental = false)
        // Both get a number; the compensating one is counted as taken in an unsteady state.
        assertEquals(2, report.count)
        assertEquals(1, report.unsteady)
        assertNotNull(report.maeSysFastPulse)
    }
}
