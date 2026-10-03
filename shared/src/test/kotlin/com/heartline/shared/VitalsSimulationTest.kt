// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared

import com.heartline.shared.hr.AlertSensitivity
import com.heartline.shared.hr.DayStats
import com.heartline.shared.hr.HealthAlert
import com.heartline.shared.hr.HeartHistory
import com.heartline.shared.hr.HrContext
import com.heartline.shared.hr.MonitorSettings
import com.heartline.shared.hr.VitalAlert
import com.heartline.shared.vitals.Spo2Sample
import com.heartline.shared.vitals.TempSample
import com.heartline.shared.vitals.VitalsHistory
import com.heartline.shared.vitals.VitalsMonitor
import java.io.File
import java.time.ZoneOffset
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 60 days of blood oxygen and skin temperature through [VitalsMonitor], driven as the watch's
 * worker drives it: SpO2 every hour awake and asleep (some hours missed while moving), a low reading
 * checked again (twice at most, 2 minutes apart), skin temperature every 30 minutes in sleep (the first hour not
 * counted), and the night check at 10:00.
 *
 * Sensor model:
 * - SpO2: reading noise SD 1 point, a day-to-day drift (SD 0.4), the night 0.8 lower (Apple Heart &
 *   Movement Study), and 3 % of readings an artefact 4–8 points low;
 * - skin temperature: night-to-night SD 0.2 °C, reading noise 0.15 °C, and 1 night in 10 under a
 *   warmer blanket (+0.4 °C);
 * - heart rate in sleep: around 55 bpm, night-to-night SD 1.5.
 * Report: build/reports/vitals-simulation.md (summarised in docs/algorithms/VITALS_MONITORING.md).
 */
class VitalsSimulationTest {
    private enum class Want { SPO2_LOW, SPO2_NIGHTS, TEMPERATURE, COMBINED }

    /** An episode put into the data and the notice it should give within [fromDay]..[toDay]. */
    private data class Episode(val name: String, val fromDay: Int, val toDay: Int, val want: Want)

    private data class Person(
        val name: String,
        val spo2: Double = 97.0,
        val skin: Double = 34.0,
        /** Luteal-phase rise in the second half of a 28-day cycle (Maijala 2019: ~0.3–0.5 °C). */
        val cycleRise: Double = 0.0
    )

    private class World(val person: Person, val days: Int = 60, seed: Int) {
        val random = Random(seed)
        val dayDrift = DoubleArray(days) { gauss(random) * 0.4 }
        val nightTemp = DoubleArray(days) { gauss(random) * 0.2 + if (random.nextInt(10) == 0) 0.4 else 0.0 }
        val nightHr = DoubleArray(days) { gauss(random) * 1.5 }
        val spo2Shift = DoubleArray(days * 24) { 0.0 }
        val nightSpo2Shift = DoubleArray(days) { 0.0 }
        val tempShift = DoubleArray(days) { 0.0 }
        val hrShift = DoubleArray(days) { 0.0 }
        val episodes = mutableListOf<Episode>()

        companion object {
            fun gauss(r: Random) = sqrt(-2 * ln(1 - r.nextDouble())) * cos(2 * Math.PI * r.nextDouble())
        }
    }

    private val zone = ZoneOffset.UTC
    private val start = 20_000L
    private fun ms(day: Int, hour: Int, minute: Int = 0) = (start + day) * 86_400_000L + hour * 3_600_000L + minute * 60_000L

    private data class Run(
        val person: String,
        val alerts: List<HealthAlert>,
        val episodes: List<Episode>,
        val caught: List<Episode>,
        val falseAlerts: List<HealthAlert>
    )

