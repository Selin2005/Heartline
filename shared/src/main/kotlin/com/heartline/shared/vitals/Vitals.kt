// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.vitals

import com.heartline.shared.hr.AlertKind
import com.heartline.shared.hr.AlertSensitivity
import com.heartline.shared.hr.HealthAlert
import com.heartline.shared.hr.HeartBaseline
import com.heartline.shared.hr.HeartHistory
import com.heartline.shared.hr.Histogram
import com.heartline.shared.hr.HrContext
import com.heartline.shared.hr.MonitorSettings
import com.heartline.shared.hr.VitalAlert
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable

/** One background blood-oxygen reading. [confirmation]: a re-check right after a low reading. */
@Serializable
data class Spo2Sample(val tsMs: Long, val percent: Int, val context: HrContext, val confirmation: Boolean = false)

/** How a background SpO2 try ended, for the phone's "today" card. */
@Serializable
enum class Spo2Outcome { MEASURED, MOVING, LOW_BATTERY, CHARGING, OFF_WRIST, APP_OPEN, OFF_IN_SLEEP, NO_SIGNAL, REJECTED, LOW_UNCONFIRMED }

/**
 * One background SpO2 try that was due: when, how it ended, the value kept ([percent], with
 * [Spo2Outcome.MEASURED]) and, when it was put off, when the next try is planned ([retryAtMs]).
 */
@Serializable
data class Spo2Attempt(val tsMs: Long, val outcome: Spo2Outcome, val percent: Int? = null, val retryAtMs: Long? = null)

/**
 * One background skin-temperature reading. [counted]: it belongs to the night's value (asleep,
 * after the first hour, when the wrist has warmed up under the covers).
 */
@Serializable
data class TempSample(val tsMs: Long, val skinC: Float, val ambientC: Float?, val context: HrContext, val counted: Boolean = false)

/**
 * One day of blood oxygen and skin temperature. SpO2 as histograms (by day at rest, and in the
 * night that ends on this day), the number of night readings below 90 %, and the night's
 * counted skin temperatures. [unusual]: a night behind a notice, left out of the baseline.
 */
@Serializable
data class VitalsDay(
    val day: Long,
    val spo2Day: Map<Int, Int> = emptyMap(),
    val spo2Night: Map<Int, Int> = emptyMap(),
    val nightLow: Int = 0,
    val temps: List<Float> = emptyList(),
    val unusual: Boolean = false
)

@Serializable
data class VitalsHistory(
    val days: List<VitalsDay> = emptyList(),
    val lastAlerts: Map<String, Long> = emptyMap(),
    val lastNightCheck: Long? = null
) {
    fun day(day: Long) = days.firstOrNull { it.day == day } ?: VitalsDay(day)

    fun with(day: VitalsDay): VitalsHistory =
        copy(days = (days.filter { it.day != day.day && it.day > day.day - KEEP_DAYS } + day).sortedBy { it.day })

    fun markUnusual(dayList: Collection<Long>) = copy(days = days.map { if (it.day in dayList) it.copy(unusual = true) else it })

    companion object {
        const val KEEP_DAYS = 35
    }
}

/** The wearer's blood-oxygen and temperature normals and the limits in use (shown on the phone). */
@Serializable
data class VitalsLimits(
    /** Readings below this are checked again; three in a row notify. Clinical, not personal. */
    val spo2Low: Int,
    val spo2DayNormal: Int,
    val spo2NightNormal: Int,
    val spo2Confidence: Double = 0.0,
    /** Drop in the nightly median, below the usual night, that counts as lower than usual. */
    val spo2NightDrop: Int,
    /** Usual (median) and spread of the nightly skin temperature (null until enough nights). */
    val tempBaseline: Float? = null,
    val tempSd: Float? = null,
    val tempNights: Int = 0,
    /** Rise above the usual night that notifies (two nights in a row). */
    val tempRise: Float,
    /** Last night's change from the usual (null before 3 nights). */
    val lastNightDeviation: Float? = null,
    val sensitivity: AlertSensitivity = AlertSensitivity.STANDARD
)

