// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationText
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import com.heartline.wear.tile.Complications
import com.heartline.wear.tile.TileData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class ComplicationsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val c = Complications(context)
    private val data = Complications.preview
    private val empty = TileData(null, null, null, null)

    private fun ComplicationText.text() = getTextAt(context.resources, Instant.now()).toString()

    @Test
    fun heartRateInAllThreeShapes() {
        assertEquals("68", (c.heartRate(ComplicationType.SHORT_TEXT, data) as ShortTextComplicationData).text.text())
        val ranged = c.heartRate(ComplicationType.RANGED_VALUE, data) as RangedValueComplicationData
        assertEquals(68f, ranged.value, 0f)
        assertEquals(40f to 180f, ranged.min to ranged.max)
        assertEquals("68 bpm · 52–118", (c.heartRate(ComplicationType.LONG_TEXT, data) as LongTextComplicationData).text.text())
        assertEquals("--", (c.heartRate(ComplicationType.SHORT_TEXT, empty) as ShortTextComplicationData).text.text())
    }

    @Test
    fun metricsShowTheirLatestValue() {
        assertEquals("Sinus", (c.ecg(ComplicationType.SHORT_TEXT, data) as ShortTextComplicationData).text.text())
        assertEquals("118/76", (c.bloodPressure(ComplicationType.SHORT_TEXT, data) as ShortTextComplicationData).text.text())
        val stress = c.stress(ComplicationType.RANGED_VALUE, data) as RangedValueComplicationData
        assertEquals(38f, stress.value, 0f)
        assertNotNull(stress.colorRamp)
        assertEquals("97%", (c.spo2(ComplicationType.SHORT_TEXT, data) as ShortTextComplicationData).text.text())
    }

    @Test
    fun everyComplicationOpensTheApp() {
        val all: List<ComplicationData> = listOf(
            c.heartRate(ComplicationType.SHORT_TEXT, data),
            c.ecg(ComplicationType.LONG_TEXT, data),
            c.bloodPressure(ComplicationType.LONG_TEXT, data),
            c.stress(ComplicationType.SHORT_TEXT, data),
            c.spo2(ComplicationType.RANGED_VALUE, data),
            c.ecgShortcut(ComplicationType.SMALL_IMAGE),
            c.shortcut(com.heartline.shared.model.Metric.BLOOD_PRESSURE, ComplicationType.SMALL_IMAGE),
            c.shortcut(com.heartline.shared.model.Metric.SPO2, ComplicationType.MONOCHROMATIC_IMAGE),
            c.body(ComplicationType.RANGED_VALUE, data),
            c.temperature(ComplicationType.SHORT_TEXT, data),
            c.today(ComplicationType.LONG_TEXT, data),
        )
        all.forEach { assertNotNull("$it", it.tapAction) }
        assertTrue(c.ecgShortcut(ComplicationType.SMALL_IMAGE) is SmallImageComplicationData)
    }

    @Test
    fun unsupportedTypesGiveNoData() {
        assertTrue(c.ecg(ComplicationType.RANGED_VALUE, data) is NoDataComplicationData)
    }

    @Test
    fun bodyTemperatureAndToday() {
        assertEquals("21.4%", (c.body(ComplicationType.SHORT_TEXT, data) as ShortTextComplicationData).text.text())
        assertEquals(21.4f, (c.body(ComplicationType.RANGED_VALUE, data) as RangedValueComplicationData).value, 0.01f)
        assertEquals("+0.2°", (c.temperature(ComplicationType.SHORT_TEXT, data) as ShortTextComplicationData).text.text())
        // ECG done; blood pressure is next.
        assertEquals("1/3", (c.today(ComplicationType.SHORT_TEXT, data) as ShortTextComplicationData).text.text())
        assertEquals("1 of 3 done today · Next: BP", (c.today(ComplicationType.LONG_TEXT, data) as LongTextComplicationData).text.text())
        assertEquals("0/3", (c.today(ComplicationType.SHORT_TEXT, empty) as ShortTextComplicationData).text.text())
    }
}
