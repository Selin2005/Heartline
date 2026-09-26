// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.heartline.phone.report.EcgReportBuilder
import com.heartline.phone.report.EcgReportPainter
import com.heartline.shared.report.EcgStripLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * Robolectric has no native PdfDocument, so this checks the report content and paints the page
 * onto a real bitmap canvas (the same painter the PDF uses). PDF bytes are checked on device (P10).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReportExportTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun reportDataIsLocalisedAndComplete() {
        val data = EcgReportBuilder(context).data(SampleData.ecgRecords[1])
        assertEquals("Heartline ECG report", data.title)
        assertEquals("Signs of AFib", data.result)
        assertTrue(data.details.any { it.second == "94 bpm" })
        assertTrue(data.details.any { it.second.contains("Fatigue") })
        assertTrue(data.footer.contains("500 Hz"))
        assertEquals(15_000, data.samples.size)
    }

    @Test
    fun painterDrawsTraceInsideEachStrip() {
        val data = EcgReportBuilder(context).data(SampleData.ecgRecords[0])
        val bitmap = Bitmap.createBitmap(EcgStripLayout.PAGE_WIDTH_PT, EcgStripLayout.PAGE_HEIGHT_PT, Bitmap.Config.ARGB_8888)
        EcgReportPainter().draw(Canvas(bitmap), data)
        EcgStripLayout.strips(data.samples.size, data.sampleRateHz).forEach { strip ->
            val y0 = strip.top.toInt()
            val y1 = (strip.top + strip.height).toInt()
            val left = strip.left.toInt()
            val right = (strip.left + strip.width).toInt()
            fun hasTrace(x: Int) = (y0 until y1).any { y -> Color.red(bitmap.getPixel(x, y)) < 120 }
            // The thin (0.7 pt) antialiased trace must run from the start to the end of the strip.
            assertTrue("strip ${strip.index} start", (left until left + 30).any(::hasTrace))
            assertTrue("strip ${strip.index} end", (right - 30 until right).any(::hasTrace))
            assertTrue("strip ${strip.index}", (left until right step 3).count(::hasTrace) > 60)
        }
    }

    @Test
    fun imageExportIsASharpPngOfThePage() {
        val file = EcgReportBuilder(context).exportImage(SampleData.ecgRecords[0], name = "Sara", fileName = "ecg.png")
        val bitmap = android.graphics.BitmapFactory.decodeFile(file.path)
        assertEquals(EcgStripLayout.PAGE_WIDTH_PT * 3, bitmap.width)
        assertEquals(EcgStripLayout.PAGE_HEIGHT_PT * 3, bitmap.height)
        assertEquals(Color.WHITE, bitmap.getPixel(2, 2))
    }

    @Test
    fun nameFollowsTheSettingsChoice() {
        val profile = com.heartline.shared.profile.UserProfile("Sara", "Karimi", "Sari", "1990-06-12")
        val builder = EcgReportBuilder(context)
        assertTrue(builder.data(SampleData.ecgRecords[0], profile, "Sari").recordedAt.startsWith("Sari, "))
        assertTrue(!builder.data(SampleData.ecgRecords[0], profile, null).recordedAt.contains("Sar"))
    }
}
