// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.bubbles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressDisplayTest {
    private fun ProgressDisplay.run(target: Float, ms: Long, paused: Boolean = false, done: Boolean = false): List<Float> =
        (0 until (ms / 33).toInt()).map { update(target, paused, done, 33) }

    @Test fun glidesInsteadOfJumping() {
        val d = ProgressDisplay()
        val steps = d.run(0.3f, 2_000)
        assertTrue(steps.zipWithNext().all { (a, b) -> b - a < 0.05f })
        assertEquals(0.3f, d.shown, 0.005f)
    }

    @Test fun neverGoesBackForASmallDip() {
        val d = ProgressDisplay()
        d.run(0.5f, 3_000)
        d.run(0.42f, 2_000)
        assertEquals(0.5f, d.shown, 0.005f)
    }

    @Test fun aRealRestartGoesBackSmoothly() {
        val d = ProgressDisplay()
        d.run(0.7f, 3_000)
        val back = d.run(0.05f, 3_000)
        assertTrue(back.first() > 0.5f)
        assertEquals(0.05f, d.shown, 0.01f)
    }

    @Test fun holdsWhileAHintIsUp() {
        val d = ProgressDisplay()
        d.run(0.4f, 3_000)
        d.run(0.8f, 2_000, paused = true)
        assertEquals(0.4f, d.shown, 0.005f)
    }

    @Test fun stopsAt99UntilTheResult() {
        val d = ProgressDisplay()
        d.run(1f, 5_000)
        assertEquals(99, d.percent)
        d.run(1f, 2_000, done = true)
        assertEquals(100, d.percent)
    }
}