/** Reading quality: only believable readings are kept. */
object VitalsQuality {
    /**
     * A completed SpO2 reading (status 2) between 70 and 100 %, taken still, whose own pulse is
     * within 15 % of the recent background heart rate (when known).
     */
    fun spo2(percent: Int, sensorBpm: Int?, recentBpm: Int?, moved: Boolean): Boolean {
        if (moved || percent !in 70..100) return false
        if (sensorBpm != null && sensorBpm > 0 && recentBpm != null && abs(sensorBpm - recentBpm) > recentBpm * 0.15) return false
        return true
    }

    /**
     * Skin 30–40 °C, and the "ambient" reading not changed by more than 5 °C since the last
     * reading (a shower, going outside). Whether the watch is on the wrist comes from the
     * off-body sensor (checked by the worker).
     *
     * The ambient value is not compared with skin: on Samsung watches it is measured inside the
     * case and reads *warmer* than skin when worn (a Galaxy Watch8 Classic: skin 34.7 °C, ambient
     * 35.9 °C). Requiring skin above ambient rejected every reading. A watch lying on a table cools
     * to the room, below 30 °C; covered wrist skin stays at 30–35 °C.
     */
    fun temperature(skinC: Float, ambientC: Float?, previousAmbientC: Float?): Boolean {
        if (skinC !in SKIN_MIN..SKIN_MAX) return false
        if (ambientC != null && previousAmbientC != null && abs(ambientC - previousAmbientC) > AMBIENT_JUMP) return false
        return true
    }

    const val SKIN_MIN = 30f
    const val SKIN_MAX = 40f
    const val AMBIENT_JUMP = 5f
}

/**
 * Personal blood-oxygen and skin-temperature normals, limits and notices.
 *
 * Blood oxygen: the low limit is clinical, never personal, so a low normal can't hide a problem.
 * Readings *below* it count (88 % low sensitivity: occult hypoxaemia, Sjoding 2020, and BTS's
 * target for people at risk; 90 % standard: hypoxaemia; 92 % high: the lowest stable value in
 * healthy older adults, BTS). A single reading never notifies: wrist oximeters are off by 2–4 %,
 * so a low one is checked again twice and only three in a row notify. The usual night (population
 * guess 95.4 % minus
 * 0.043 a year over 40, Apple Heart & Movement Study; learnt like the heart-rate normal) is used
 * for "lower than usual in sleep": 2 of the last 3 nights' medians that many points below it, or
 * 3 readings below 90 in one night, each confirmed by its re-check.
 *
 * Skin temperature (as Apple's wrist temperature and Oura): one value per night, the median of
 * the readings after the first hour asleep; a baseline of the last 28 nights (median, robust spread); the change shown
 * from the 3rd night, notices from the 5th. Notices need a rise of at least [Rise.celsius] and
 * [Rise.sds] standard deviations over two nights in a row (their average, each night at least 70 %
 * of it), above the menstrual cycle's ~0.3–0.5 °C
 * (Maijala 2019). A warmer night (+0.5) with a raised heart rate in sleep notifies at once as one
 * combined notice (Mishra 2020, Alavi 2022, Smarr 2020).
 */
object VitalsBaseline {
    const val PRIOR_DAY = 96.2
    const val PRIOR_NIGHT = 95.4
    const val PRIOR_READINGS = 6.0
    const val WINDOW_DAYS = 28
    const val MIN_NIGHT_SPO2 = 3
    const val MIN_NIGHT_TEMPS = 4
    const val TEMP_SHOW_NIGHTS = 2
    const val TEMP_ALERT_NIGHTS = 4
    const val TEMP_MIN_SD = 0.2f
    private const val MAD_TO_SD = 1.4826
    const val COMBINED_RISE = 0.5f

    data class Rise(val celsius: Float, val sds: Float)

