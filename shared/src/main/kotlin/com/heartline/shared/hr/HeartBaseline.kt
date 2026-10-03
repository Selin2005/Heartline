// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.hr

import com.heartline.shared.profile.Sex
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.serialization.Serializable

/**
 * One day of the wearer's heart rate, as histograms of minute averages (bpm → count): awake and
 * still ([rest]), asleep ([sleep], the night that ends on this day) and [exercise]. [unusual]: a
 * night with a raised resting heart rate (trend notice), left out of the baseline.
 */
@Serializable
data class DayStats(
    val day: Long,
    val rest: Map<Int, Int> = emptyMap(),
    val sleep: Map<Int, Int> = emptyMap(),
    val exercise: Map<Int, Int> = emptyMap(),
    val unusual: Boolean = false
)

/** The wearer's recent days, kept on the watch (about 30–50 kB as JSON). */
@Serializable
data class HeartHistory(val days: List<DayStats> = emptyList(), val lastTrendDay: Long? = null) {
    /** Adds one minute to its day. Minutes of other kinds (moving about) are not learnt from. */
    fun record(minute: HrMinute, zone: ZoneId): HeartHistory {
        val kind = minute.activity
        if (kind == HrContext.ACTIVE) return this
        val day = HeartBaseline.dayOf(minute.minuteStartMs, kind, zone)
        val current = days.firstOrNull { it.day == day } ?: DayStats(day)
        fun Map<Int, Int>.plus(bpm: Int) = toMutableMap().apply { merge(bpm, 1, Int::plus) }
        val updated = when (kind) {
            HrContext.REST -> current.copy(rest = current.rest.plus(minute.avgBpm))
            HrContext.SLEEP -> current.copy(sleep = current.sleep.plus(minute.avgBpm))
            HrContext.EXERCISE -> current.copy(exercise = current.exercise.plus(minute.avgBpm))
            HrContext.ACTIVE -> current
        }
        val keepFrom = day - HeartBaseline.EXERCISE_DAYS
        return copy(days = (days.filter { it.day != day && it.day > keepFrom } + updated).sortedBy { it.day })
    }

    fun markUnusual(dayList: Collection<Long>): HeartHistory = copy(
        days = days.map {
            if (it.day in
                dayList
            ) {
                it.copy(unusual = true)
            } else {
                it
            }
        }
    )
}

/**
 * The limits in use for one wearer, and the normal they come from. Sent to the phone with each
 * batch so it shows exactly what the watch uses.
 */
@Serializable
data class HeartLimits(
    /** Typical heart rate awake and still, and asleep (blended with the population guess while learning). */
    val restNormal: Int,
    val sleepNormal: Int,
    /** The wearer's usual range at rest (5th–95th percentile), null until there is enough data. */
    val restLow: Int? = null,
    val restHigh: Int? = null,
    val high: Int,
    val low: Int,
    val sleepLow: Int,
    val exerciseMax: Int,
    /** 0..1: how much of [restNormal] / [sleepNormal] comes from the wearer's own data. */
    val restConfidence: Double = 0.0,
    val sleepConfidence: Double = 0.0,
    val sensitivity: AlertSensitivity = AlertSensitivity.STANDARD
) {
    /** Still mostly the population guess. */
    val learning: Boolean get() = restConfidence < HeartBaseline.LEARNT
}

/** The notice for a resting heart rate that stays raised over several nights. */
data class RestingTrend(val nightsAbove: Int, val latestNight: Int, val usual: Int, val threshold: Int, val days: List<Long>)

/**
 * Personal heart-rate limits. Each clinical number is defined for an average adult whose resting
 * heart rate is [REFERENCE_REST] bpm (Quer 2020), so a limit is that number's ratio to the
 * reference, applied to the wearer's own normal:
 *
 * | Sensitivity | High (rest/sleep) | Low awake | Low asleep |
 * |---|---|---|---|
 * | Low | 120/65: Apple/Fitbit default | 40/65: Apple default | 35/57 |
 * | Standard | 100/65: tachycardia at rest (AHA) | 45/65: middle of the lowest 2nd percentile, 40–55 (ACC/AHA/HRS 2018) | 40/57 |
 * | High | 95/65: 95th percentile of real-world heart rate over 60 (Avram 2019) | 50/65: sinus bradycardia (ACC/AHA/HRS 2018) | 45/57 |
 *
 * 57 bpm is the reference asleep: the median sleep dip is 12.7 % (JAMA Intern Med 2010).
 * Limits stay outside the wearer's own usual range (1st/99th percentile ± 5 bpm) and inside
 * fixed safety bounds, so an abnormal normal can't hide a problem.
 *
 * Learning: the normal starts as a population guess (65 bpm, women +3, Quer 2020) and moves to
 * the wearer's own median as readings arrive: weight n / (n + [PRIOR_READINGS]). Until then the
 * limits are blended with the fixed defaults by the same confidence.
 */
