// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared

import com.heartline.shared.bp.BpEstimator
import com.heartline.shared.bp.PpgFeatures
import com.heartline.shared.sample.SyntheticPpg
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test

/** The same physiological state recorded twice must give features within their day-to-day spread. */
class BpStabilityTest {
    @Test
    fun repeatedRecordingsOfOneStateAreConsistent() {
        val all = (1..8).map { PpgFeatures.extract(SyntheticPpg.generate(20.0, 68.0, 0.5, seed = it), 100)!!.modelArray() }
        for (j in all.first().indices) {
            val values = all.map { it[j] }
            val spread = values.max() - values.min()
            assertTrue("feature $j spread $spread values $values", spread < BpEstimator.featureScale[j])
        }
        val ba = all.map { it[4] }
        assertTrue("b/a negative and bounded: $ba", ba.all { it in -3.0..0.0 })
        assertTrue(abs(ba.average()) > 0.05)
    }
}
