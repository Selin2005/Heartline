// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.profile

import com.heartline.shared.model.Metric
import com.heartline.shared.model.RecordMeta
import com.heartline.shared.model.RecordSummary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/** The daily check-ins the user wants to do (set on the phone, shown on the watch). */
object DailyGoal {
    val DEFAULT = listOf(Metric.ECG, Metric.BLOOD_PRESSURE, Metric.SPO2)

    /** Which metrics were measured on each local day. */
    fun byDay(records: List<RecordMeta>, zone: ZoneId): Map<LocalDate, Set<Metric>> = records.groupBy {
        Instant.ofEpochMilli(it.startedAtMs).atZone(zone).toLocalDate()
    }.mapValues { (_, l) -> l.map { it.kind.metric }.toSet() }

    fun complete(done: Set<Metric>?, goal: List<Metric>): Boolean = goal.isNotEmpty() && done != null && done.containsAll(goal)
}

/** Days in a row with every daily check-in done. */
object Streak {
    /** Counts back from today (or yesterday, while today is still open). */
    fun of(byDay: Map<LocalDate, Set<Metric>>, goal: List<Metric>, today: LocalDate): Int {
        if (goal.isEmpty()) return 0
        var day = if (DailyGoal.complete(byDay[today], goal)) today else today.minusDays(1)
        var n = 0
        while (DailyGoal.complete(byDay[day], goal)) {
            n++
            day = day.minusDays(1)
        }
        return n
    }
}

/** The user's own usual range for a metric: the middle half of their last 30 days. */
data class Baseline(val low: Float, val median: Float, val high: Float, val count: Int) {
    enum class Place { BELOW, USUAL, ABOVE }

    fun place(value: Float): Place = when {
        value < low -> Place.BELOW
        value > high -> Place.ABOVE
        else -> Place.USUAL
    }

    /** Whole-number difference from the median (e.g. "5 below your usual"). */
    fun delta(value: Float): Int = (value - median).roundToInt()
}

object PersonalBaseline {
    const val DAYS = 30
    const val MIN_READINGS = 5

    /** The number a metric's baseline is kept on (systolic for blood pressure), or null. */
    fun value(summary: RecordSummary): Float? = when (summary) {
        is RecordSummary.BloodPressure -> summary.systolic.toFloat()
        is RecordSummary.Spo2 -> summary.percent.toFloat()
        is RecordSummary.Stress -> summary.score.toFloat()
        is RecordSummary.Ecg -> summary.averageBpm?.toFloat()
        is RecordSummary.SkinTemperature -> summary.skinCelsius
        is RecordSummary.BodyComposition -> summary.bodyFatPercent
        is RecordSummary.HeartRate -> summary.bpm.toFloat()
    }

    /** Quartiles of [values]; null with fewer than [MIN_READINGS]. */
    fun of(values: List<Float>): Baseline? {
        if (values.size < MIN_READINGS) return null
        val s = values.sorted()
        fun q(p: Float): Float {
            val i = p * (s.size - 1)
            val lo = s[i.toInt()]
            val hi = s[minOf(i.toInt() + 1, s.lastIndex)]
            return lo + (hi - lo) * (i - i.toInt())
        }
        return Baseline(q(0.25f), q(0.5f), q(0.75f), s.size)
    }

    /** Baselines of every metric from the last [DAYS] days of [records], leaving out [exclude] (the reading being judged). */
    fun all(records: List<RecordMeta>, nowMs: Long, exclude: String? = null): Map<Metric, Baseline> =
        records.filter { nowMs - it.startedAtMs <= DAYS * 86_400_000L && it.id != exclude }
            .groupBy { it.kind.metric }
            .mapNotNull { (metric, list) -> of(list.mapNotNull { value(it.summary) })?.let { metric to it } }
            .toMap()
}

/** A week in numbers, for the Friday summary. */
data class WeekSummary(
    val measurements: Int,
    val byMetric: Map<Metric, Int>,
    val goalDays: Int,
    val bpAverage: Pair<Int, Int>?,
    val bpChange: Int?,
    val streak: Int
) {
    companion object {
        fun of(records: List<RecordMeta>, goal: List<Metric>, today: LocalDate, zone: ZoneId): WeekSummary {
            val start = today.minusDays(6)
            fun inWeek(r: RecordMeta, from: LocalDate) = Instant.ofEpochMilli(r.startedAtMs).atZone(zone).toLocalDate().let {
                !it.isBefore(from) &&
                    !it.isAfter(from.plusDays(6))
            }
            val week = records.filter { inWeek(it, start) }
            val before = records.filter { inWeek(it, start.minusDays(7)) }
            fun bp(list: List<RecordMeta>) = list.mapNotNull { it.summary as? RecordSummary.BloodPressure }.takeIf { it.isNotEmpty() }
                ?.let { l -> l.map { it.systolic }.average().roundToInt() to l.map { it.diastolic }.average().roundToInt() }
            val byDay = DailyGoal.byDay(records, zone)
            val avg = bp(week)
            return WeekSummary(
                measurements = week.size,
                byMetric = week.groupingBy { it.kind.metric }.eachCount(),
                goalDays = (0..6).count { DailyGoal.complete(byDay[start.plusDays(it.toLong())], goal) },
                bpAverage = avg,
                bpChange = avg?.let { a -> bp(before)?.let { a.first - it.first } },
                streak = Streak.of(byDay, goal, today)
            )
        }
    }
}
