// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared

import com.heartline.shared.bp.BpCalibration
import com.heartline.shared.bp.BpChannel
import com.heartline.shared.bp.BpEstimator
import com.heartline.shared.bp.BpFusion
import com.heartline.shared.bp.BpOutcome
import com.heartline.shared.bp.BpPipeline
import com.heartline.shared.bp.BpSessionInput
import com.heartline.shared.bp.CalibrationPoint
import com.heartline.shared.bp.ChannelEstimate
import com.heartline.shared.bp.HemodynamicState
import com.heartline.shared.bp.PpgFeatures
import com.heartline.shared.bp.TransitEstimator
import com.heartline.shared.bp.WristBcg
import com.heartline.shared.sample.SyntheticPpg
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Algorithm 6.2, from a second real user (Galaxy Watch6 Classic, treated hypertension): with a
 * cuff of 140/80 the watch said 110/53, and later measurements kept ending in "calibrate again".
 * The Watch6's raw green PPG has no clear light-intensity offset, so which way up the wave is was
 * decided anew by a slope heuristic in every recording.
 */
class BpAlgorithm62Test {
    /** A Watch6-style recording: the pulse without a large light-intensity offset. */
    private fun wave(seed: Int, seconds: Double = 25.0) = SyntheticPpg.generate(seconds, 72.0, 0.6, seed = seed)

    private val upright = PpgFeatures.extract(wave(1), 100)!!.inverted

    private fun round(seed: Int, polarity: Boolean = upright, keepRaw: Boolean = true): CalibrationPoint {
        val raw = wave(seed)
        return CalibrationPoint(PpgFeatures.extract(raw, 100, polarity)!!, 140, 80, 72, raw.toList().takeIf { keepRaw })
    }

    private fun calibration(vararg points: CalibrationPoint) = BpCalibration("c", 0, points.toList())

    @Test
    fun aWaveReadUpsideDownIsANeverComparableShape() {
        val raw = wave(9)
        val right = PpgFeatures.extract(raw, 100, upright)!!
        val wrong = PpgFeatures.extract(raw, 100, !upright)!!
        val cal = calibration(round(1), round(2), round(3))
        val good = BpEstimator.estimate(cal, right, 1_000)
        val bad = BpEstimator.estimate(cal, wrong, 1_000)
        assertTrue("$good", good is BpOutcome.Ok && abs(good.estimate.systolic - 140) <= 5)
        // Before 6.2 nothing stopped this comparison: calibrated at 140/80, the same pulse read
        // upside down came out about 116/66 (the user saw 110/53 against a cuff of 140/80).
        assertTrue("$bad", bad is BpOutcome.Ok && bad.estimate.systolic <= 125)
    }

    @Test
    fun theMeasurementIsReadTheCalibrationsWayUp() {
        // Whatever the slope heuristic would say, the pipeline reads the wave as the calibration did.
        for (polarity in listOf(true, false)) {
            val cal = calibration(round(1, polarity), round(2, polarity), round(3, polarity))
            val result = BpPipeline.run(cal, BpSessionInput(wave(9), 100), 1_000)
            assertEquals(polarity, result.features?.inverted)
            val outcome = result.outcome
            assertTrue("$outcome", outcome is BpOutcome.Ok && abs(outcome.estimate.systolic - 140) <= 5)
        }
    }

    @Test
    fun aRoundReadUpsideDownIsReadAgainNotLost() {
        val cal = calibration(round(1), round(2), round(3, !upright))
        assertTrue(cal.isValid(1_000))
        val aligned = cal.aligned()
        assertTrue(aligned.points.all { it.features.inverted == upright })
        // Before 6.2 the round was left out, 2 were left and the watch asked to calibrate again.
        val outcome = BpPipeline.run(cal, BpSessionInput(wave(9), 100), 1_000).outcome
        assertTrue("$outcome", outcome is BpOutcome.Ok && abs(outcome.estimate.systolic - 140) <= 5)
    }

    @Test
    fun withoutItsRawWaveAnUpsideDownRoundMakesTheCalibrationInvalidOnPhoneAndWatchAlike() {
        val cal = calibration(round(1), round(2), round(3, !upright, keepRaw = false))
        // The phone's status and the watch's check are the same function, and agree with the estimator.
        assertFalse(cal.isValid(1_000))
        assertEquals(BpOutcome.NeedsCalibration, BpPipeline.run(cal, BpSessionInput(wave(9), 100), 1_000).outcome)
        assertTrue(BpPipeline.needsCalibrationReason(cal, 1_000).contains("upside down, no raw wave"))
    }

    @Test
    fun bcgTransitTimesOutsideARealPulseAreRejected() {
        assertNull(WristBcg.transitMs(WristBcg.Result(-440.0, -300.0, 0.9, 20, 2, 1.0)))
        assertNull(WristBcg.transitMs(WristBcg.Result(-396.0, -300.0, 0.9, 20, 2, 1.0)))
        assertEquals(132.0, WristBcg.transitMs(WristBcg.Result(-132.0, -60.0, 0.9, 20, 2, 1.0))!!, 1e-9)
    }

    @Test
    fun theStandingRoundAndAStrayRoundDoNotTeachTheTransitChannel() {
        val base = round(1)
        val cal = BpCalibration(
            "c",
            0,
            listOf(
                base.copy(bcgPttMs = 150.0),
                base.copy(bcgPttMs = 160.0),
                base.copy(bcgPttMs = 155.0),
                // A stray detection and a standing round (hand far below the heart) with a lower cuff.
                base.copy(bcgPttMs = 290.0),
                base.copy(bcgPttMs = 110.0, cuffSystolic = 110, cuffDiastolic = 70, standing = true)
            )
        )
        val c = TransitEstimator.estimate(cal, BpChannel.BCG_PTT, 155.0, 1_000)
        assertNotNull(c)
        assertEquals(140.0, c!!.systolic, 1.0)
    }

    @Test
    fun aChannelThatContradictsTheOthersWidensThePlusMinusAndFlagsTheReading() {
        // 6.2 left such a channel out; 6.3 keeps it (it may be the one that sees a real change),
        // and the conflict shows: a wide ± and a flag for a cuff check.
        val pwa = ChannelEstimate(BpChannel.PWA_GREEN, 140.0, 80.0, 5.0, 3.5)
        val ir = ChannelEstimate(BpChannel.PWA_IR, 138.0, 79.0, 6.0, 4.2)
        val bcg = ChannelEstimate(BpChannel.BCG_PTT, 80.0, 30.0, 6.0, 4.2)
        val fused = BpFusion.fuse(listOf(pwa, ir, bcg), HemodynamicState.STEADY)!!
        assertTrue(fused.conflict)
        assertTrue("${fused.sdSys}", fused.sdSys >= 15)
        val agreeing = bcg.copy(systolic = 145.0, diastolic = 82.0)
        val calm = BpFusion.fuse(listOf(pwa, ir, agreeing), HemodynamicState.STEADY)!!
        assertFalse(calm.conflict)
        assertEquals(5.0, calm.sdSys, 1.0)
    }
}
