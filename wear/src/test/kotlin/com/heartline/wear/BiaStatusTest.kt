package com.heartline.wear

import com.heartline.wear.sensor.QuickHint
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
}
