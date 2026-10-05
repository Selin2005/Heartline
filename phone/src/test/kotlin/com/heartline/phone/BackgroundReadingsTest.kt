// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone

import com.heartline.phone.data.BackgroundAll
import com.heartline.phone.data.BackgroundLatest
import com.heartline.phone.data.Spo2SampleEntity
import com.heartline.phone.data.StressSampleEntity
import com.heartline.phone.data.TempSampleEntity
import com.heartline.phone.export.CsvFormat
import com.heartline.phone.ui.model.BackgroundReadings
import com.heartline.phone.ui.model.MetricReadingUi
import com.heartline.phone.ui.model.RecordFormatter
import com.heartline.shared.hr.HrContext
import com.heartline.shared.model.Metric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

/** The watch's background readings on Home, in a metric's history and in the export. */
class BackgroundReadingsTest {
    private val zone = ZoneOffset.UTC
    private val formatter = RecordFormatter("Today", "Yesterday", zone = zone, locale = Locale.US) { LocalDate.of(1970, 1, 10) }
    private val day = 9 * 86_400_000L
    private fun at(hour: Int, minute: Int = 0) = day + hour * 3_600_000L + minute * 60_000L

    @Test
    fun aNewerBackgroundReadingReplacesTheTile() {
        val latest = BackgroundLatest(spo2 = Spo2SampleEntity(at(3), 95, HrContext.SLEEP, false), temp = TempSampleEntity(at(4), 33.84f, 33.6f, HrContext.SLEEP, true))
        val spo2 = BackgroundReadings.tile(Metric.SPO2, latest, manualAtMs = at(3) - 86_400_000L * 5, formatter)!!
        assertEquals("95", spo2.value)
        assertEquals("Today ${formatter.time(at(3))}", spo2.caption)
        assertEquals(formatter.backgroundLabel, spo2.detail)
        assertEquals("33.8", BackgroundReadings.tile(Metric.SKIN_TEMPERATURE, latest, 0, formatter)!!.value)
        // A measurement taken after it keeps the tile.
        assertNull(BackgroundReadings.tile(Metric.SPO2, latest, manualAtMs = at(5), formatter))
        assertNull(BackgroundReadings.tile(Metric.STRESS, latest, 0, formatter))
    }

    @Test
    fun aDayOfReadingsIsOneHistoryRowWithItsReadings() {
        val temps = listOf(
            TempSampleEntity(at(2), 33.0f, 33f, HrContext.SLEEP, true),
            TempSampleEntity(at(3), 34.0f, 34f, HrContext.SLEEP, true),
            TempSampleEntity(at(4), 33.6f, 33f, HrContext.SLEEP, true),
            // By day the air moves it: the night decides the day's value.
            TempSampleEntity(at(14), 30.1f, 29f, HrContext.REST, false),
        )
        val row = BackgroundReadings.tempDays(temps, formatter, zone).single()
        assertEquals("33.6", row.value)
        assertTrue(row.fromWatch)
        assertEquals(at(14), row.atMs)
        assertEquals(4, row.entries.size)
        assertEquals(formatter.time(at(14)) to "30.1 °C", row.entries.first())
        val spo2 = BackgroundReadings.spo2Days(listOf(Spo2SampleEntity(at(1), 96, HrContext.SLEEP, false), Spo2SampleEntity(at(2), 93, HrContext.SLEEP, false), Spo2SampleEntity(at(3), 97, HrContext.SLEEP, false)), formatter, zone).single()
        assertEquals("96", spo2.value)
        assertEquals(listOf(R.string.detail_lowest to "93 %", R.string.detail_readings to "3"), spo2.details)
    }

    @Test
    fun historyIsNewestFirstAcrossMeasurementsAndWatchDays() {
        val manual = MetricReadingUi("m", "Sep 29", "4:29 AM", "34.1", "°C", 34.1f, atMs = at(4) - 86_400_000L * 3)
        val watch = BackgroundReadings.tempDays(listOf(TempSampleEntity(at(3), 33.5f, 33f, HrContext.SLEEP, true), TempSampleEntity(at(3) - 86_400_000L, 33.4f, 33f, HrContext.SLEEP, true)), formatter, zone)
        val merged = BackgroundReadings.merge(listOf(manual), watch)
        assertEquals(listOf(true, true, false), merged.map { it.fromWatch })
    }

    @Test
    fun theExportHasTheBackgroundReadings() {
        val rows = CsvFormat.backgroundRows(
            BackgroundAll(
                spo2 = listOf(Spo2SampleEntity(0, 95, HrContext.SLEEP, false)),
                temps = listOf(TempSampleEntity(60_000, 33.5f, null, HrContext.SLEEP, true)),
                stress = listOf(StressSampleEntity(120_000, 41.26, 64, 35, HrContext.REST)),
            ),
        ).map { it.second }
        assertEquals(
            listOf(
                "1970-01-01T00:00:00Z,SPO2,95,%,source=background;context=SLEEP;recheck=false",
                "1970-01-01T00:01:00Z,SKIN_TEMPERATURE,33.5,C,source=background;ambient=;context=SLEEP",
                "1970-01-01T00:02:00Z,STRESS,35,score,source=background;rmssd_ms=41.3;bpm=64;context=REST",
            ),
            rows,
        )
    }
}