object HeartBaseline {
    const val REFERENCE_REST = 65.0
    const val SLEEP_DIP = 0.127
    val REFERENCE_SLEEP = REFERENCE_REST * (1 - SLEEP_DIP)

    /** The population guess counts as this many readings (about 4 hours of passive heart rate). */
    const val PRIOR_READINGS = 24.0
    const val LEARNT = 0.8
    const val WINDOW_DAYS = 28
    const val EXERCISE_DAYS = 90

    /** Readings needed before the wearer's own 1st/99th percentile is trusted as a guard. */
    const val GUARD_READINGS = 60
    const val GUARD_MARGIN = 5

    val HIGH_BOUNDS = 90..130
    val LOW_BOUNDS = 35..50
    val SLEEP_LOW_BOUNDS = 30..45

    /**
     * The lowest sleep limit, for wearers whose own nights go below [SLEEP_LOW_BOUNDS]. Readings
     * under 25 bpm are dropped as implausible, so a limit must stay a few bpm above that.
     */
    const val SLEEP_ABSOLUTE_MIN = 28

    /**
     * Limits used while nothing is learnt yet: the earlier high default, and for low the safety
     * floors. The earlier lows (40 awake, 35 asleep) are above many fit people's normal sleeping
     * rate; the simulation showed false low alerts on an athlete's first night with them.
     */
    const val FIXED_HIGH = 120
    const val FIXED_LOW = 35
    const val FIXED_SLEEP_LOW = 30

    /** The learned exercise maximum may exceed the age formula by its own spread (~10 bpm) × 1.5. */
    const val EXERCISE_MAX_ABOVE_AGE = 15
    const val EXERCISE_MIN_READINGS = 30
    const val EXERCISE_MIN_DAYS = 3

    data class Ratios(val high: Double, val low: Double, val sleepLow: Double)

    fun ratios(sensitivity: AlertSensitivity) = when (sensitivity) {
        AlertSensitivity.LOW -> Ratios(120 / REFERENCE_REST, 40 / REFERENCE_REST, 35 / REFERENCE_SLEEP)
        AlertSensitivity.STANDARD -> Ratios(100 / REFERENCE_REST, 45 / REFERENCE_REST, 40 / REFERENCE_SLEEP)
        AlertSensitivity.HIGH -> Ratios(95 / REFERENCE_REST, 50 / REFERENCE_REST, 45 / REFERENCE_SLEEP)
    }

    /** Endurance athletes' resting rate is typically around 50 (first guess only; their own data decides). */
    const val ENDURANCE_REST = 50.0

    /** In pregnancy the resting rate rises week by week: a 14-day normal follows it. */
    const val PREGNANCY_WINDOW_DAYS = 14

    fun priorRest(sex: Sex?, health: HealthContext = HealthContext()): Double =
        if (health.enduranceTraining) ENDURANCE_REST else REFERENCE_REST + if (sex == Sex.FEMALE) 3 else 0

    /** Day a minute belongs to: its local date, or for sleep the date the night ends (18:00–18:00). */
    fun dayOf(ms: Long, kind: HrContext, zone: ZoneId): Long {
        val shifted = if (kind == HrContext.SLEEP) ms + 6 * 3_600_000L else ms
        return Instant.ofEpochMilli(shifted).atZone(zone).toLocalDate().toEpochDay()
    }

