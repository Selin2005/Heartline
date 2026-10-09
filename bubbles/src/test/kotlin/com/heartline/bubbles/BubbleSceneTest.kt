// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.bubbles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleSceneTest {
    private val styles = BubbleStyle.entries

    @Test fun sameInputsGiveTheSameFrame() {
        for (style in styles) {
            val a = BubbleScene(style).frame(BubblePhase.MEASURING, 1234, 800, 0.4f, 70)
            val b = BubbleScene(style).frame(BubblePhase.MEASURING, 1234, 800, 0.4f, 70)
            assertTrue(a.radii.contentEquals(b.radii))
            assertEquals(a.front, b.front)
            assertEquals(a.inside, b.inside)
        }
    }

    @Test fun outlineStaysRoundish() {
        for (style in styles) for (phase in BubblePhase.entries) for (t in 0L..20_000L step 137) {
            val f = BubbleScene(style).frame(phase, t, t % 3_000, 0.5f, 90)
            assertEquals(BubbleScene.CONTOUR_POINTS, f.radii.size)
            assertTrue("$style $phase $t", f.radii.all { it in 0.75f..1.25f })
        }
    }

    @Test fun liquidRisesWithProgress() {
        val scene = BubbleScene(BubbleStyle.SPO2)
        val levels = (0..10).map { scene.frame(BubblePhase.MEASURING, 5_000, 5_000, it / 10f).level }
        assertTrue(levels.zipWithNext().all { (a, b) -> b > a })
        assertTrue(levels.first() > 0f && levels.last() <= 1f)
    }

    @Test fun burstEndsWithACalmFullBubble() {
        val scene = BubbleScene(BubbleStyle.HEART)
        val mid = scene.frame(BubblePhase.SUCCESS, 10_000, 600, 1f)
        assertTrue(mid.sparks.isNotEmpty())
        val end = scene.frame(BubblePhase.SUCCESS, 12_000, 2_500, 1f)
        assertTrue(end.sparks.isEmpty())
        assertEquals(1f, end.alpha, 0.001f)
        assertEquals(1f, end.level, 0.001f)
    }

    @Test fun failureDrainsAndTurnsGrey() {
        val f = BubbleScene(BubbleStyle.BLOOD_PRESSURE).frame(BubblePhase.FAILED, 9_000, 2_000, 0.7f)
        assertEquals(1f, f.grey, 0.001f)
        assertEquals(0f, f.level, 0.001f)
    }

    @Test fun hintTintsAmberAndProgressRingShows() {
        val f = BubbleScene(BubbleStyle.SPO2).frame(BubblePhase.HINT, 3_000, 1_000, 0.3f)
        assertTrue(f.amber > 0.4f)
        assertTrue(f.showProgress)
    }

    @Test fun heartBeatsFasterWithAFasterPulse() {
        fun beats(bpm: Int) = (0 until 6_000 step 10).count { t ->
            val scene = BubbleScene(BubbleStyle.HEART)
            scene.frame(BubblePhase.MEASURING, t.toLong(), 3_000, 0.5f, bpm).rings.any { it.alpha > 0.33f }
        }
        assertTrue(beats(120) > beats(60))
    }

    @Test fun beatScaleReturnsToRest() {
        assertEquals(1f, BubbleScene.beatScale(0f, 0.1f), 0.001f)
        assertEquals(1f, BubbleScene.beatScale(0.7f, 0.1f), 0.001f)
        assertTrue(BubbleScene.beatScale(0.12f, 0.1f) > 1.09f)
    }
}
