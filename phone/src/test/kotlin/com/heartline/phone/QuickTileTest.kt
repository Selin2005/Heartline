// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.heartline.phone.qs.QuickTilePrefs
import com.heartline.phone.widget.WidgetMetrics
import com.heartline.phone.widget.WidgetSamples
import com.heartline.shared.model.Metric
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QuickTileTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun measureTileStartsTheChosenMetric() = runTest {
        assertEquals(Metric.SPO2, QuickTilePrefs.metric(context).first())
        QuickTilePrefs.setMetric(context, Metric.STRESS)
        assertEquals(Metric.STRESS, QuickTilePrefs.metric(context).first())
    }

    @Test
    fun tileSubtitleShowsTheLatestReading() {
        val bp = WidgetMetrics.info(context, WidgetSamples.snapshot, Metric.BLOOD_PRESSURE)
        assertEquals("118/76", bp.tinyValue)
        assertEquals("Today 08:05", bp.at)
    }
}
