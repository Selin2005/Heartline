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
 * The particle globe of every metric on the watch (black) and on the phone (light and dark), and
 * every phase of the heart's.
 *
 * With HEARTLINE_BUBBLE_FRAMES=1, [frames] also records a few seconds of every globe frame by
 * frame (for review GIFs, not kept as goldens): see tools/screenshots/bubble_gifs.py.
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
    private fun Globe(metric: Metric, phase: BubblePhase, timeMs: Long, phaseMs: Long, progress: Float, dark: Boolean = true, phone: Boolean = false) {
        Box(Modifier.fillMaxSize().background(if (dark) Color.Black else Color(Palette.Light.BACKGROUND))) {
            ParticleGlobeOrb(
                BubbleStyle.of(metric),
                phase,
                accent(metric, dark),
                Modifier.fillMaxSize(),
                progress = progress,
                bpm = 72,
                fit = if (phone) 2.55f else 1.9f,
                dark = dark,
                surroundings = phone,
                frameMs = timeMs,
                phaseFrameMs = phaseMs,
            )
        }
    }

    /** The watch's globe for every metric, and every phase of the heart's. */
    @Test fun globe() {
        for (metric in Metric.entries) paparazzi.snapshot("globe_${metric.name.lowercase()}") { Globe(metric, BubblePhase.MEASURING, 3_400, 2_000, 0.55f) }
        val at = mapOf(BubblePhase.FORMING to 700L, BubblePhase.HINT to 400L, BubblePhase.SUCCESS to 450L, BubblePhase.FAILED to 1_200L, BubblePhase.IDLE to 2_000L)
        for ((phase, ms) in at) paparazzi.snapshot("globe_heart_${phase.name.lowercase()}") { Globe(Metric.HEART_RATE, phase, 3_000 + ms, ms, 0.55f) }
    }

    /** On the phone's light theme the unlit points are ink, not white. */
    @Test fun globeLight() {
        for (metric in Metric.entries) paparazzi.snapshot("globe_light_${metric.name.lowercase()}") { Globe(metric, BubblePhase.MEASURING, 3_400, 2_000, 0.55f, dark = false) }
    }

    /** The phone's globe, with its orbits, dust and heartbeat ripples around it. */
    @Test fun globePhone() {
        for (metric in Metric.entries) paparazzi.snapshot("globe_phone_${metric.name.lowercase()}") { Globe(metric, BubblePhase.MEASURING, 3_400, 2_000, 0.55f, phone = true) }
        val at = mapOf(BubblePhase.FORMING to 700L, BubblePhase.HINT to 400L, BubblePhase.SUCCESS to 600L, BubblePhase.FAILED to 1_200L)
        for ((phase, ms) in at) paparazzi.snapshot("globe_phone_heart_${phase.name.lowercase()}") { Globe(Metric.HEART_RATE, phase, 3_000 + ms, ms, 0.55f, phone = true) }
        paparazzi.snapshot("globe_phone_light_heart") { Globe(Metric.HEART_RATE, BubblePhase.MEASURING, 3_400, 2_000, 0.55f, dark = false, phone = true) }
    }

    /** One storyboard per metric: forming → measuring → a hint → measuring → done. */
    @Test fun frames() {
        assumeTrue(System.getenv("HEARTLINE_BUBBLE_FRAMES") == "1")
        val fps = 15
        val runs = Metric.entries.map { Triple("globe", true, it) } +
            listOf(Triple("light", false, Metric.HEART_RATE), Triple("light", false, Metric.SPO2)) +
            listOf(Metric.HEART_RATE, Metric.SPO2, Metric.STRESS).map { Triple("phone", true, it) } +
            Triple("phonelight", false, Metric.HEART_RATE)
        for ((kind, dark, metric) in runs) {
            val phone = kind.startsWith("phone")
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
                    val name = "frame_${kind}_${metric.name.lowercase()}_${"%04d".format(index++)}"
                    val (c, ip) = clock to inPhase
                    paparazzi.snapshot(name) { Globe(metric, phase, c, ip, p, dark, phone) }
                    clock += 1000L / fps
                    inPhase += 1000L / fps
                }
            }
        }
    }
}
