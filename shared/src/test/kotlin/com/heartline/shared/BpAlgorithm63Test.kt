// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared

import com.heartline.shared.bp.BpCalibration
import com.heartline.shared.bp.BpEstimate
import com.heartline.shared.bp.BpOutcome
import com.heartline.shared.bp.BpPipeline
import com.heartline.shared.bp.CalibrationPoint
import com.heartline.shared.sample.SyntheticSession
import com.heartline.shared.sample.SyntheticSession.Spec
import kotlin.math.abs
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Algorithm 6.3: the number must follow the pressure away from the calibration (a fall to 90/60,
 * a rise to 170/100), not only reproduce it, and its ± must be honest. Calibrated like the second
 * real user (Galaxy Watch6 Classic, treated hypertension, about 139/79). In the synthetic sessions
 * the pressure drives the transit time; the pulse-wave shape follows the given stiffness.
 */
class BpAlgorithm63Test {
    /** Like the second real user: treated hypertension, calibrated at about 139/79, pulse about 85. */
    private val base =
        Spec(systolic = 139.0, diastolic = 79.0, refSystolic = 139.0, heartRateStart = 85.0, stiffness = 0.6, irStiffness = 0.6)

    private val calibration = BpCalibration(
        "c",
        0,
        (1..3).map { i ->
            val s = SyntheticSession.generate(base.copy(heartRateStart = 84.0 + i, seed = i))
            CalibrationPoint.of(BpPipeline.capture(s.input)!!, 138 + i - 1, 79, 85)
        }
    )

    private fun measure(spec: Spec): BpEstimate {
        val out = BpPipeline.run(calibration, SyntheticSession.generate(spec).input, 1_000).outcome
        return (out as? BpOutcome.Ok)?.estimate ?: error("no number: $out")
    }

    private val same = base.copy(seed = 20)

    /** A real fall: fast pulse, softer-looking wave. */
    private val low = base.copy(
        systolic = 90.0,
        diastolic = 60.0,
        heartRateStart = 110.0,
        heartRateEnd = 110.0,
        stiffness = 0.35,
        irStiffness = 0.35,
        seed = 21
    )

    /** The same fall with the wrist vessels clamped down (the wave looks stiffer, as in a faint). */
    private val lowConstricted = base.copy(
        systolic = 90.0,
        diastolic = 60.0,
        heartRateStart = 115.0,
        heartRateEnd = 115.0,
        stiffness = 0.7,
        irStiffness = 0.45,
        perfusionIndex = 0.45,
        irPerfusionIndex = 0.5,
        seed = 22
    )

    private val high = base.copy(systolic = 170.0, diastolic = 100.0, stiffness = 0.85, irStiffness = 0.85, seed = 23)

    @Test
    fun atTheCalibratedPressureTheNumberIsTheCalibrationsAndThePlusMinusIsHonest() {
        val e = measure(same)
        assertTrue("$e", abs(e.systolic - 139) <= 3 && abs(e.diastolic - 79) <= 3)
        // Before 6.3 three agreeing channels claimed ±3; they share one cuff calibration, so ±5 at least.
        assertTrue("$e", e.uncertaintySys >= 5)
        assertFalse(e.beyondCalibration)
    }

    @Test
    fun aFallTo90Over60IsFollowedNotAveragedAway() {
        // Before 6.3 both falls read about 140 (±6 and ±9): the transit channel saw the fall, but
        // its uncertainty about the size of a change took away its weight, and the pulse-wave
        // channels (which see little here) kept the number at the calibration.
        val e = measure(low)
        assertTrue("$e", e.systolic <= 135 && e.beyondCalibration)
        val c = measure(lowConstricted)
        assertTrue("$c", c.systolic <= 120 && c.beyondCalibration)
        assertTrue("$c", c.systolic - c.uncertaintySys <= 95)
    }

    @Test
    fun aRiseTo170Over100MovesTheNumberUp() {
        // Before 6.3: 142. The transit channel alone sees the rise here and its slope for this
        // user is still uncertain, so the number moves only part of the way: cuff checks at
        // other pressures teach the slope (see BP_HISTORY.md, algorithm 6.3).
        val e = measure(high)
        assertTrue("$e", e.systolic >= 143)
    }
}