    private fun simulate(w: World, sensitivity: AlertSensitivity = AlertSensitivity.STANDARD): Run {
        var ids = 0
        val monitor = VitalsMonitor(zone) { "a${ids++}" }
        val settings = MonitorSettings(alertSensitivity = sensitivity)
        var history = VitalsHistory()
        var heart = HeartHistory()
        val alerts = mutableListOf<HealthAlert>()
        val r = w.random
        fun spo2Reading(day: Int, hour: Int, asleep: Boolean): Int {
            var v = w.person.spo2 + w.dayDrift[day] + World.gauss(r) * 1.0 + w.spo2Shift[day * 24 + hour]
            if (asleep) v += -0.8 + w.nightSpo2Shift[day]
            if (r.nextDouble() < 0.03) v -= 4 + r.nextDouble() * 4
            return v.roundToInt().coerceAtMost(100)
        }
        for (day in 0 until w.days) {
            // The night that ends this morning: heart rate in sleep for the combined notice.
            val bpm = (55 + w.nightHr[day] + w.hrShift[day]).roundToInt()
            heart = HeartHistory(heart.days + DayStats(start + day, sleep = (bpm - 1..bpm + 1).associateWith { 14 }))
            for (hour in 0 until 24) {
                val asleep = hour < 7 || hour == 23
                val nightDay = if (hour == 23) day + 1 else day
                if (nightDay >= w.days) continue
                // Skin temperature every 30 minutes asleep; the first hour (23:00–00:00) not counted.
                if (asleep) {
                    for (half in 0..1) {
                        val cycle = if (((nightDay % 28) >= 14)) w.person.cycleRise else 0.0
                        val t = w.person.skin + w.nightTemp[nightDay] + cycle + w.tempShift[nightDay] + World.gauss(r) * 0.15
                        history =
                            monitor.onTemp(
                                history,
                                TempSample(
                                    ms(day, hour, half * 30),
                                    t.toFloat(),
                                    22f,
                                    HrContext.SLEEP,
                                    counted =
                                    hour != 23
                                )
                            )
                    }
                }
                // SpO2 every hour; 15 % of hours missed (moving, off the wrist).
                if (r.nextDouble() < 0.15) continue
                val context = if (asleep) HrContext.SLEEP else HrContext.REST
                val first = Spo2Sample(ms(day, hour), spo2Reading(nightDay.coerceAtMost(w.days - 1), hour, asleep), context)
                val rechecks = mutableListOf<Spo2Sample>()
                var last = first
                while (monitor.needsRecheck(last.percent, settings) && rechecks.size < VitalsMonitor.RECHECKS) {
                    last =
                        Spo2Sample(
                            ms(day, hour, 2 + 2 * rechecks.size),
                            spo2Reading(nightDay.coerceAtMost(w.days - 1), hour, asleep),
                            context,
                            confirmation = true
                        )
                    rechecks += last
                }
                val (next, alert) = monitor.onSpo2(history, first, rechecks, settings)
                history = next
                alert?.let { alerts += it }
                if (hour == 10) {
                    val (after, found) = monitor.afterNight(history, heart, settings, ms(day, 10, 5))
                    history = after
                    alerts += found
                }
            }
        }
        fun want(a: HealthAlert) = when (a.vital) {
            VitalAlert.SPO2_LOW -> Want.SPO2_LOW
            VitalAlert.SPO2_NIGHTS -> Want.SPO2_NIGHTS
            VitalAlert.TEMPERATURE -> Want.TEMPERATURE
            VitalAlert.COMBINED -> Want.COMBINED
            VitalAlert.STRESS -> null
            null -> null
        }
        fun dayOf(a: HealthAlert) = (a.atMs / 86_400_000L - start).toInt()
        fun explains(e: Episode, a: HealthAlert) = want(a) == e.want && dayOf(a) in e.fromDay..e.toDay
        val caught = w.episodes.filter { e -> alerts.any { explains(e, it) } }
        // An illness may also explain a temperature notice (and the reverse); at high altitude a lower
        // night is expected too.
        val falseAlerts = alerts.filter { a -> w.episodes.none { e -> dayOf(a) in e.fromDay..e.toDay } }
        return Run(w.person.name, alerts, w.episodes, caught, falseAlerts)
    }

