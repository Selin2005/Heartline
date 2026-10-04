// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared

import com.heartline.shared.bp.BpCalibration
import com.heartline.shared.bp.BpChannel
import com.heartline.shared.bp.BpEstimate
import com.heartline.shared.bp.BpEstimator
import com.heartline.shared.bp.BpExportEvaluation
import com.heartline.shared.bp.BpOutcome
import com.heartline.shared.bp.BpPipeline
import com.heartline.shared.bp.BpTuning
import com.heartline.shared.bp.CalibrationPoint
import com.heartline.shared.bp.PpgFeatureVector
import com.heartline.shared.sample.SyntheticSession
import com.heartline.shared.sync.Protocol
import kotlin.math.abs
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Algorithm 6.4, on a real user's recordings (Galaxy Watch6 Classic, treated hypertension,
 * 29 Sep – 2 Oct 2026): a calibration of 3 rounds, 9 readings each checked with a cuff, and one
 * reading taken lying in bed that came out far too low (111/39). Only derived numbers are kept
 * (each reading's features, forearm angle and the cuff values; times relative to calibration):
 * no raw waves and nothing that identifies the user.
 *
 * The test uses the green pulse-wave channel alone; the full replay of the raw sessions (every
 * channel) gave the same picture: systolic error SD 9.0 → 4.0 mmHg, diastolic 14.3 → 7.7.
 */
class BpRealLog2Test {
    @Serializable
    private data class Session(
        val atMs: Long,
        val green: PpgFeatureVector,
        val ir: PpgFeatureVector? = null,
        val pitchDeg: Double? = null,
        val hydrostaticMmHg: Double = 0.0,
        val cuffSystolic: Int? = null,
        val cuffDiastolic: Int? = null,
        val shownSystolic: Int,
        val shownDiastolic: Int,
        val state: String? = null
    )

    @Serializable
    private data class Fixture(val calibration: BpCalibration, val sessions: List<Session>)

    private val fixture = Protocol.json.decodeFromString<Fixture>(
        javaClass.getResource("/bp/real-user-2.json")!!.readText()
    )

    /** The calibration as it was before [s] (its own cuff check left out). */
    private fun calibrationBefore(s: Session) = fixture.calibration.copy(
        extraPoints = fixture.calibration.extraPoints.filter { (it.atMs ?: 0) < s.atMs - 120_000 }
    )

    private fun estimate(s: Session, tuning: BpTuning, hydrostaticMmHg: Double): BpEstimate {
        val out = BpEstimator.estimate(
            calibrationBefore(s),
            s.green,
            s.atMs,
            channel = BpChannel.PWA_GREEN,
            hydrostaticMmHg = hydrostaticMmHg,
            tuning = tuning
        )
        return (out as? BpOutcome.Ok)?.estimate ?: error("no number: $out")
    }

    private val checked = fixture.sessions.filter { it.cuffSystolic != null }
    private val lying = fixture.sessions.single { (it.pitchDeg ?: 0.0) < -45 }

    private fun errors(tuning: BpTuning, withRhoGh: Boolean) = checked.map { s ->
        val e = estimate(s, tuning, if (withRhoGh) s.hydrostaticMmHg * tuning.hydrostaticFactor else 0.0)
        (e.systolic - s.cuffSystolic!!).toDouble() to (e.diastolic - s.cuffDiastolic!!).toDouble()
    }

    @Test
    fun withoutTheForearmCorrectionTheSystolicMeetsTheValidationLimits() {
        val old = errors(BpTuning.ALGORITHM_6_3, withRhoGh = true)
        val now = errors(BpTuning.DEFAULT, withRhoGh = true)
        val oldSys = BpExportEvaluation.stats(old.map { it.first })
        val newSys = BpExportEvaluation.stats(now.map { it.first })
        // 6.3 took the wrist's ρgh off: +15 at a forearm angle of +15°, −17 at −18°.
        assertTrue("$oldSys", oldSys.sd > 8)
        // ISO 81060-2: mean within ±5 mmHg, SD ≤ 8.
        assertTrue("$newSys", abs(newSys.mean) <= 5 && newSys.sd <= 5 && newSys.mae <= 4)
    }

    @Test
    fun theDiastolicFollowsTheSystolicAndItsPlusMinusIsHonest() {
        val old = BpExportEvaluation.stats(errors(BpTuning.ALGORITHM_6_3, withRhoGh = true).map { it.second })
        val now = BpExportEvaluation.stats(errors(BpTuning.DEFAULT, withRhoGh = true).map { it.second })
        assertTrue("old $old new $now", now.sd < old.sd - 4 && now.mae < old.mae)
        // The diastolic ± comes from its own misfit, no longer 0.7 × the systolic's.
        val covered = checked.count { s ->
            val e = estimate(s, BpTuning.DEFAULT, 0.0)
            abs(e.diastolic - s.cuffDiastolic!!) <= 2 * e.uncertaintyDia
        }
        assertTrue("$covered of ${checked.size}", covered >= 7)
    }

    @Test
    fun lyingInBedNoLongerTakesThirtySevenMmHgOff() {
        // Shown 111/39 (forearm −58°, 37 mmHg of ρgh taken off); the user's cuff readings that day were about 142/87.
        assertTrue(lying.shownSystolic < 115 && lying.hydrostaticMmHg > 30)
        val e = estimate(lying, BpTuning.DEFAULT, lying.hydrostaticMmHg * BpTuning.DEFAULT.hydrostaticFactor)
        assertTrue("$e", e.systolic in 130..155 && e.diastolic >= 70)
    }

    @Test
    fun aPostureFarFromTheCalibrationsIsFlaggedNotCorrected() {
        val base = SyntheticSession.Spec(systolic = 139.0, diastolic = 79.0, refSystolic = 139.0, heartRateStart = 80.0)
        val cal = BpCalibration(
            "c",
            0,
            (1..3).map { i -> CalibrationPoint.of(BpPipeline.capture(SyntheticSession.generate(base.copy(seed = i)).input)!!, 139, 79, 80) }
        )
        fun run(pitch: Double) = (
            BpPipeline.run(
                cal,
                SyntheticSession.generate(
                    base.copy(pitchDeg = {
                        pitch
                    }, seed = 30)
                ).input,
                1_000
            ).outcome as BpOutcome.Ok
            ).estimate
        val seated = run(0.0)
        val lyingDown = run(-58.0)
        assertFalse(seated.postureDiffers)
        assertTrue(lyingDown.postureDiffers && lyingDown.beyondCalibration)
        assertTrue("$lyingDown", lyingDown.uncertaintySys > seated.uncertaintySys)
        // The same pressure, a different arm angle: the number itself is not moved by the angle.
        assertTrue("$seated vs $lyingDown", abs(seated.systolic - lyingDown.systolic) <= 6)
    }
}
