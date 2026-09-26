package com.heartline.wear

import com.heartline.wear.ui.components.ConfettiField
import com.heartline.wear.ui.components.radius
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeEffectsTest {
    private val field = ConfettiField(seed = 7)

    @Test
    fun confettiStartsOnTheRoundEdge() {
        // Pieces fire over the first moments, each from a point on the rim.
        val start = field.at(40)
        assertTrue(start.isNotEmpty())
        start.forEach { assertEquals(0.97f, it.radius(), 0.04f) }
    }

    @Test
    fun confettiFlowsInwardThenFadesAway() {
        val mid = field.at(500)
        assertTrue("most pieces are inside the screen", mid.count { it.radius() < 1f } > mid.size * 0.7)
        assertTrue(field.at(field.durationMs * 2).isEmpty())
        val late = field.at(field.durationMs - 50)
        assertTrue(late.all { it.alpha < 0.5f })
    }

    @Test
    fun sameSeedSameFrame() {
        assertEquals(ConfettiField(seed = 3).at(420), ConfettiField(seed = 3).at(420))
    }
}
