// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.sim

import com.heartline.shared.hr.AlertKind
import com.heartline.shared.hr.AlertSensitivity
import com.heartline.shared.hr.HealthAlert
import com.heartline.shared.hr.HeartHistory
import com.heartline.shared.hr.HeartLimits
import com.heartline.shared.hr.HeartTrend
import com.heartline.shared.hr.HrBatch
import com.heartline.shared.hr.HrContext
import com.heartline.shared.hr.HrSample
import com.heartline.shared.hr.MaxHr
import com.heartline.shared.hr.MonitorSettings
import com.heartline.shared.hr.MonitorState
import com.heartline.shared.irn.IrnState
import com.heartline.shared.profile.Sex
import com.heartline.wear.monitor.HeartMonitor
import com.heartline.wear.monitor.MonitorOutput
import kotlinx.coroutines.runBlocking
import java.time.ZoneOffset
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * A simulated wearer: resting heart rate, sleep dip, day-to-day drift (SD ~3 bpm, Quer 2020),
 * minute-to-minute noise, a circadian swing peaking in the afternoon (Avram 2019), workouts as a
 * share of the heart-rate reserve (Karvonen), walks, short stress spikes and coffee.
 */
data class Person(
    val name: String,
    val age: Int,
    val sex: Sex,
    val rest: Double,
    val sleepDip: Double = 0.13,
    val noise: Double = 3.0,
    val daySd: Double = 2.5,
    val circadian: Double = 4.0,
    /** True maximum minus the age formula (fit people often exceed the formula). */
    val maxOffset: Int = 0,
    val workoutDays: Set<Int> = setOf(1, 3, 5),
    val workoutMinutes: Int = 45,
    /** Share of the heart-rate reserve reached in a normal hard workout. */
    val workoutIntensity: Double = 0.85,
    /** Whether workouts are started on the watch (else only steps say "moving"). */
    val labelsWorkouts: Boolean = true,
    val walksPerDay: Int = 3,
) {
    val trueMax: Int get() = MaxHr.predicted(age) + maxOffset
}

/** Something abnormal put into the data, and the notification it should give. */
data class Event(val name: String, val startMs: Long, val endMs: Long, val expect: Expect)

enum class Expect { HIGH_REST, LOW_REST, LOW_SLEEP, HIGH_EXERCISE, TREND }

data class Result(
    val person: String,
    val algorithm: String,
    val alerts: List<HealthAlert>,
    val events: List<Event>,
    val detected: List<Event>,
    val missed: List<Event>,
    val falseAlerts: List<HealthAlert>,
    val finalLimits: HeartLimits?,
)

/**
 * Runs [days] days of one [person] through the watch's own [HeartMonitor] (the production code:
 * minutes, activity, baseline, limits, rules, trend), delivering passive heart rate like Health
 * Services does: every 5–10 minutes at rest and asleep, every 5 s in a workout, every minute on a
 * walk, in deliveries every 15 minutes.
 */
