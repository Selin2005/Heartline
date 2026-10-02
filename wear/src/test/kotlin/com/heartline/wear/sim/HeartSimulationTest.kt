// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.sim

import com.heartline.shared.hr.AlertSensitivity
import com.heartline.shared.hr.HeartTrend
import com.heartline.shared.profile.Sex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 60 days of six simulated wearers through the watch's real monitor, with personal limits and,
 * for comparison, the earlier fixed limits. Each wearer has normal days (workouts, walks, stress
 * spikes, coffee, recovery, nights, hours without the watch) and a few injected abnormal episodes
 * that should notify. Episodes are clearly abnormal for the person (at least ~15 % beyond the
 * standard limit, e.g. 1.8 × the resting rate where the limit is 1.54 ×); an episode right at
 * the limit may or may not notify, as with any threshold.
 * The report is written to build/reports/heart-simulation.md (and summarised in
 * docs/algorithms/HEART_MONITORING.md).
 */
class HeartSimulationTest {
    private data class Case(val person: Person, val inject: HeartSimulation.() -> Unit)

    private val cases = listOf(
        Case(Person("Typical adult (40, resting 65)", 40, Sex.MALE, rest = 65.0)) {
            restEpisode(day = 20, hour = 14, minutes = 40, factor = 1.8, expect = Expect.HIGH_REST)
            restEpisode(day = 34, hour = 15, minutes = 40, factor = 0.55, expect = Expect.LOW_REST)
            overexertion(day = 45, minutes = 5, above = 12)
            illness(day = 50, nights = 4, rise = 11.0)
        },
        Case(Person("High normal (35, F, resting 84)", 35, Sex.FEMALE, rest = 84.0, workoutIntensity = 0.75)) {
            restEpisode(day = 25, hour = 14, minutes = 40, factor = 1.7, expect = Expect.HIGH_REST)
            illness(day = 44, nights = 4, rise = 10.0)
        },
        Case(
            Person(
                "Athlete (28, resting 47)", 28, Sex.MALE, rest = 47.0, sleepDip = 0.12, maxOffset = 10,
                workoutDays = setOf(0, 1, 2, 3, 4, 5), workoutMinutes = 60, workoutIntensity = 0.95, noise = 2.5,
            ),
        ) {
            // 100 bpm at rest is more than twice this athlete's normal: fixed 120 misses it.
            restEpisode(day = 22, hour = 14, minutes = 40, factor = 2.1, expect = Expect.HIGH_REST)
            sleepEpisode(day = 38, minutes = 60, factor = 0.55, expect = Expect.LOW_SLEEP)
        },
        Case(Person("Older adult (70, resting 72)", 70, Sex.FEMALE, rest = 72.0, workoutDays = setOf(2, 5), workoutIntensity = 0.65, workoutMinutes = 30)) {
            // 42 bpm awake is far below this person's normal, though above the fixed 40.
            restEpisode(day = 30, hour = 14, minutes = 40, factor = 0.58, expect = Expect.LOW_REST)
            restEpisode(day = 48, hour = 15, minutes = 40, factor = 1.8, expect = Expect.HIGH_REST)
        },
        Case(Person("Runner without workout tracking (45, resting 60)", 45, Sex.MALE, rest = 60.0, workoutDays = setOf(1, 3, 5, 6), labelsWorkouts = false, workoutIntensity = 0.85)) {
            overexertion(day = 40, minutes = 6, above = 10)
            restEpisode(day = 52, hour = 14, minutes = 40, factor = 1.8, expect = Expect.HIGH_REST)
        },
        Case(Person("Quiet routine (55, resting 68), illness only", 55, Sex.FEMALE, rest = 68.0, workoutDays = emptySet())) {
            illness(day = 35, nights = 5, rise = 9.0)
        },
    )

    /** Life around the episodes: a night and a day without the watch, and a charging hour most days. */
    private val everyday: HeartSimulation.() -> Unit = {
        offWrist(day = 12, fromHour = 20, hours = 26)
        for (d in 0 until 60 step 2) offWrist(day = d, fromHour = 7, hours = 1)
    }

    private fun runAll(sensitivity: AlertSensitivity = AlertSensitivity.STANDARD, seed: Int = 7) =
        cases.map { c -> HeartSimulation(c.person, seed = seed, sensitivity = sensitivity).run { everyday(); c.inject(this) } }

    private fun runFixed() = cases.map { c -> HeartSimulation(c.person, fixed = HeartSimulation.fixedLimits(c.person.age)).run { everyday(); c.inject(this) } }