    fun limits(
        history: HeartHistory,
        today: Long,
        sensitivity: AlertSensitivity,
        age: Int?,
        sex: Sex?,
        health: HealthContext = HealthContext()
    ): HeartLimits {
        val window = if (health.pregnant) PREGNANCY_WINDOW_DAYS else WINDOW_DAYS
        val recent = history.days.filter { it.day in (today - window + 1)..today && !it.unusual }
        val rest = Histogram.merge(recent.map { it.rest })
        val sleep = Histogram.merge(recent.map { it.sleep })
        val r = ratios(sensitivity)

        val restC = confidence(rest.count)
        val restNormal = blend(rest.median(), priorRest(sex, health), restC)
        val sleepC = confidence(sleep.count)
        val sleepNormal = blend(sleep.median(), restNormal * (1 - SLEEP_DIP), sleepC)

        // Limits come from the wearer's own data only, mixed with the safe defaults by confidence:
        // a population guess must not set an athlete's first-night low limit.
        val restOwn = rest.median() ?: restNormal
        val sleepOwn = sleep.median() ?: restOwn * (1 - SLEEP_DIP)
        var high = restOwn * r.high
        var low = restOwn * r.low
        var sleepLow = sleepOwn * r.sleepLow
        if (rest.count >= GUARD_READINGS) {
            high = maxOf(high, rest.percentile(0.99) + GUARD_MARGIN)
            low = minOf(low, rest.percentile(0.01) - GUARD_MARGIN)
        }
        // Fit people's own nights can go below the sleep floor (sleeping rates in the low 30s and
        // below are common in athletes): their own 1st percentile − 5 may go down to 28.
        val sleepOwnFloor = if (sleep.count >= GUARD_READINGS) sleep.percentile(0.01) - GUARD_MARGIN else null
        if (sleepOwnFloor != null) sleepLow = minOf(sleepLow, sleepOwnFloor)

        return HeartLimits(
            restNormal = restNormal.roundToInt(),
            sleepNormal = sleepNormal.roundToInt(),
            restLow = rest.takeIf { it.count >= GUARD_READINGS }?.percentile(0.05)?.roundToInt(),
            restHigh = rest.takeIf { it.count >= GUARD_READINGS }?.percentile(0.95)?.roundToInt(),
            high = mix(high.coerceIn(HIGH_BOUNDS), FIXED_HIGH, restC).coerceIn(HIGH_BOUNDS),
            low = mix(low.coerceIn(LOW_BOUNDS), FIXED_LOW, restC).coerceIn(LOW_BOUNDS),
            sleepLow = mix(sleepLow.coerceIn(SLEEP_LOW_BOUNDS), FIXED_SLEEP_LOW, sleepC).coerceIn(SLEEP_LOW_BOUNDS).let { limit ->
                sleepOwnFloor?.takeIf { it < limit }?.let { maxOf(it, SLEEP_ABSOLUTE_MIN.toDouble()).roundToInt() } ?: limit
            },
            exerciseMax = exerciseMax(history, today, age, health.rateLowering),
            restConfidence = restC,
            sleepConfidence = sleepC,
            sensitivity = sensitivity
        )
    }

    /**
     * Age formula (Tanaka), raised to the wearer's own hardest workouts (99th percentile), at most
     * +15. Only earlier days count, from at least [EXERCISE_MIN_DAYS] days with exercise: a peak
     * today must not raise today's limit (the 99th percentile of one short session is its peak).
     */
    fun exerciseMax(history: HeartHistory, today: Long, age: Int?, rateLowering: Boolean = false): Int {
        val predicted = MaxHr.predicted(age, rateLowering)
        val days = history.days.filter { it.day in (today - EXERCISE_DAYS + 1) until today && it.exercise.isNotEmpty() }
        val exercise = Histogram.merge(days.map { it.exercise })
        if (days.size < EXERCISE_MIN_DAYS || exercise.count < EXERCISE_MIN_READINGS) return predicted
        return exercise.percentile(0.99).roundToInt().coerceIn(predicted, predicted + EXERCISE_MAX_ABOVE_AGE)
    }