    /** With a lung condition that keeps oxygen low, BTS's target range starts at 88 %. */
    const val LUNG_LOW = 88

    /**
     * The limit in use. High sensitivity's 92 % (the lowest stable value in healthy older adults)
     * sits within a wrist oximeter's 2–4 % error of many people's usual: a Galaxy Watch8 Classic
     * read 90–92 % at rest for a healthy wearer. So it applies only to wearers whose own usual by
     * day is at least 95 %; for the others it is their usual − 3, never below the clinical 90 %.
     * Until their usual is learnt ([HIGH_USUAL_READINGS]), 90 %.
     */
    fun spo2Low(settings: MonitorSettings, history: VitalsHistory? = null, today: Long? = null): Int {
        if (settings.health.lung) return LUNG_LOW
        val s = settings.alertSensitivity
        if (s != AlertSensitivity.HIGH) return spo2Low(s)
        val usual = history?.let { h ->
            val days = h.days.filter { d -> !d.unusual && (today == null || d.day in (today - WINDOW_DAYS + 1)..today) }
            Histogram.merge(days.map { it.spo2Day }).takeIf { it.count >= HIGH_USUAL_READINGS }?.median()?.roundToInt()
        } ?: return spo2Low(AlertSensitivity.STANDARD)
        return if (usual >= HIGH_USUAL_MIN) spo2Low(AlertSensitivity.HIGH) else maxOf(spo2Low(AlertSensitivity.STANDARD), usual - 3)
    }

    const val HIGH_USUAL_MIN = 95
    const val HIGH_USUAL_READINGS = 20

    fun spo2Low(s: AlertSensitivity) = when (s) {
        AlertSensitivity.LOW -> 88
        AlertSensitivity.STANDARD -> 90
        AlertSensitivity.HIGH -> 92
    }

    fun nightDrop(s: AlertSensitivity) = when (s) {
        AlertSensitivity.LOW -> 4
        AlertSensitivity.STANDARD -> 3
        AlertSensitivity.HIGH -> 2
    }

    fun rise(s: AlertSensitivity) = when (s) {
        AlertSensitivity.LOW -> Rise(1.2f, 3f)
        AlertSensitivity.STANDARD -> Rise(1.0f, 3f)
        AlertSensitivity.HIGH -> Rise(0.7f, 2.5f)
    }

    private fun ageShift(age: Int?) = ((age ?: 40) - 40).coerceAtLeast(0) * 0.043

    fun limits(history: VitalsHistory, today: Long, sensitivity: AlertSensitivity, age: Int?, lung: Boolean = false): VitalsLimits {
        val inUse =
            spo2Low(
                MonitorSettings(
                    alertSensitivity = sensitivity,
                    health = com.heartline.shared.hr.HealthContext(lungCondition = if (lung) com.heartline.shared.hr.Answer.YES else null)
                ),
                history,
                today
            )
        val recent = history.days.filter { it.day in (today - WINDOW_DAYS + 1)..today && !it.unusual }
        val day = Histogram.merge(recent.map { it.spo2Day })
        val night = Histogram.merge(recent.map { it.spo2Night })
        val dayC = day.count / (day.count + PRIOR_READINGS)
        val nightC = night.count / (night.count + PRIOR_READINGS)
        fun blend(own: Double?, prior: Double, c: Double) = own?.let { it * c + prior * (1 - c) } ?: prior
        val temp = tempBaseline(history, today)
        return VitalsLimits(
            spo2Low = inUse,
            spo2DayNormal = blend(day.median(), PRIOR_DAY - ageShift(age), dayC).roundToInt(),
            spo2NightNormal = blend(night.median(), PRIOR_NIGHT - ageShift(age), nightC).roundToInt(),
            spo2Confidence = minOf(dayC, nightC).takeIf { day.count > 0 && night.count > 0 } ?: maxOf(dayC, nightC),
            spo2NightDrop = nightDrop(sensitivity),
            tempBaseline = temp?.first?.toFloat(),
            tempSd = temp?.second?.toFloat(),
            tempNights = temp?.third ?: 0,
            tempRise = rise(sensitivity).celsius,
            lastNightDeviation = nightTemp(history, today)?.let { t -> temp?.let { (t - it.first).toFloat() } },
            sensitivity = sensitivity
        )
    }

