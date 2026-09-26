// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared

import com.heartline.shared.sample.SyntheticEcg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyntheticEcgTest {
    @Test
    fun producesExpectedLengthAndBeatCount() {
        val signal = SyntheticEcg.generate(durationSec = 10.0, heartRateBpm = 60.0, irregularity = 0.0)
        assertEquals(5000, signal.size)
        // Count R-peak crossings above 0.6 mV.
        var peaks = 0
        for (i in 1 until signal.size) if (signal[i - 1] < 0.6f && signal[i] >= 0.6f) peaks++
        assertTrue("peaks=$peaks", peaks in 9..11)
    }
}
