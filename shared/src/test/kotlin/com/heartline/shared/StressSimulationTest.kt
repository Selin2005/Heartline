// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared

import com.heartline.shared.hr.HealthAlert
import com.heartline.shared.hr.HrContext
import com.heartline.shared.hr.MonitorSettings
import com.heartline.shared.stress.StressBaseline
import com.heartline.shared.stress.StressHistory
import com.heartline.shared.stress.StressMonitor
import java.io.File
import java.time.ZoneOffset
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 60 days of background stress through [StressMonitor], driven as the watch's rhythm windows
 * drive it: a window every 15 minutes, awake 7:00–23:00 and asleep otherwise; 30 % of awake
 * windows skipped (moving; 10 % during a stressful spell at a desk), exercise excluded, and the half hour after it still counted as rest
 * while the body recovers (heart rate +15, RMSSD −25 %).
 *
 * Model: ln RMSSD noise SD 0.25 between windows, day-to-day SD 0.1, afternoon −0.05; heart rate
 * noise SD 3 bpm; a coffee at 9:00 every day (one window: RMSSD −33 %, +6 bpm).
 * Report: build/reports/stress-simulation.md (summarised in docs/algorithms/STRESS_MONITORING.md).
 */
class StressSimulationTest {
    /** [required]: must be caught; otherwise only reported (a known limit). */
    private data class Episode(
        val name: String,
        val day: Int,
        val fromHour: Double,
        val toHour: Double,
        val lnShift: Double,
        val bpmShift: Int,
        val required: Boolean = true
    )

    private data class Person(
        val name: String,
        val rmssd: Double = 40.0,
        val bpm: Int = 68,
        val exerciseEvery: Int = 0,
        val illness: IntRange? = null,
        val stressWeek: IntRange? = null
    )

    private class World(val person: Person, seed: Int, val days: Int = 60) {
        val random = Random(seed)
        val dayDrift = DoubleArray(days) { gauss(random) * 0.1 }
        val episodes = mutableListOf<Episode>()

        companion object {
            fun gauss(r: Random) = sqrt(-2 * ln(1 - r.nextDouble())) * cos(2 * Math.PI * r.nextDouble())
        }
    }

    private data class Run(
        val person: String,
        val alerts: List<HealthAlert>,
        val episodes: List<Episode>,
        val caught: List<Episode>,
        val falseAlerts: List<HealthAlert>,
        val insightDays: List<Int>,
        val maxPerDay: Int
    )

    private val zone = ZoneOffset.UTC
    private val start = 20_000L
    private fun ms(day: Int, minute: Int) = (start + day) * 86_400_000L + minute * 60_000L

    private fun simulate(w: World): Run {
        var ids = 0
        val monitor = StressMonitor(zone) { "s${ids++}" }
        val settings = MonitorSettings()
        var h = StressHistory()
        val alerts = mutableListOf<HealthAlert>()
        val insight = mutableListOf<Int>()
        val r = w.random
        val p = w.person
        for (day in 0 until w.days) {
            val ill = p.illness?.contains(day) == true
            val week = p.stressWeek?.contains(day) == true
            val exercise = p.exerciseEvery > 0 && day % p.exerciseEvery == 0
            for (slot in 0 until 96) {
                val minute = slot * 15
                val hour = minute / 60.0
                val asleep = hour < 7 || hour >= 23
                var ln = ln(p.rmssd) + w.dayDrift[day] + World.gauss(r) * 0.25
                var bpm = p.bpm + World.gauss(r) * 3
                val context = when {
                    asleep -> {
                        ln += 0.3 - if (week) 0.2 else 0.0
                        bpm -= 10
                        HrContext.SLEEP
                    }
                    exercise && hour in 17.0..18.25 -> HrContext.EXERCISE // the workout and its first 15 minutes
                    // Stress at a desk is mostly still: 10 % of its windows moving, 30 % otherwise.
                    r.nextDouble() < if (w.episodes.any {
                            it.day == day && hour >= it.fromHour && hour < it.toHour
                        }
                    ) {
                        0.1
                    } else {
                        0.3
                    } -> HrContext.ACTIVE
                    else -> HrContext.REST
                }
                if (context == HrContext.REST) {
                    if (hour >= 13) ln -= 0.05
                    if (minute == 9 * 60) {
                        ln += ln(0.67)
                        bpm += 6
                    }
                    if (exercise && hour in 18.5..19.0) {
                        ln += ln(0.75)
                        bpm += 15
                    }
                    if (ill) {
                        ln += ln(0.7)
                        bpm += 9
                    }
                    if (week) {
                        ln -= 0.3
                        bpm += 5
                    }
                    w.episodes.filter { it.day == day && hour >= it.fromHour && hour < it.toHour }.forEach {
                        ln += it.lnShift
                        bpm += it.bpmShift
                    }
                }
                val (next, _, alert) = monitor.onWindow(h, ms(day, minute), exp(ln), bpm.roundToInt(), context, settings, illness = ill)
                h = next
                alert?.let { alerts += it }
            }
            if (StressBaseline.limits(h, start + day).weekAboveUsual != null) insight += day
        }
        fun dayOf(a: HealthAlert) = (a.atMs / 86_400_000L - start).toInt()
        fun hourOf(a: HealthAlert) = (a.atMs % 86_400_000L) / 3_600_000.0
        val caught = w.episodes.filter { e -> alerts.any { dayOf(it) == e.day && hourOf(it) in e.fromHour..(e.toHour + 0.25) } }
        val falseAlerts = alerts.filter { a ->
            val d = dayOf(a)
            w.episodes.none { it.day == d } && p.stressWeek?.contains(d) != true
        }
        val perDay = alerts.groupingBy { dayOf(it) }.eachCount().values.maxOrNull() ?: 0
        return Run(p.name, alerts, w.episodes, caught, falseAlerts, insight, perDay)
    }

