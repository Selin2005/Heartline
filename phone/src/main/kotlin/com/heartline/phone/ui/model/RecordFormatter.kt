// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.ui.model

import com.heartline.phone.data.StoredRecord
import com.heartline.shared.model.EcgResult
import com.heartline.shared.model.RecordSummary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Turns stored records into display strings; clock, zone and locale are injectable for tests. */
class RecordFormatter(
    private val todayLabel: String,
    private val yesterdayLabel: String,
    private val calibrationDaysPattern: String = "Calibration valid · %d days left",
    val calibrationNeeded: String = "Calibration needed",
    /** Under a value the watch measured by itself (background stress). */
    val backgroundLabel: String = "Measured by your watch",
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val locale: Locale = Locale.getDefault(),
    private val today: () -> LocalDate = { LocalDate.now(zone) },
) {
    /** A short day name ("Mon"). */
    fun weekday(day: LocalDate): String = day.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, locale)

    private val month = DateTimeFormatter.ofPattern("LLLL yyyy", locale)
    private val shortDate = DateTimeFormatter.ofPattern("MMM d", locale)
    private val shortDateYear = DateTimeFormatter.ofPattern("MMM d, yyyy", locale)
    private val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)

    fun month(ms: Long): String = month.format(local(ms))

    fun date(ms: Long): String {
        val date = local(ms).toLocalDate()
        val now = today()
        return when {
            date == now -> todayLabel
            date == now.minusDays(1) -> yesterdayLabel
            date.year == now.year -> shortDate.format(date)
            else -> shortDateYear.format(date)
        }
    }

    fun time(ms: Long): String = time.format(local(ms))

    fun calibrationDaysLeft(days: Int): String = calibrationDaysPattern.format(days)

    fun ecg(record: StoredRecord, samples: FloatArray? = null): EcgRecordUi {
        val summary = record.summary as RecordSummary.Ecg
        val e = record.entity
        return EcgRecordUi(
            id = e.id,
            month = month(e.startedAtMs),
            date = date(e.startedAtMs),
            time = time(e.startedAtMs),
            result = summary.result ?: EcgResult.INCONCLUSIVE,
            averageBpm = summary.averageBpm,
            symptoms = summary.symptoms,
            durationSec = (e.durationMs / 1000).toInt(),
            sampleRateHz = e.sampleRateHz,
            samples = samples,
            metrics = summary.metrics,
        )
    }

    private fun local(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone)
}