    @Test
    fun personalLimitsCatchEveryEpisodeWithFewFalseAlerts() {
        val personal = runAll()
        val fixed = runFixed()
        val low = runAll(AlertSensitivity.LOW)
        val high = runAll(AlertSensitivity.HIGH)
        writeReport(personal, fixed, low, high)

        personal.forEach { r ->
            assertTrue("${r.person}: missed ${r.missed.map { it.name }} (alerts ${r.alerts.map { describe(it) }})", r.missed.isEmpty())
            assertTrue("${r.person}: false ${r.falseAlerts.map { describe(it) }}", r.falseAlerts.size <= 1)
        }
        // Overall the personal limits must beat the fixed ones on both counts.
        assertTrue(personal.sumOf { it.missed.size } < fixed.sumOf { it.missed.size })
        assertTrue(personal.sumOf { it.falseAlerts.size } <= fixed.sumOf { it.falseAlerts.size })
    }

    @Test
    fun resultsHoldAcrossRandomDays() {
        // Five more random histories per wearer (other noise, drift, walks, spikes).
        val runs = (1..5).flatMap { seed -> runAll(seed = 100 + seed) }
        runs.forEach { r ->
            assertTrue("${r.person}: missed ${r.missed.map { it.name }} limits ${r.finalLimits} alerts ${r.alerts.map { describe(it) }}", r.missed.isEmpty())
            assertTrue("${r.person}: false ${r.falseAlerts.map { describe(it) }}", r.falseAlerts.size <= 1)
        }
        val falsePerWearerMonth = runs.sumOf { it.falseAlerts.size } / (runs.size * 2.0)
        println("False alerts per wearer per 30 days: $falsePerWearerMonth (${runs.size} runs of 60 days)")
        assertTrue(falsePerWearerMonth <= 0.25)
    }

    @Test
    fun aRealEpisodeIsCaughtWhileStillLearning() {
        // A resting heart rate of ~150 on the first afternoon, before anything is learnt.
        val sim = HeartSimulation(cases.first().person, days = 2)
        val r = sim.run { restEpisode(day = 0, hour = 15, minutes = 40, factor = 2.3, expect = Expect.HIGH_REST) }
        assertTrue(r.alerts.map { describe(it) }.toString(), r.missed.isEmpty())
    }

    @Test
    fun theFirstDaysAreQuietForEveryone() {
        // No episodes at all: two weeks of normal life must not notify anyone (learning included).
        cases.forEach { c ->
            val r = HeartSimulation(c.person, days = 14, seed = 11).run()
            assertEquals("${c.person.name}: ${r.alerts.map { describe(it) }}", 0, r.alerts.size)
        }
    }

    @Test
    fun personalNormalMatchesTheSimulatedPerson() {
        runAll().zip(cases).forEach { (r, c) ->
            val l = r.finalLimits!!
            assertTrue("${r.person}: normal ${l.restNormal} vs ${c.person.rest}", kotlin.math.abs(l.restNormal - c.person.rest) <= 6)
            assertTrue("${r.person}: still learning", !l.learning)
        }
    }

    private fun describe(a: com.heartline.shared.hr.HealthAlert) =
        "day ${(a.atMs / HeartSimulation.DAY) - 20_000} ${a.kind}${a.trend?.let { "/$it" } ?: ""} ${a.context} ${a.bpm} (limit ${a.threshold})"

    private fun writeReport(personal: List<Result>, fixed: List<Result>, low: List<Result>, high: List<Result>) {
        val text = buildString {
            appendLine("# Heart notification simulation (60 days per wearer)")
            appendLine()
            appendLine("| Wearer | Limits | Episodes | Caught | Missed | False alerts | Limits at the end (high / low / asleep / exercise) |")
            appendLine("|---|---|---|---|---|---|---|")
            for (set in listOf(personal, fixed, low, high)) for (r in set) {
                val l = r.finalLimits
                appendLine(
                    "| ${r.person} | ${r.algorithm} | ${r.events.size} | ${r.detected.size} | " +
                        "${r.missed.joinToString { it.name }.ifEmpty { "–" }} | ${r.falseAlerts.size}${if (r.falseAlerts.isEmpty()) "" else ": " + r.falseAlerts.joinToString { describe(it) }} | " +
                        (l?.let { "${it.high} / ${it.low} / ${it.sleepLow} / ${it.exerciseMax} (normal ${it.restNormal}, asleep ${it.sleepNormal})" } ?: "–") + " |",
                )
            }
            appendLine()
            appendLine("Trend notices (personal): " + personal.sumOf { r -> r.alerts.count { it.trend == HeartTrend.ELEVATED_RESTING } })
        }
        File("build/reports").mkdirs()
        File("build/reports/heart-simulation.md").writeText(text)
        println(text)
    }
}