class HeartSimulation(
    private val person: Person,
    private val days: Int = 60,
    private val seed: Int = 7,
    private val sensitivity: AlertSensitivity = AlertSensitivity.STANDARD,
    /** Null: personal limits (production). Otherwise fixed limits, for comparison. */
    private val fixed: HeartLimits? = null,
) {
    private val random = Random(seed)

    /** Standard normal (Box–Muller). */
    private fun Random.nextGaussian(): Double = kotlin.math.sqrt(-2 * kotlin.math.ln(1 - nextDouble())) * kotlin.math.cos(2 * Math.PI * nextDouble())
    private val zone = ZoneOffset.UTC
    private val start = 20_000L * DAY // a Monday, 00:00 UTC
    val events = mutableListOf<Event>()

    /** Minute start → what the watch would say the wearer was doing (null = still/unknown). */
    private val activity = HashMap<Long, HrContext>()

    private val alerts = mutableListOf<HealthAlert>()
    private val output = object : MonitorOutput {
        var irn = IrnState()
        var state = MonitorState()

        override suspend fun loadIrnState() = irn

        override suspend fun saveIrnState(state: IrnState) {
            irn = state
        }

        override suspend fun loadMonitorState() = state

        override suspend fun saveMonitorState(state: MonitorState) {
            this.state = state
        }

        override suspend fun enqueueBatch(batch: HrBatch) = Unit

        override suspend fun enqueueAlert(alert: HealthAlert) {
            alerts += alert
        }

        override fun notify(alert: HealthAlert) = Unit
    }

    private var ids = 0
    private val monitor = HeartMonitor(
        output,
        { false },
        { MonitorSettings(alertSensitivity = sensitivity).normalized().copy(irregularRhythmEnabled = false) },
        activity = { activity[it] },
        age = { person.age },
        sex = { person.sex },
        zone = zone,
        limitsFor = { h: HeartHistory, day: Long, cfg: MonitorSettings, a: Int?, x: Sex? ->
            fixed ?: com.heartline.shared.hr.HeartBaseline.limits(h, day, cfg.alertSensitivity, a, x)
        },
        newId = { "a${ids++}" },
    )

    fun run(inject: HeartSimulation.() -> Unit = {}): Result {
        inject()
        val samples = generate()
        runBlocking {
            var batch = mutableListOf<HrSample>()
            var deliverAt = start + DELIVERY
            for (s in samples) {
                if (s.tsMs >= deliverAt) {
                    deliver(batch)
                    batch = mutableListOf()
                    deliverAt += DELIVERY
                }
                batch += s
            }
            deliver(batch)
        }
        return score()
    }

    private suspend fun deliver(batch: List<HrSample>) {
        if (batch.isEmpty()) return
        batch.sortedBy { it.tsMs }.forEach { monitor.onSample(it) }
        monitor.closeOpenMinutes()
        monitor.flushBatch()
    }

    // --- Events -------------------------------------------------------------------------------

    private val modifiers = mutableListOf<(Long, Double) -> Double>()

    /** Heart rate held at [factor] × the normal while awake and still, for [minutes] (e.g. a resting tachycardia). */
    fun restEpisode(day: Int, hour: Int, minutes: Int, factor: Double, expect: Expect?) {
        val from = start + day * DAY + hour * HOUR
        val to = from + minutes * MIN
        restOnly += from until to
        modifiers += { t, bpm -> if (t in from until to) restingOn(t) * factor + (bpm - restingOn(t)) * 0.3 else bpm }
        if (expect != null) events += Event("rest x$factor day $day", from, to, expect)
    }

    /** Heart rate in sleep at [factor] × the sleep normal for [minutes] after 02:00. */
    fun sleepEpisode(day: Int, minutes: Int, factor: Double, expect: Expect?) {
        val from = start + day * DAY + 2 * HOUR
        val to = from + minutes * MIN
        modifiers += { t, bpm -> if (t in from until to) bpm * factor else bpm }
        if (expect != null) events += Event("sleep x$factor day $day", from, to, expect)
    }

    /** A workout on [day] that ends with [minutes] above the true maximum (overexertion). */
    fun overexertion(day: Int, minutes: Int, above: Int) {
        val from = workoutStart(day) + (person.workoutMinutes - minutes) * MIN
        val to = from + minutes * MIN
        extraWorkoutDays += day
        modifiers += { t, bpm -> if (t in from until to) (person.trueMax + above).toDouble() else bpm }
        events += Event("overexertion day $day", from, to, Expect.HIGH_EXERCISE)
    }

    /** Illness: resting and sleeping heart rate up by [rise] bpm for [nights] nights from [day]. */
    fun illness(day: Int, nights: Int, rise: Double) {
        val from = start + day * DAY - 6 * HOUR
        val to = start + (day + nights) * DAY + 12 * HOUR
        modifiers += { t, bpm -> if (t in from until to) bpm + rise else bpm }
        events += Event("illness +$rise day $day", start + (day + 2) * DAY, start + (day + nights + 1) * DAY + 12 * HOUR, Expect.TREND)
    }

    /** The watch was off the wrist (charging, a day without it): no readings at all. */
    fun offWrist(day: Int, fromHour: Int, hours: Int) {
        val from = start + day * DAY + fromHour * HOUR
        offRanges += from until from + hours * HOUR
    }

    private val offRanges = mutableListOf<LongRange>()
    private val restOnly = mutableListOf<LongRange>()
    private val extraWorkoutDays = mutableSetOf<Int>()

    // --- Physiology ---------------------------------------------------------------------------

    private val dayOffset = DoubleArray(days + 2) { 0.0 }

    private fun restingOn(t: Long): Double {
        val d = ((t - start) / DAY).toInt().coerceIn(0, days)
        val hour = ((t - start) % DAY) / HOUR.toDouble()
        // Circadian: lowest around 05:00, highest around 17:00.
        return person.rest + dayOffset[d] + person.circadian * sin((hour - 11) / 24.0 * 2 * Math.PI)
    }

    private fun workoutStart(day: Int) = start + day * DAY + 18 * HOUR

    private fun generate(): List<HrSample> {
        for (i in dayOffset.indices) dayOffset[i] = random.nextGaussian() * person.daySd
        val out = ArrayList<HrSample>(200_000)
        val walks = (0 until days).associateWith { (0 until person.walksPerDay).map { 8 * HOUR + random.nextLong(0, 9 * HOUR) } }
        val spikes = (0 until days).associateWith { (0 until 2).map { 9 * HOUR + random.nextLong(0, 10 * HOUR) } }
        var nextRest = start
        var t = start
        val end = start + days * DAY
        while (t < end) {
            val day = ((t - start) / DAY).toInt()
            val inDay = (t - start) % DAY
            val sleepStart = 23 * HOUR - 30 * MIN + (dayOffset[day] * 600_000).toLong().coerceIn(-HOUR, HOUR)
            val asleep = inDay < 7 * HOUR || inDay >= sleepStart
            val workout = (day % 7 in person.workoutDays || day in extraWorkoutDays) &&
                (t - workoutStart(day)) in 0 until person.workoutMinutes * MIN
            val sinceWorkout = t - (workoutStart(day) + person.workoutMinutes * MIN)
            val walking = !asleep && !workout && walks.getValue(day).any { (inDay - it) in 0 until 15 * MIN } && restOnly.none { t in it }
            val minute = t / MIN * MIN
            val rest = restingOn(t)
            var bpm = when {
                asleep -> rest * (1 - person.sleepDip)
                workout -> {
                    // Warm-up over 8 minutes, then the workout's level with intervals.
                    val warm = ((t - workoutStart(day)) / (8.0 * MIN)).coerceAtMost(1.0)
                    val level = person.workoutIntensity * (0.92 + 0.08 * sin((t - workoutStart(day)) / (4.0 * MIN) * Math.PI))
                    rest + warm * level * (person.trueMax - rest)
                }
                walking -> rest + 0.35 * (person.trueMax - rest)
                // Recovery: back to rest over ~10 minutes after a workout.
                sinceWorkout in 0 until 40 * MIN && (day % 7 in person.workoutDays || day in extraWorkoutDays) ->
                    rest + person.workoutIntensity * (person.trueMax - rest) * 0.6 * exp(-sinceWorkout / (10.0 * MIN))
                else -> rest
            }
            // Short stress spikes (6 min, +20) and a coffee (+8 for an hour): normal, never alerts.
            if (!asleep && !workout && spikes.getValue(day).any { (inDay - it) in 0 until 6 * MIN }) bpm += 20
            if (!asleep && !workout && (inDay - 8 * HOUR) in 0 until HOUR) bpm += 8
            modifiers.forEach { bpm = it(t, bpm) }
            bpm += random.nextGaussian() * person.noise

            when {
                asleep -> activity[minute] = HrContext.SLEEP
                workout -> activity[minute] = if (person.labelsWorkouts) HrContext.EXERCISE else HrContext.ACTIVE
                walking -> activity[minute] = HrContext.ACTIVE
            }
            val step = when {
                workout -> 5_000L
                walking -> MIN
                else -> 0L
            }
            val worn = offRanges.none { t in it }
            if (step > 0) {
                if (worn) out += HrSample(t, bpm.toInt().coerceIn(25, 240), emptyList())
                t += step
            } else {
                if (t >= nextRest) {
                    if (worn) out += HrSample(t, bpm.toInt().coerceIn(25, 240), emptyList())
                    nextRest = t + random.nextLong(5 * MIN, 10 * MIN)
                }
                t += MIN
            }
        }
        return out
    }

    // --- Scoring ------------------------------------------------------------------------------

    private fun matches(a: HealthAlert, e: Event): Boolean {
        val inWindow = a.atMs in e.startMs..(e.endMs + 45 * MIN)
        return inWindow && when (e.expect) {
            Expect.HIGH_REST -> a.trend == null && a.kind == AlertKind.HIGH_HEART_RATE && a.context != HrContext.EXERCISE
            Expect.LOW_REST -> a.trend == null && a.kind == AlertKind.LOW_HEART_RATE && a.context == HrContext.REST
            Expect.LOW_SLEEP -> a.trend == null && a.kind == AlertKind.LOW_HEART_RATE && a.context == HrContext.SLEEP
            Expect.HIGH_EXERCISE -> a.trend == null && a.kind == AlertKind.HIGH_HEART_RATE && a.context == HrContext.EXERCISE
            Expect.TREND -> a.trend == HeartTrend.ELEVATED_RESTING
        }
    }

    private fun score(): Result {
        val detected = events.filter { e -> alerts.any { matches(it, e) } }
        // An alert is false when no injected event explains it; an illness may also raise a high
        // resting alert legitimately, so alerts during an illness count as explained.
        val falseAlerts = alerts.filter { a -> events.none { e -> matches(a, e) || (e.expect == Expect.TREND && a.atMs in (e.startMs - 3 * DAY)..e.endMs) } }
        return Result(
            person.name,
            if (fixed == null) "personal ($sensitivity)" else "fixed",
            alerts.toList(),
            events.toList(),
            detected,
            events - detected.toSet(),
            falseAlerts,
            monitor.limits,
        )
    }

    companion object {
        const val MIN = 60_000L
        const val HOUR = 60 * MIN
        const val DAY = 24 * HOUR
        const val DELIVERY = 15 * MIN

        /** The earlier fixed limits (120/40, 35 asleep) with the age formula in exercise. */
        fun fixedLimits(age: Int) = HeartLimits(65, 57, high = 120, low = 40, sleepLow = 35, exerciseMax = MaxHr.predicted(age))
    }
}