    /**
     * Raised resting heart rate over several nights (Quer 2020's "unusual increase": more than
     * two standard deviations above the wearer's own average on 3 of 5 nights; Mishra 2020 and
     * Alavi 2022 use the same 28-day baseline). At least 6 bpm, the latest night included.
     */
    fun restingTrend(history: HeartHistory, today: Long): RestingTrend? {
        val nights = history.days.mapNotNull { d ->
            Histogram(d.sleep).takeIf { it.count >= MIN_NIGHT_READINGS }?.let { d to it.median()!! }
        }
        val recent = nights.filter { (d, _) -> d.day in (today - TREND_NIGHTS + 1)..today }
        val baseline = nights.filter { (d, _) ->
            d.day in (today - WINDOW_DAYS - TREND_NIGHTS + 1)..(today - TREND_NIGHTS) && !d.unusual
        }.map { it.second }
        if (baseline.size < MIN_BASELINE_NIGHTS || recent.isEmpty()) return null
        val mean = baseline.average()
        val sd = sqrt(baseline.sumOf { (it - mean) * (it - mean) } / (baseline.size - 1).coerceAtLeast(1))
        val threshold = mean + maxOf(2 * sd, MIN_TREND_RISE)
        val above = recent.filter { it.second > threshold }
        val latest = recent.maxBy { it.first.day }
        if (above.size < TREND_REQUIRED || latest.second <= threshold) return null
        return RestingTrend(above.size, latest.second.roundToInt(), mean.roundToInt(), threshold.roundToInt(), above.map { it.first.day })
    }

    /**
     * Whether the night ending on [day] had a raised heart rate in sleep: more than 2 standard
     * deviations (at least 6 bpm) above the wearer's own average of the 28 nights before (the
     * same rise as [restingTrend], for one night). Used with skin temperature.
     */
    fun nightRaised(history: HeartHistory, day: Long): Boolean {
        val nights = history.days.mapNotNull { d ->
            Histogram(d.sleep).takeIf { it.count >= MIN_NIGHT_READINGS }?.let { d to it.median()!! }
        }
        val tonight = nights.firstOrNull { it.first.day == day }?.second ?: return false
        val baseline = nights.filter { (d, _) -> d.day in (day - WINDOW_DAYS)..(day - 1) && !d.unusual }.map { it.second }
        if (baseline.size < MIN_BASELINE_NIGHTS) return false
        val mean = baseline.average()
        val sd = sqrt(baseline.sumOf { (it - mean) * (it - mean) } / (baseline.size - 1).coerceAtLeast(1))
        return tonight > mean + maxOf(2 * sd, MIN_TREND_RISE)
    }

    /** The usual resting heart rate itself has been high for a week (only once well learnt). */
    fun highNormal(history: HeartHistory, today: Long, limits: HeartLimits): Boolean {
        val days = history.days.count { it.day > today - 7 && Histogram(it.rest).count >= 20 }
        return limits.restConfidence >= 0.9 && days >= 7 && limits.restNormal > HIGH_NORMAL
    }

    /** Karvonen zones: lower bounds at 50–90 % of the heart-rate reserve above [rest]. */
    fun zones(max: Int, rest: Int): List<Int> = listOf(50, 60, 70, 80, 90).map { rest + (max - rest) * it / 100 }

    private fun confidence(n: Int) = n / (n + PRIOR_READINGS)

    private fun blend(own: Double?, prior: Double, c: Double) = if (own == null) prior else own * c + prior * (1 - c)

    private fun mix(personal: Double, fixed: Int, c: Double) = (personal * c + fixed * (1 - c)).roundToInt()

    private fun Double.coerceIn(range: IntRange) = coerceIn(range.first.toDouble(), range.last.toDouble())

    const val MIN_NIGHT_READINGS = 20
    const val MIN_BASELINE_NIGHTS = 5
    const val TREND_NIGHTS = 5
    const val TREND_REQUIRED = 3
    const val MIN_TREND_RISE = 6.0
    const val HIGH_NORMAL = 100
}

/** A bpm → count histogram with robust statistics. */
class Histogram(private val counts: Map<Int, Int>) {
    val count = counts.values.sum()
    private val sorted = counts.toSortedMap()

    fun percentile(p: Double): Double {
        if (count == 0) return Double.NaN
        val target = p * (count - 1)
        var seen = 0
        for ((bpm, n) in sorted) {
            if (seen + n > target) return bpm.toDouble()
            seen += n
        }
        return sorted.lastKey().toDouble()
    }

    fun median(): Double? = if (count == 0) null else percentile(0.5)

    /** Median absolute deviation (×1.4826 ≈ standard deviation for normal data). */
    fun mad(): Double? {
        val m = median() ?: return null
        return Histogram(counts.entries.groupBy({ abs(it.key - m).roundToInt() }, { it.value }).mapValues { it.value.sum() }).median()
    }

    companion object {
        fun merge(maps: List<Map<Int, Int>>) =
            Histogram(maps.flatMap { it.entries }.groupBy({ it.key }, { it.value }).mapValues { it.value.sum() })
    }
}
