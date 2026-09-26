// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.glance.wear.tooling.preview.RectangularLargeWidgetPreviewParams
import androidx.glance.wear.tooling.preview.RectangularSmallWidgetPreviewParams
import androidx.glance.wear.core.ContainerInfo
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.heartline.shared.bp.BpCategory
import com.heartline.shared.model.EcgResult
import com.heartline.shared.model.Metric
import com.heartline.shared.profile.StressLevel
import com.heartline.wear.tile.BodyCard
import com.heartline.wear.tile.BpCard
import com.heartline.wear.tile.EcgCard
import com.heartline.wear.tile.HeartCard
import com.heartline.wear.tile.MeasureCard
import com.heartline.wear.tile.Spo2Card
import com.heartline.wear.tile.StressCard
import com.heartline.wear.tile.TileData
import com.heartline.wear.tile.TodayCard
import com.heartline.wear.tile.WellnessCard
import com.heartline.wear.tile.Greeting
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Every card, small and large, drawn by Google's widget preview (the same document the watch plays). */
class CardScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.WEAR_OS_SMALL_ROUND.copy(screenWidth = 520, screenHeight = 500, xdpi = 320, ydpi = 320, density = com.android.resources.Density.XHIGH, screenRound = null, orientation = com.android.resources.ScreenOrientation.LANDSCAPE))

    private fun shot(name: String, widget: androidx.glance.wear.GlanceWearWidget) {
        val large = RectangularLargeWidgetPreviewParams().values.first()
        // Older watches show the card as a full-screen tile (a round 227dp screen).
        @Suppress("RestrictedApi")
        val full = androidx.glance.wear.core.WearWidgetParams(large.instanceId, ContainerInfo.CONTAINER_TYPE_TILE_COMPAT, 227f, 227f, 0f, 0f, 113f)
        listOf("small" to RectangularSmallWidgetPreviewParams().values.first(), "large" to large, "full" to full).forEach { (size, params) ->
            paparazzi.snapshot("${name}_$size") {
                Box(Modifier.background(Color.Black).padding(8.dp)) {
                    androidx.glance.wear.tooling.preview.WearWidgetPreview(widget, params)
                }
            }
        }
    }

    // The Today card greets by the time of day: pin it to the morning the goldens were recorded in.
    @Before fun morning() {
        Greeting.hourNow = { 9 }
    }

    @Test fun heart() = shot("heart", HeartCard(CardSamples.full))

    @Test fun bp() = shot("bp", BpCard(CardSamples.full))

    @Test fun ecg() = shot("ecg", EcgCard(CardSamples.full))

    @Test fun spo2() = shot("spo2", Spo2Card(CardSamples.full))

    @Test fun stress() = shot("stress", StressCard(CardSamples.full))

    @Test fun body() = shot("body", BodyCard(CardSamples.full))

    @Test fun today() = shot("today", TodayCard(CardSamples.full))

    @Test fun wellness() = shot("wellness", WellnessCard(CardSamples.full))

    @Test fun measure() = shot("quick", MeasureCard(CardSamples.full))

    @Test fun heartEmpty() = shot("heart_empty", HeartCard(CardSamples.empty))
}

object CardSamples {
    val full = TileData(
        heartRate = 72,
        lastEcg = "Sinus rhythm",
        lastBp = "118/76",
        bpDaysLeft = 21,
        heartMin = 52,
        heartMax = 118,
        ecgResult = EcgResult.SINUS_RHYTHM,
        bpCategory = BpCategory.NORMAL,
        spo2 = 97,
        stressScore = 38,
        stressLevel = StressLevel.MEDIUM,
        hrvMs = 42,
        temperature = "+0.2°",
        heartHours = listOf(56..64, 54..60, 55..62, 58..70, 62..95, 70..118, 66..84, 64..80, 68..90, 66..78, 64..76, 70..74),
        spo2History = listOf(96, 97, 95, 98, 97),
        stressHistory = listOf(52, 41, 47, 30, 35, 44, 38),
        body = TileData.Body(21.4f, 32.1f, 72.4f, -0.3f),
        doneToday = setOf(Metric.ECG),
        name = "Sara",
        streak = 6,
    )
    val empty = TileData(null, null, null, null)
}
