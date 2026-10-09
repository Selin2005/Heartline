// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.bubbles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.heartline.shared.design.Palette
import com.heartline.shared.model.Metric
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/**
 * The bubble of every metric, light and dark, and every phase of the heart bubble.
 *
 * With HEARTLINE_BUBBLE_FRAMES=1, [frames] also records a few seconds of every bubble frame by
 * frame (for review GIFs, not kept as goldens), or of the watch's globe with HEARTLINE_GLOBE=1:
 * see tools/screenshots/bubble_gifs.py.
 */
class BubbleScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6.copy(screenWidth = 720, screenHeight = 720, density = Density.XHIGH, xdpi = 320, ydpi = 320),
        maxPercentDifference = 0.1,
    )

    private fun accent(metric: Metric, dark: Boolean) = Color(
        if (dark) {
            when (metric) {
                Metric.ECG -> Palette.Dark.ECG
                Metric.BLOOD_PRESSURE -> Palette.Dark.BP
                Metric.HEART_RATE -> Palette.Dark.HEART_RATE
                Metric.SPO2 -> Palette.Dark.SPO2
                Metric.SKIN_TEMPERATURE -> Palette.Dark.TEMP
                Metric.BODY_COMPOSITION -> Palette.Dark.BODY
                Metric.STRESS -> Palette.Dark.STRESS
            }
        } else {
            when (metric) {
                Metric.ECG -> Palette.Light.ECG
                Metric.BLOOD_PRESSURE -> Palette.Light.BP
                Metric.HEART_RATE -> Palette.Light.HEART_RATE
                Metric.SPO2 -> Palette.Light.SPO2
                Metric.SKIN_TEMPERATURE -> Palette.Light.TEMP
                Metric.BODY_COMPOSITION -> Palette.Light.BODY
                Metric.STRESS -> Palette.Light.STRESS
            }
        },
    )

    @Composable
    private fun Scene(metric: Metric, phase: BubblePhase, dark: Boolean, timeMs: Long, phaseMs: Long, progress: Float) {
        Box(Modifier.fillMaxSize().background(Color(if (dark) Palette.Dark.BACKGROUND else Palette.Light.BACKGROUND))) {
            BubbleOrb(
                BubbleStyle.of(metric),
                phase,
                accent(metric, dark),
                dark,
                Modifier.fillMaxSize(),
                progress = progress,
                bpm = 72,
                frameMs = timeMs,
                phaseFrameMs = phaseMs,
            )
        }
    }

    @Composable
    private fun Globe(metric: Metric, phase: BubblePhase, timeMs: Long, phaseMs: Long, progress: Float) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            ParticleGlobeOrb(BubbleStyle.of(metric), phase, accent(metric, true), Modifier.fillMaxSize(), progress = progress, bpm = 72, frameMs = timeMs, phaseFrameMs = phaseMs)
        }
    }

    /** The watch's particle globe for every metric, and every phase of the heart's. */
    @Test fun globe() {
        for (metric in Metric.entries) paparazzi.snapshot("globe_${metric.name.lowercase()}") { Globe(metric, BubblePhase.MEASURING, 3_400, 2_000, 0.55f) }
        val at = mapOf(BubblePhase.FORMING to 700L, BubblePhase.HINT to 400L, BubblePhase.SUCCESS to 450L, BubblePhase.FAILED to 1_200L, BubblePhase.IDLE to 2_000L)
        for ((phase, ms) in at) paparazzi.snapshot("globe_heart_${phase.name.lowercase()}") { Globe(Metric.HEART_RATE, phase, 3_000 + ms, ms, 0.55f) }
    }

    @Test fun light() {
        for (metric in Metric.entries) paparazzi.snapshot("light_${metric.name.lowercase()}") { Scene(metric, BubblePhase.MEASURING, false, 1_350, 1_350, 0.55f) }
    }

    @Test fun dark() {
        for (metric in Metric.entries) paparazzi.snapshot("dark_${metric.name.lowercase()}") { Scene(metric, BubblePhase.MEASURING, true, 1_350, 1_350, 0.55f) }
    }

    @Test fun phases() {
        val at = mapOf(
            BubblePhase.IDLE to 1_000L,
            BubblePhase.FORMING to 700L,
            BubblePhase.MEASURING to 1_350L,
            BubblePhase.HINT to 120L,
            BubblePhase.SUCCESS to 520L,
            BubblePhase.FAILED to 1_200L,
        )
        for ((phase, ms) in at) paparazzi.snapshot("heart_${phase.name.lowercase()}") { Scene(Metric.HEART_RATE, phase, false, 2_000 + ms, ms, 0.55f) }
    }

    /** One storyboard per metric: forming → measuring (filling) → a hint → measuring → success. */
    @Test fun frames() {
        assumeTrue(System.getenv("HEARTLINE_BUBBLE_FRAMES") == "1")
        val fps = 15
        val globe = System.getenv("HEARTLINE_GLOBE") == "1"
        val runs = if (globe) Metric.entries.map { true to it } else Metric.entries.map { false to it } + listOf(true to Metric.HEART_RATE, true to Metric.SPO2)
        for ((dark, metric) in runs) {
            val story = listOf(
                Triple(BubblePhase.FORMING, 1_600L, 0f to 0f),
                Triple(BubblePhase.MEASURING, 3_000L, 0f to 0.6f),
                Triple(BubblePhase.HINT, 1_600L, 0.6f to 0.6f),
                Triple(BubblePhase.MEASURING, 2_000L, 0.6f to 1f),
                Triple(BubblePhase.SUCCESS, 2_200L, 1f to 1f),
            )
            var clock = 0L
            var index = 0
            for ((phase, length, range) in story) {
                var inPhase = 0L
                while (inPhase < length) {
                    val p = range.first + (range.second - range.first) * inPhase / length
                    val name = "frame_${if (globe) "globe" else if (dark) "dark" else "light"}_${metric.name.lowercase()}_${"%04d".format(index++)}"
                    val (c, ip) = clock to inPhase
                    paparazzi.snapshot(name) { if (globe) Globe(metric, phase, c, ip, p) else Scene(metric, phase, dark, c, ip, p) }
                    clock += 1000L / fps
                    inPhase += 1000L / fps
                }
            }
        }
    }
}
