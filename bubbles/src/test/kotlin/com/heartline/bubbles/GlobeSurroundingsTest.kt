// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.bubbles

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class GlobeSurroundingsTest {
    private fun around(
        phase: BubblePhase,
        progress: Float = 0.5f,
        timeMs: Long = 3_000,
        phaseMs: Long = 2_000,
        style: BubbleStyle = BubbleStyle.HEART,
    ) = GlobeSurroundings(style).apply { frame(phase, timeMs, phaseMs, progress, 72, 1f) }

    @Test fun sameInputsSameFrame() {
        val a = around(BubblePhase.MEASURING)
        val b = around(BubblePhase.MEASURING)
        assertArrayEquals(a.orbitX, b.orbitX, 0f)
        assertArrayEquals(a.dustX, b.dustX, 0f)
        assertArrayEquals(a.rippleRadius, b.rippleRadius, 0f)
    }

    @Test fun theOrbitsLightUpWithTheProgress() {
        for (progress in listOf(0.2f, 0.5f, 0.9f)) {
            val s = around(BubblePhase.MEASURING, progress)
            val share = s.orbitLit.count { it }.toFloat() / GlobeSurroundings.ORBIT_POINTS
            assertEquals("progress $progress", progress, share, 0.02f)
        }
        assertTrue(around(BubblePhase.SUCCESS).orbitLit.all { it })
    }

    @Test fun theOrbitsPassBehindAndInFrontOfTheGlobe() {
        val s = around(BubblePhase.MEASURING)
        val front = s.orbitFront.count { it }
        assertTrue(front in GlobeSurroundings.ORBIT_POINTS / 4 until GlobeSurroundings.ORBIT_POINTS * 3 / 4)
        // They reach well outside the globe but stay within the surroundings.
        val reach = s.orbitX.indices.maxOf { hypot(s.orbitX[it], s.orbitY[it]) }
        assertTrue("reach $reach", reach > 1.4f && reach < GlobeSurroundings.EXTENT)
    }

    @Test fun dustStreamsInWhileFormingAndOutOnSuccess() {
        fun mean(s: GlobeSurroundings) = s.dustX.indices.map { hypot(s.dustX[it], s.dustY[it]).toDouble() }.average()
        val resting = mean(around(BubblePhase.MEASURING))
        val forming = mean(around(BubblePhase.FORMING, phaseMs = 900))
        val burst = mean(around(BubblePhase.SUCCESS, phaseMs = 1_200))
        assertTrue("forming $forming vs $resting", forming < resting)
        assertTrue("burst $burst vs $resting", burst > resting)
        // The burst also fades the dust out.
        assertTrue(around(BubblePhase.SUCCESS, phaseMs = 1_400).dustAlpha.max() < 0.1f)
    }

    @Test fun heartbeatRipplesSpreadOutwardAndFade() {
        val early = around(BubblePhase.MEASURING, timeMs = 100)
        val later = around(BubblePhase.MEASURING, timeMs = 500)
        assertTrue(later.rippleRadius[0] > early.rippleRadius[0])
        assertTrue(later.rippleAlpha[0] < early.rippleAlpha[0])
        assertTrue(early.rippleRadius.indices.all { early.rippleAlpha[it] == 0f || early.rippleRadius[it] <= GlobeSurroundings.EXTENT + 0.01f })
    }

    @Test fun noRipplesWithoutAPulseOrAfterTheResult() {
        assertTrue(around(BubblePhase.MEASURING, style = BubbleStyle.SPO2).rippleAlpha.all { it == 0f })
        assertTrue(around(BubblePhase.SUCCESS).rippleAlpha.all { it == 0f })
    }
}
