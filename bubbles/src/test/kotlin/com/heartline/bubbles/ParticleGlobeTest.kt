// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.bubbles

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParticleGlobeTest {
    private fun globe(phase: BubblePhase, progress: Float = 0.5f, timeMs: Long = 3_000, phaseMs: Long = 2_000, style: BubbleStyle = BubbleStyle.SPO2) =
        ParticleGlobe(style).apply { frame(phase, timeMs, phaseMs, progress, 72) }

    @Test fun sameInputsSameFrame() {
        val a = globe(BubblePhase.MEASURING)
        val b = globe(BubblePhase.MEASURING)
        assertArrayEquals(a.x, b.x, 0f)
        assertArrayEquals(a.y, b.y, 0f)
        assertArrayEquals(a.alpha, b.alpha, 0f)
    }

    @Test fun theLitShareFollowsTheProgress() {
        for (progress in listOf(0.1f, 0.3f, 0.5f, 0.8f)) {
            val g = globe(BubblePhase.MEASURING, progress)
            val share = g.lit.count { it }.toFloat() / g.count
            assertEquals("progress $progress", progress, share, 0.03f)
        }
    }

    @Test fun theBottomLightsFirst() {
        val g = globe(BubblePhase.MEASURING, 0.3f, timeMs = 0)
        // At time 0 nothing has turned yet: lit points are the lower ones on screen.
        val litY = g.y.indices.filter { g.lit[it] }.map { g.y[it] }.average()
        val darkY = g.y.indices.filter { !g.lit[it] }.map { g.y[it] }.average()
        assertTrue(litY > darkY)
    }

    @Test fun pointsGatherWhileForming() {
        fun spread(phaseMs: Long) = globe(BubblePhase.FORMING, phaseMs = phaseMs).let { g -> g.x.indices.maxOf { kotlin.math.hypot(g.x[it], g.y[it]) } }
        assertTrue(spread(100) > 1.8f)
        assertTrue(spread(3_000) < 1.25f)
    }

    @Test fun aResultLightsEverythingAndSwells() {
        val g = globe(BubblePhase.SUCCESS, 1f, phaseMs = 450)
        assertTrue(g.lit.all { it })
        assertTrue(g.scale > 1.25f)
        val settled = globe(BubblePhase.SUCCESS, 1f, phaseMs = 3_000)
        assertEquals(1f, settled.scale, 0.06f)
    }

    @Test fun failureTurnsGreyAndDims() {
        val g = globe(BubblePhase.FAILED, phaseMs = 3_000)
        assertEquals(1f, g.grey, 0.001f)
        assertTrue(g.lit.none { it })
    }

    @Test fun aHintTurnsAmber() {
        assertTrue(globe(BubblePhase.HINT, phaseMs = 1_000).amber > 0.5f)
    }

    @Test fun drawnBackToFront() {
        val g = globe(BubblePhase.MEASURING)
        // Larger alpha means nearer (alpha grows with depth towards the viewer for lit points).
        val sizes = g.order.map { g.size[it] / (if (g.lit[it]) 1.25f else 1f) }
        assertTrue(sizes.first() < sizes.last())
        assertEquals(g.count, g.order.toSet().size)
    }

    @Test fun heartBeatsWithThePulse() {
        val scales = (0 until 20).map { ParticleGlobe(BubbleStyle.HEART).apply { frame(BubblePhase.MEASURING, it * 50L, 2_000, 0.5f, 60) }.scale }
        assertTrue(scales.max() - scales.min() > 0.03f)
    }
}
