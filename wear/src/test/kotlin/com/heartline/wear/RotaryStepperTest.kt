// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear

import com.heartline.wear.ui.components.RotaryStepper
import org.junit.Assert.assertEquals
import org.junit.Test

/** Bezel clicks are one step each (faster turning jumps), crown scrolling adds up. */
class RotaryStepperTest {
    @Test
    fun eachBezelClickIsOneStep() {
        val s = RotaryStepper()
        assertEquals(1, s.onEvent(96f, 0))
        assertEquals(1, s.onEvent(96f, 400))
        assertEquals(-1, s.onEvent(-96f, 800))
    }

    @Test
    fun fastClicksInOneDirectionJump() {
        val s = RotaryStepper()
        assertEquals(1, s.onEvent(96f, 0))
        assertEquals(5, s.onEvent(96f, 50))
        assertEquals(-1, s.onEvent(-96f, 90)) // a turn back is never a jump
    }

    @Test
    fun smoothCrownEventsAddUp() {
        val s = RotaryStepper()
        val steps = (0 until 9).sumOf { s.onEvent(8f, it * 200L) }
        assertEquals(2, steps) // 72 px at 36 px a step
        assertEquals(0, s.onEvent(-8f, 5_000)) // reversing drops what was pending
    }
}
