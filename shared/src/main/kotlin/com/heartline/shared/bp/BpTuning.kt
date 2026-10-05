// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.bp

/**
 * The algorithm's tunable choices in one place, so variants can be compared on real recorded
 * sessions with their cuff readings (BpExportEvaluation) before one becomes the default.
 *
 * [hydrostaticFactor]: share of the wrist's ρgh (from the forearm angle) taken off the estimate;
 * 1 until algorithm 6.3, 0 since 6.4 (real cuff checks: systolic error SD 9.0 → 4.0 mmHg).
 * [diastolic]: how the diastolic follows the pulse wave (see [Diastolic]); COUPLED since 6.4
 * (real cuff checks: diastolic error SD 14.3 → 7.7 mmHg).
 * [diaPriorRel]: how far this user's diastolic slopes may move from the population's.
 * [transitHydrostaticFactor]: share of the arm's ρgh taken off a transit-time channel. Unlike the
 * pulse shape, the transit time itself changes with the hand's height (the arm's share of the
 * path is under a different pressure), so in principle the channel's own value is corrected back.
 * 0 since 6.4 all the same: the hand's height comes from the forearm angle under a seated
 * geometry, which lying down breaks (it said 47 cm below the heart), and on real cuff checks
 * the correction made the systolic error larger (SD 4.4 against 4.0 without).
 */
data class BpTuning(
    val hydrostaticFactor: Double = 0.0,
    val diastolic: Diastolic = Diastolic.COUPLED,
    val diaPriorRel: Double = 2.5,
    val transitHydrostaticFactor: Double = 0.0
) {
    enum class Diastolic {
        /** Its own shape model, like the systolic (algorithms 3–6.3). */
        SHAPE,

        /**
         * The change follows the systolic change times this user's ratio (learned from the cuff
         * readings around a population prior of 0.5).
         */
        COUPLED
    }

    companion object {
        val DEFAULT = BpTuning()

        /** Algorithms 6.0–6.3, for comparison. */
        val ALGORITHM_6_3 = BpTuning(hydrostaticFactor = 1.0, diastolic = Diastolic.SHAPE, transitHydrostaticFactor = 1.0)
    }
}