    /** The night's skin temperature (median of its counted readings), or null without enough. */
    fun nightTemp(history: VitalsHistory, day: Long): Double? = history.days.firstOrNull {
        it.day == day
    }?.temps?.takeIf { it.size >= MIN_NIGHT_TEMPS }?.sorted()?.let { it[it.size / 2].toDouble() }

    /**
     * Usual night (median), spread (at least 0.2 °C) and count of the 28 nights before [today], from
     * 2 nights. Robust: the spread is 1.4826 × the median absolute deviation (an SD for normal
     * nights), so the first warm night of an illness, or a few under a warmer blanket, move neither.
     */
    fun tempBaseline(history: VitalsHistory, today: Long): Triple<Double, Double, Int>? {
        val nights = history.days.filter {
            it.day in (today - WINDOW_DAYS)..(today - 1) && !it.unusual
        }.mapNotNull { nightTemp(history, it.day) }
        if (nights.size < TEMP_SHOW_NIGHTS) return null
        val usual = median(nights)
        val sd = (MAD_TO_SD * median(nights.map { abs(it - usual) })).coerceAtLeast(TEMP_MIN_SD.toDouble())
        return Triple(usual, sd, nights.size)
    }

    private fun median(values: List<Double>) = values.sorted().let {
        if (it.size % 2 ==
            1
        ) {
            it[it.size / 2]
        } else {
            (it[it.size / 2 - 1] + it[it.size / 2]) / 2
        }
    }

    fun nightSpo2(history: VitalsHistory, day: Long): Int? = history.days.firstOrNull {
        it.day == day
    }?.let { Histogram(it.spo2Night) }?.takeIf { it.count >= MIN_NIGHT_SPO2 }?.median()?.roundToInt()

    /** Day a reading belongs to: its local date, or for sleep the date the night ends (18:00–18:00). */
    fun dayOf(ms: Long, context: HrContext, zone: ZoneId) = HeartBaseline.dayOf(ms, context, zone)
}

/**
 * Applies readings to the history and decides notices. Pure: the watch's worker measures and
 * stores; the simulation drives this directly.
 */
class VitalsMonitor(private val zone: ZoneId = ZoneId.systemDefault(), private val newId: () -> String) {
    /** True when a reading is low enough to be checked again right away (below the limit). */
    fun needsRecheck(percent: Int, settings: MonitorSettings, history: VitalsHistory? = null, atMs: Long = System.currentTimeMillis()) =
        settings.spo2Active && percent < VitalsBaseline.spo2Low(settings, history, VitalsBaseline.dayOf(atMs, HrContext.REST, zone))