    private fun worlds(seed: Int): List<World> = listOf(
        World(Person("Typical adult, a coffee every morning"), seed),
        World(Person("Stressful afternoons at work"), seed + 1).apply {
            listOf(20, 33, 47).forEach { episodes += Episode("day $it 14:00–15:30", it, 14.0, 15.5, ln(0.6), 10) }
        },
        World(Person("Exercise every other day", exerciseEvery = 2), seed + 2),
        World(Person("Illness, days 40–42", illness = 40..42), seed + 3),
        World(Person("Endurance athlete (RMSSD 90, 48 bpm)", rmssd = 90.0, bpm = 48, exerciseEvery = 1), seed + 4),
        World(Person("Beta blocker (RMSSD 35, 58 bpm)", rmssd = 35.0, bpm = 58), seed + 5).apply {
            // Beta blockers blunt the heart-rate rise (+6 instead of +10): reported, not required.
            episodes += Episode("day 44 10:00–11:30 (blunted)", 44, 10.0, 11.5, ln(0.6), 6, required = false)
        },
        World(Person("A stressful week, days 30–36", stressWeek = 30..36), seed + 6)
    )

    @Test
    fun episodesAreCaughtWithoutFalseNotices() {
        val runs = worlds(7).map { simulate(it) }
        report(runs, randomRuns())
        runs.forEach { r ->
            assertTrue(
                "${r.person}: missed ${(
                    r.episodes.filter {
                        it.required
                    } - r.caught.toSet()
                    ).map {
                    it.name
                }} alerts ${r.alerts.map { describe(it) }}",
                r.caught.containsAll(r.episodes.filter { it.required })
            )
            assertTrue("${r.person}: false ${r.falseAlerts.map { describe(it) }}", r.falseAlerts.size <= 1)
            assertTrue("${r.person}: ${r.maxPerDay} notices in a day", r.maxPerDay <= StressMonitor.MAX_PER_DAY)
        }
        val week = runs.single { it.person.startsWith("A stressful week") }
        assertTrue("stressful week insight on ${week.insightDays}", week.insightDays.any { it in 33..40 })
        assertTrue(
            "insight outside the week: ${runs.filter {
                !it.person.startsWith("A stressful week")
            }.map { it.person to it.insightDays }}",
            runs.filter { !it.person.startsWith("A stressful week") }.all { it.insightDays.isEmpty() }
        )
    }

    /**
     * 105 more random histories (15 per wearer). Moderate spells sit a few noise SDs above a calm
     * day, so this is statistical: at least 90 % of them caught and at most 2 false notices in
     * about 6,300 days.
     */
    @Test
    fun resultsHoldAcrossRandomHistories() {
        val runs = randomRuns()
        val required = runs.sumOf { r -> r.episodes.count { it.required } }
        val caught = runs.sumOf { r -> r.caught.count { it.required } }
        val falseAlerts = runs.flatMap { r -> r.falseAlerts.map { "${r.person}: ${describe(it)}" } }
        assertTrue("caught $caught of $required", caught >= required * 0.9)
        assertTrue("false: $falseAlerts", falseAlerts.size <= 2)
        assertTrue(runs.all { it.maxPerDay <= StressMonitor.MAX_PER_DAY })
    }

    private fun randomRuns() = (1..15).flatMap { s -> worlds(200 + s * 10).map { simulate(it) } }

    private fun describe(a: HealthAlert) = "day ${a.atMs / 86_400_000L - start} %.2fh score ${a.value}".format(
        (a.atMs % 86_400_000L) / 3_600_000.0
    )

    private fun report(runs: List<Run>, more: List<Run>) {
        val text = buildString {
            appendLine("# Stress simulation (60 days per wearer)")
            appendLine()
            appendLine("| Wearer | Episodes | Caught | Notices | False notices | Insight days |")
            appendLine("|---|---|---|---|---|---|")
            runs.forEach { r ->
                appendLine(
                    "| ${r.person} | ${r.episodes.size} | ${r.caught.size} | ${r.alerts.joinToString {
                        describe(it)
                    }.ifEmpty { "–" }} | ${r.falseAlerts.size} | ${r.insightDays.joinToString().ifEmpty { "–" }} |"
                )
            }
            appendLine()
            appendLine(
                "Fifteen more random histories per wearer (${more.size} runs, ${more.size * 60} days): caught ${more.sumOf { r ->
                    r.caught.count { it.required }
                }} of " +
                    "${more.sumOf { r ->
                        r.episodes.count { it.required }
                    }} required episodes, false notices ${more.sumOf { it.falseAlerts.size }}; " +
                    "blunted (beta blocker) episodes caught ${more.sumOf { r ->
                        r.caught.count { !it.required }
                    }} of ${more.sumOf { r -> r.episodes.count { !it.required } }}."
            )
        }
        File("build/reports").mkdirs()
        File("build/reports/stress-simulation.md").writeText(text)
        println(text)
    }
}
