// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear

import com.heartline.wear.monitor.MoveTimes
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoveTimesTest {
    @Test
    fun aReadingIsJudgedByMovementAroundItsOwnTime() {
        val moves = MoveTimes()
        // The arm moved at the end of the window (100 s); the readings came in late, from 0–75 s.
        moves.add(100_000)
        assertFalse(moves.any(40_000 - 30_000, 40_000))
        assertTrue(moves.any(110_000 - 30_000, 110_000))
    }

    @Test
    fun oldMovementsAreForgotten() {
        val moves = MoveTimes(keepMs = 60_000)
        moves.add(0)
        moves.add(120_000)
        assertFalse(moves.any(0, 1_000))
        assertTrue(moves.any(119_000, 121_000))
    }
}