    /**
     * A SpO2 reading with its re-checks (taken while each was still low, [RECHECKS] at most). Only
     * three low readings in a row notify: one artefact is common (2–4 % error), two in a row about
     * 1 in 1,000 readings, three practically never. Low readings are kept for the charts but never
     * learnt into the normal.
     */
    fun onSpo2(
        history: VitalsHistory,
        first: Spo2Sample,
        rechecks: List<Spo2Sample>,
        settings: MonitorSettings
    ): Pair<VitalsHistory, HealthAlert?> {
        val limit = VitalsBaseline.spo2Low(settings, history, VitalsBaseline.dayOf(first.tsMs, HrContext.REST, zone))
        val nightLowBelow = minOf(NIGHT_LOW_PERCENT, limit)
        var h = history
        // A night reading counts as low only when its re-check agrees: single artefacts (3 % of
        // wrist readings) added up to false "several low readings in one night" notices.
        val confirmedLow = first.percent < nightLowBelow && rechecks.firstOrNull()?.let { it.percent < nightLowBelow } == true
        for (s in listOf(first) + rechecks) {
            val dayKey = VitalsBaseline.dayOf(s.tsMs, s.context, zone)
            val d = h.day(dayKey)
            val low = s.percent < limit
            h = h.with(
                when {
                    s.context == HrContext.SLEEP -> d.copy(
                        spo2Night = if (low) d.spo2Night else d.spo2Night.plus(s.percent),
                        nightLow = d.nightLow + if (s === first && confirmedLow) 1 else 0
                    )
                    low -> d
                    else -> d.copy(spo2Day = d.spo2Day.plus(s.percent))
                }
            )
        }
        val all = listOf(first) + rechecks
        if (!settings.spo2Active || rechecks.size < RECHECKS || all.any { it.percent >= limit }) return h to null
        val last = rechecks.last()
        val key = "SPO2_LOW"
        if (h.lastAlerts[key]?.let { last.tsMs - it < SPO2_COOLDOWN_MS } == true) return h to null
        val shown = all.maxOf { it.percent }
        val alert = HealthAlert(
            newId(),
            AlertKind.LOW_HEART_RATE,
            last.tsMs,
            shown,
            threshold = limit,
            context = last.context,
            vital = VitalAlert.SPO2_LOW,
            value = shown.toFloat()
        )
        return h.copy(lastAlerts = h.lastAlerts + (key to last.tsMs)) to alert
    }

    /** A skin-temperature reading (already passed [VitalsQuality.temperature]). */
    fun onTemp(history: VitalsHistory, sample: TempSample): VitalsHistory {
        if (!sample.counted) return history
        val dayKey = VitalsBaseline.dayOf(sample.tsMs, HrContext.SLEEP, zone)
        val d = history.day(dayKey)
        return history.with(d.copy(temps = d.temps + (sample.skinC * 100).roundToInt() / 100f))
    }

