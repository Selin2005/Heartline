// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone

import com.heartline.shared.vitals.Spo2Outcome
import com.heartline.phone.ui.model.Spo2Today
import com.heartline.phone.data.Spo2AttemptEntity
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
    fun aStretchOfReadingsIsOneHistoryRowWithItsReadings() {
        val temps = listOf(
            TempSampleEntity(at(2), 33.0f, 33f, HrContext.SLEEP, true),
            TempSampleEntity(at(3), 34.0f, 34f, HrContext.SLEEP, true),
            TempSampleEntity(at(4), 33.6f, 33f, HrContext.SLEEP, true),
        )
        val row = BackgroundReadings.tempDays(temps, formatter, zone).single()
        assertEquals("33.6", row.value)
        assertTrue(row.fromWatch)
        assertEquals(at(4), row.atMs)
        assertEquals("${formatter.time(at(2))}–${formatter.time(at(4))}", row.time)
        assertEquals(3, row.entries.size)
        assertEquals(formatter.time(at(4)) to "33.6 °C", row.entries.first())
        val spo2 = BackgroundReadings.spo2Days(listOf(Spo2SampleEntity(at(1), 96, HrContext.SLEEP, false), Spo2SampleEntity(at(2), 93, HrContext.SLEEP, false), Spo2SampleEntity(at(3), 97, HrContext.SLEEP, false)), formatter, zone).single()
        assertEquals("96", spo2.value)
        assertEquals(listOf(R.string.detail_lowest to "93 %", R.string.detail_readings to "3"), spo2.details)
    }

    @Test
    fun aNightAndAnEveningNapAreTwoRowsAroundAMeasurementBetweenThem() {
        // The real case: readings 1:19–6:07 at night, a manual one at 17:50, a nap reading at 20:09.
        val spo2 = listOf(1 to 19, 2 to 31, 3 to 55, 5 to 6, 6 to 7, 20 to 9).map { (h, m) -> Spo2SampleEntity(at(h, m), 95, HrContext.SLEEP, false) }
        val watch = BackgroundReadings.spo2Days(spo2, formatter, zone)
        assertEquals(2, watch.size)
        val manual = MetricReadingUi("m", "Today", formatter.time(at(17, 50)), "95", "%", 95f, atMs = at(17, 50))
        val merged = BackgroundReadings.merge(listOf(manual), watch)
        assertEquals(listOf(at(20, 9), at(17, 50), at(6, 7)), merged.map { it.atMs })
        assertEquals("${formatter.time(at(1, 19))}–${formatter.time(at(6, 7))}", merged.last().time)
        assertEquals(formatter.time(at(20, 9)), merged.first().time)
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

    @Test
    fun todaysBloodOxygenTriesShowTheLastOneAndTheNext() {
        val samples = listOf(
            Spo2SampleEntity(at(2), 96, HrContext.SLEEP, false),
            Spo2SampleEntity(at(3), 95, HrContext.SLEEP, false),
            Spo2SampleEntity(at(11), 97, HrContext.REST, false),
        )
        val tries = listOf(
            Spo2AttemptEntity(at(11), Spo2Outcome.MEASURED, 97, null),
            Spo2AttemptEntity(at(12), Spo2Outcome.MOVING, null, at(12, 10)),
        )
        val ui = Spo2Today.ui(tries, samples, day, 60, formatter)!!
        assertEquals(1, ui.awake)
        assertEquals(2, ui.asleep)
        assertEquals(Spo2Outcome.MOVING, ui.lastOutcome)
        assertEquals(formatter.time(at(12)), ui.lastTime)
        // Put off: back in 10 minutes. Measured: an interval later.
        assertEquals(formatter.time(at(12, 10)), ui.next)
        assertEquals(formatter.time(at(11, 30)), Spo2Today.ui(tries.take(1), samples, day, 30, formatter)!!.next)
        // Yesterday's don't count; nothing today, no card.
        assertNull(Spo2Today.ui(tries, samples, at(13), 60, formatter))
    }
}
