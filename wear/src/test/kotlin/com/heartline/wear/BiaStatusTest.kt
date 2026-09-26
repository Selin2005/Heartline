// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear

import com.heartline.wear.sensor.QuickHint
import com.heartline.wear.sensor.sdk.biaFraction
import com.heartline.wear.sensor.sdk.biaHint
import org.junit.Assert.assertEquals
import org.junit.Test

/** Every documented BIA status gets its own advice, not a generic "wear your watch snugly". */
class BiaStatusTest {
    @Test
    fun documentedStatusesMapToSpecificHints() {
        assertEquals(QuickHint.TOP_KEY, biaHint(7))
        assertEquals(QuickHint.BOTTOM_KEY, biaHint(8))
        assertEquals(QuickHint.TOUCH_KEYS, biaHint(9))
        assertEquals(QuickHint.DRY_SKIN, biaHint(11))
        assertEquals(QuickHint.HANDS_APART, biaHint(14))
        assertEquals(QuickHint.KEYS_ONLY, biaHint(15))
        assertEquals(QuickHint.HOLD_STILL, biaHint(17))
        assertEquals(QuickHint.WRIST_CONTACT, biaHint(4))
        assertEquals(QuickHint.WRIST_CONTACT, biaHint(10))
    }

    /** The Galaxy Watch8 reports progress 0–1 (1.0 = done); 0–100 is accepted too. */
    @Test
    fun progressIsAFractionOnEitherScale() {
        assertEquals(0.4f, biaFraction(0.4f), 1e-6f)
        assertEquals(1f, biaFraction(1.0f), 1e-6f)
        assertEquals(0.4f, biaFraction(40f), 1e-6f)
        assertEquals(1f, biaFraction(100f), 1e-6f)
        assertEquals(0f, biaFraction(null), 1e-6f)
    }
}