    /**
     * Once a day after the night ([nowMs] at or after 10:00): the night-time notices. [heart]: the
     * heart-rate history, for the combined notice (only when the heart part is on too).
     */
    fun afterNight(
        history: VitalsHistory,
        heart: HeartHistory?,
        settings: MonitorSettings,
        nowMs: Long
    ): Pair<VitalsHistory, List<HealthAlert>> {
        val local = Instant.ofEpochMilli(nowMs).atZone(zone)
        val today = local.toLocalDate().toEpochDay()
        if (local.hour < CHECK_HOUR || history.lastNightCheck == today) return history to emptyList()
        var h = history.copy(lastNightCheck = today)
        val alerts = mutableListOf<HealthAlert>()
        fun ready(key: String) = h.lastAlerts[key]?.let { nowMs - it >= NIGHT_COOLDOWN_MS } ?: true
        fun fire(key: String, alert: HealthAlert, nights: Collection<Long>) {
            alerts += alert
            h = h.copy(lastAlerts = h.lastAlerts + (key to nowMs)).markUnusual(nights)
        }

        if (settings.temperatureNotices) {
            val base = VitalsBaseline.tempBaseline(h, today)
            val tonight = VitalsBaseline.nightTemp(h, today)
            val lastNight = VitalsBaseline.nightTemp(h, today - 1)
            val baseYesterday = VitalsBaseline.tempBaseline(h, today - 1)
            if (base != null && tonight != null && base.third >= VitalsBaseline.TEMP_ALERT_NIGHTS) {
                val dev = tonight - base.first
                val rise = VitalsBaseline.rise(settings.alertSensitivity)
                val needed = maxOf(rise.celsius.toDouble(), rise.sds * base.second)
                val heartRaised = settings.heartActive && heart != null && HeartBaseline.nightRaised(heart, today)
                val yesterdayDev = if (lastNight != null && baseYesterday != null) lastNight - baseYesterday.first else null
                when {
                    heartRaised && dev >= VitalsBaseline.COMBINED_RISE && ready(COMBINED) ->
                        fire(
                            COMBINED,
                            HealthAlert(
                                newId(),
                                AlertKind.HIGH_HEART_RATE,
                                nowMs,
                                null,
                                context = HrContext.SLEEP,
                                vital = VitalAlert.COMBINED,
                                value = dev.toFloat()
                            ),
                            listOf(today)
                        )
                    // One illness, one notice: no temperature notice while a combined one is recent.
                    // Two nights: their average past the rise, and each at least 70 % of it (averaging
                    // halves the night-to-night noise; one warm night alone, a blanket, can't do it).
                    yesterdayDev != null &&
                        (dev + yesterdayDev) / 2 >= needed &&
                        minOf(dev, yesterdayDev) >= needed * TWO_NIGHT_SHARE &&
                        ready(TEMP) &&
                        ready(COMBINED) ->
                        fire(
                            TEMP,
                            HealthAlert(
                                newId(),
                                AlertKind.HIGH_HEART_RATE,
                                nowMs,
                                null,
                                context = HrContext.SLEEP,
                                vital = VitalAlert.TEMPERATURE,
                                value = dev.toFloat()
                            ),
                            listOf(
                                today,
                                today - 1
                            )
                        )
                }
            }
        }

        if (settings.spo2Active) {
            val limits = VitalsBaseline.limits(h, today - 3, settings.alertSensitivity, null, settings.health.lung)
            val nights = (0..2).mapNotNull { i -> VitalsBaseline.nightSpo2(h, today - i)?.let { (today - i) to it } }
            // "Lower than usual" needs a usual: at least 5 nights of the wearer's own before these.
            val ownNights = (3 until 3 + VitalsBaseline.WINDOW_DAYS).count { VitalsBaseline.nightSpo2(h, today - it) != null }
            val dropped = nights.filter {
                it.second <= limits.spo2NightNormal - limits.spo2NightDrop
            }.takeIf { ownNights >= MIN_OWN_NIGHTS }.orEmpty()
            val manyLow = h.day(today).nightLow >= NIGHT_LOW_READINGS
            // High sensitivity's 2-point drop is within a wrist oximeter's noise over 2 nights: it
            // needs all 3 (the simulation found false notices with 2 of 3).
            val needed = if (settings.alertSensitivity == AlertSensitivity.HIGH) 3 else 2
            if ((dropped.size >= needed || manyLow) && ready(SPO2_NIGHTS)) {
                val median = VitalsBaseline.nightSpo2(h, today) ?: dropped.firstOrNull()?.second
                fire(
                    SPO2_NIGHTS,
                    HealthAlert(
                        newId(),
                        AlertKind.LOW_HEART_RATE,
                        nowMs,
                        median,
                        threshold = limits.spo2NightNormal,
                        context = HrContext.SLEEP,
                        vital = VitalAlert.SPO2_NIGHTS,
                        value = median?.toFloat()
                    ),
                    dropped.map { it.first } + today
                )
            }
        }
        return h to alerts
    }

    private fun Map<Int, Int>.plus(value: Int) = toMutableMap().apply { merge(value, 1, Int::plus) }

    companion object {
        const val CHECK_HOUR = 10
        const val SPO2_COOLDOWN_MS = 6 * 3_600_000L
        const val NIGHT_COOLDOWN_MS = 3 * 24 * 3_600_000L
        const val NIGHT_LOW_READINGS = 3

        /** Night readings below this (hypoxaemia, SpO2 < 90 %) count towards "several low in one night". */
        const val NIGHT_LOW_PERCENT = 90
        const val MIN_OWN_NIGHTS = 5

        /** Each of the two warmer nights must reach this share of the rise. */
        const val TWO_NIGHT_SHARE = 0.7

        /** Re-checks of a low reading: three low in a row notify. */
        const val RECHECKS = 2
        const val TEMP = "TEMP"
        const val COMBINED = "COMBINED"
        const val SPO2_NIGHTS = "SPO2_NIGHTS"
    }
}