    private fun worlds(seed: Int): List<World> = listOf(
        World(Person("Typical adult (SpO2 97 %), sensor artefacts only"), seed = seed),
        World(Person("Older adult (SpO2 93 %)", spo2 = 93.0), seed = seed + 1).apply {
            for (h in 0 until 4) spo2Shift[40 * 24 + 13 + h] = -5.0 // three hours around 88 % by day
            episodes += Episode("sustained 88 % by day, day 40", 40, 40, Want.SPO2_LOW)
        },
        World(Person("Woman with a menstrual cycle (+0.35 °C luteal)", cycleRise = 0.35), seed = seed + 2),
        World(Person("Trip to 2,500 m for 5 days"), seed = seed + 3).apply {
            for (d in 30 until 35) {
                nightSpo2Shift[d] = -3.0
                for (h in 0 until 24) spo2Shift[d * 24 + h] = -2.5
            }
            // Lower nights at altitude are expected to give the "lower in sleep" notice.
            episodes += Episode("altitude, days 30-34", 30, 36, Want.SPO2_NIGHTS)
        },
        World(Person("Illness: warmer nights and raised heart rate"), seed = seed + 4).apply {
            for (d in 40 until 43) {
                tempShift[d] = 1.2
                hrShift[d] = 9.0
            }
            episodes += Episode("illness, nights 40-42", 40, 41, Want.COMBINED)
        },
        World(Person("Warmer nights only (+1.3 °C)"), seed = seed + 5).apply {
            for (d in 40 until 43) tempShift[d] = 1.3
            episodes += Episode("warm nights 40-42", 41, 42, Want.TEMPERATURE)
        },
        World(Person("Breathing trouble in sleep (nights at 91 %)"), seed = seed + 6).apply {
            for (d in 45 until 48) nightSpo2Shift[d] = -5.2
            episodes += Episode("low nights 45-47", 46, 48, Want.SPO2_NIGHTS)
        }
    )

    @Test
    fun everyEpisodeIsCaughtWithFewFalseNotices() {
        val runs = worlds(7).map { simulate(it) }
        report(runs, (1..5).flatMap { s -> worlds(100 + s * 10).map { simulate(it) } })
        runs.forEach { r ->
            assertTrue(
                "${r.person}: missed ${(r.episodes - r.caught.toSet()).map { it.name }} alerts ${r.alerts.map { describe(it) }}",
                r.caught.size == r.episodes.size
            )
            assertTrue("${r.person}: false ${r.falseAlerts.map { describe(it) }}", r.falseAlerts.size <= 1)
        }
    }

    @Test
    fun resultsHoldAcrossRandomHistories() {
        val runs = (1..5).flatMap { s -> worlds(100 + s * 10).map { simulate(it) } }
        runs.forEach { r ->
            assertTrue(
                "${r.person}: missed ${(r.episodes - r.caught.toSet()).map { it.name }} alerts ${r.alerts.map { describe(it) }}",
                r.caught.size == r.episodes.size
            )
            assertTrue("${r.person}: false ${r.falseAlerts.map { describe(it) }}", r.falseAlerts.isEmpty())
        }
    }

    private fun describe(a: HealthAlert) = "day ${(a.atMs / 86_400_000L - start)} ${a.vital} ${a.value}"

    private fun report(runs: List<Run>, more: List<Run>) {
        val text = buildString {
            appendLine("# Blood oxygen and skin temperature simulation (60 days per wearer, standard sensitivity)")
            appendLine()
            appendLine("| Wearer | Episodes | Caught | Notices | False notices |")
            appendLine("|---|---|---|---|---|")
            runs.forEach { r ->
                appendLine(
                    "| ${r.person} | ${r.episodes.size} | ${r.caught.size} | ${r.alerts.joinToString {
                        describe(it)
                    }.ifEmpty { "–" }} | ${r.falseAlerts.size} |"
                )
            }
            appendLine()
            appendLine(
                "Five more random histories per wearer (${more.size} runs): caught ${more.sumOf {
                    it.caught.size
                }} of ${more.sumOf { it.episodes.size }}, false notices ${more.sumOf { it.falseAlerts.size }}."
            )
        }
        File("build/reports").mkdirs()
        File("build/reports/vitals-simulation.md").writeText(text)
        println(text)
    }
}
