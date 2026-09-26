// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone

import com.heartline.phone.data.HrMinuteEntity
import com.heartline.phone.data.RecordEntity
import com.heartline.phone.data.StoredRecord
import com.heartline.phone.ui.model.RecordFormatter
import com.heartline.phone.widget.WidgetSnapshot
import com.heartline.phone.widget.WidgetSnapshots
import com.heartline.shared.bp.BpCategory
import com.heartline.shared.model.EcgResult
import com.heartline.shared.model.RecordKind
import com.heartline.shared.model.RecordSummary
import com.heartline.shared.profile.StressLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

class WidgetDataTest {
    private val zone = ZoneOffset.UTC
    private val day = LocalDate.of(2026, 9, 24).atStartOfDay(zone).toInstant().toEpochMilli()
    private val formatter = RecordFormatter("Today", "Yesterday", zone = zone, locale = Locale.US) { LocalDate.of(2026, 9, 24) }

    private fun record(kind: RecordKind, atMs: Long, summary: RecordSummary) =
        StoredRecord(RecordEntity("$kind-$atMs", kind, atMs, 30_000, 0, 0, "", null, receivedAtMs = atMs), summary)

    private fun minute(hour: Int, minute: Int, bpm: Int, resting: Boolean = true) =
        HrMinuteEntity(day + (hour * 60 + minute) * 60_000L, bpm, bpm - 3, bpm + 4, 40.0, resting)

    @Test
    fun emptyDataGivesAnEmptySnapshot() {
        val s = WidgetSnapshots.build(emptyMap(), emptyList(), null, formatter, day + 3_600_000, zone)
        assertEquals(WidgetSnapshot(), s)
    }

    @Test
    fun latestOfEachKindAndTodaysHeartRate() {
        val minutes = (0 until 10).map { minute(3, it, 55 + it % 3) } + minute(14, 30, 72, resting = false)
        val s = WidgetSnapshots.build(
            mapOf(
                RecordKind.ECG to listOf(record(RecordKind.ECG, day + 9 * 3_600_000, RecordSummary.Ecg(68, EcgResult.SINUS_RHYTHM, 0f))),
                RecordKind.BLOOD_PRESSURE to listOf(record(RecordKind.BLOOD_PRESSURE, day + 8 * 3_600_000, RecordSummary.BloodPressure(135, 85, 70))),
                RecordKind.STRESS to listOf(record(RecordKind.STRESS, day + 13 * 3_600_000, RecordSummary.Stress(72, 18.0))),
                RecordKind.SPO2 to listOf(record(RecordKind.SPO2, day + 7 * 3_600_000, RecordSummary.Spo2(97, 64, false))),
            ),
            minutes,
            null,
            formatter,
            day + 15 * 3_600_000,
            zone,
        )
        val hr = s.heartRate!!
        assertEquals(72, hr.bpm)
        assertEquals(formatter.date(day) + " " + formatter.time(day + (14 * 60 + 30) * 60_000L), hr.at)
        assertEquals(52, hr.min)
        assertEquals(76, hr.max)
        // 03:00–03:30 and 14:30–15:00 buckets.
        assertEquals(listOf(6, 29), hr.day.map { it.index })
        assertEquals(listOf(11), hr.recent(nowSlot = 29).map { it.index })
        assertEquals(EcgResult.SINUS_RHYTHM, s.ecg!!.result)
        assertEquals(BpCategory.HIGH_STAGE_1, s.bp!!.category)
        assertEquals(StressLevel.HIGH, s.stress!!.level)
        assertEquals(18, s.stress!!.hrvMs)
        assertEquals("97", s.spo2!!.value)
        assertEquals(WidgetSnapshot.Calibration.None, s.calibration)
        assertNull(s.temperature)
    }

    @Test
    fun recentKeepsTheLastSixHours() {
        val minutes = listOf(minute(6, 0, 60), minute(10, 0, 70), minute(14, 45, 80))
        val hr = WidgetSnapshots.build(emptyMap(), minutes, null, formatter, day + 15 * 3_600_000, zone).heartRate!!
        // Now = 14:45 → slot 29; the window is slots 18..29 (09:00–15:00).
        assertEquals(listOf(2, 11), hr.recent(29).map { it.index })
    }
}
