// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.stress

import com.heartline.shared.hr.AlertKind
import com.heartline.shared.hr.HealthAlert
import com.heartline.shared.hr.HeartBaseline
import com.heartline.shared.hr.HrContext
import com.heartline.shared.hr.MonitorSettings
import com.heartline.shared.hr.VitalAlert
import com.heartline.shared.profile.StressIndex
import com.heartline.shared.profile.StressLevel
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable

/**
 * One background stress reading: a readable rhythm window (about a minute of clean beat-to-beat
 * intervals, still). [score] is null in sleep, where the window only feeds the night's HRV.
 */
@Serializable
data class StressSample(val tsMs: Long, val rmssdMs: Double, val bpm: Int, val score: Int?, val context: HrContext)

/**
 * One day of stress: ln RMSSD and heart rate of the awake windows (learnt), their scores, and the
 * ln RMSSD of the night that ends this day. [unusual]: a day behind a notice or of illness, left
 * out of the normal.
 */
@Serializable
data class StressDay(
    val day: Long,
    val awakeLn: List<Float> = emptyList(),
    val awakeBpm: List<Int> = emptyList(),
    val scores: List<Int> = emptyList(),
    val sleepLn: List<Float> = emptyList(),
    val notices: Int = 0,
    val unusual: Boolean = false,
    /** Illness (from the other parts) explains this day: not in the week's insight either. */
    val ill: Boolean = false
)

@Serializable
data class StressHistory(
    val days: List<StressDay> = emptyList(),
    /** The latest scored awake windows (last 90 minutes), for the sustained-stress rule. */
    val recent: List<StressSample> = emptyList(),
    val lastAlertMs: Long? = null,
    /** The last exercise window: heart-rate variability takes about an hour to recover after it. */
    val lastExerciseMs: Long? = null
) {
    fun day(day: Long) = days.firstOrNull { it.day == day } ?: StressDay(day)

    fun with(day: StressDay): StressHistory =
        copy(days = (days.filter { it.day != day.day && it.day > day.day - KEEP_DAYS } + day).sortedBy { it.day })

    companion object {
        const val KEEP_DAYS = 35
    }
}

/** The wearer's stress normal (shown on the phone). */
@Serializable
data class StressLimits(
    /** Usual awake RMSSD (ms) and heart rate in the windows; null until a few windows. */
    val usualRmssd: Double? = null,
    val usualBpm: Int? = null,
    /** How much of the score is the wearer's own (0–1): windows / (windows + 20). */
    val confidence: Double = 0.0,
    val windows: Int = 0,
    /** Usual and last night's RMSSD in sleep (ms). */
    val usualNightRmssd: Double? = null,
    val lastNightRmssd: Double? = null,
    /** Today's average score and minutes in high stress (windows × 15). */
    val todayAverage: Int? = null,
    val todayHighMinutes: Int = 0,
    /** The week's average above the usual (insight), when the week was clearly more stressful. */
    val weekAboveUsual: Int? = null
)

/**
 * Personal stress from heart-rate variability (docs/algorithms/STRESS_MONITORING.md).
 *
 * RMSSD falls and heart rate rises with mental stress (Task Force 1996; Kim 2018), but the levels
 * differ a lot between people, so the score is relative to the wearer's own still, awake windows
 * of the last 28 days (ln RMSSD, as Plews and Buchheit track it): median and a robust spread
 * (1.4826 × MAD, at least [MIN_LN_SD]). In SD units, raw = −0.6 z(ln RMSSD) + 0.4 z(heart rate);
 * score = 30 + 18 raw, so the wearer's usual is about 30 (low) and high (≥ 67, [StressIndex.level])
 * is about 2 SD worse than usual. Until it is learnt, the score is blended with the population
 * score ([StressIndex.score]) by windows / (windows + [PRIOR_WINDOWS]).
 */
object StressBaseline {
    const val WINDOW_DAYS = 28
    const val PRIOR_WINDOWS = 20.0

    /** Within-person SD of ultra-short ln RMSSD is about 0.25–0.35: a spread from a few days is floored there. */
    const val MIN_LN_SD = 0.25
    const val MIN_BPM_SD = 3.0
    const val USUAL = 30.0
    const val PER_SD = 18.0
    const val HRV_WEIGHT = 0.6
    const val HR_WEIGHT = 0.4
    private const val MAD_TO_SD = 1.4826
    const val MIN_NIGHT_WINDOWS = 3
    const val WEEK_ABOVE = 15

    data class Normal(val ln: Double, val lnSd: Double, val bpm: Double, val bpmSd: Double, val windows: Int)

    fun normal(history: StressHistory, today: Long): Normal? {
        val days = history.days.filter { it.day in (today - WINDOW_DAYS + 1)..today && !it.unusual }
        val ln = days.flatMap { it.awakeLn }.map { it.toDouble() }
        if (ln.isEmpty()) return null
        val bpm = days.flatMap { it.awakeBpm }.map { it.toDouble() }
        val lnMed = median(ln)
        val bpmMed = median(bpm)
        return Normal(
            lnMed,
            (MAD_TO_SD * median(ln.map { abs(it - lnMed) })).coerceAtLeast(MIN_LN_SD),
            bpmMed,
            (MAD_TO_SD * median(bpm.map { abs(it - bpmMed) })).coerceAtLeast(MIN_BPM_SD),
            ln.size
        )
    }

    /** The 0–100 score of a window: personal, blended with the population score while learning. */
    fun score(rmssdMs: Double, bpm: Int, normal: Normal?): Int {
        val population = StressIndex.score(rmssdMs).toDouble()
        if (normal == null) return population.roundToInt()
        val z = (ln(rmssdMs.coerceIn(3.0, 300.0)) - normal.ln) / normal.lnSd
        val hrZ = (bpm - normal.bpm) / normal.bpmSd
        val personal = USUAL + PER_SD * (-HRV_WEIGHT * z + HR_WEIGHT * hrZ)
        val c = normal.windows / (normal.windows + PRIOR_WINDOWS)
        return (personal * c + population * (1 - c)).roundToInt().coerceIn(0, 100)
    }

    fun nightLn(history: StressHistory, day: Long): Double? = history.days.firstOrNull {
        it.day == day
    }?.sleepLn?.takeIf { it.size >= MIN_NIGHT_WINDOWS }?.let { median(it.map(Float::toDouble)) }

    fun limits(history: StressHistory, today: Long): StressLimits {
        val n = normal(history, today)
        val nights = (1..WINDOW_DAYS).mapNotNull { nightLn(history, today - it) }
        val todayDay = history.days.firstOrNull { it.day == today }
        val week = history.days.filter { it.day in (today - 6)..today && !it.ill }.flatMap { it.scores }
        val before = history.days.filter { it.day in (today - WINDOW_DAYS + 1)..(today - 7) && !it.unusual }.flatMap { it.scores }
        val weekAbove = if (week.size >= 30 && before.size >= 60) (week.average() - before.average()).roundToInt() else null
        val lastNight = nightLn(history, today)
        val usualNight = nights.takeIf { it.size >= 3 }?.let { median(it) }
        // The insight needs a clearly higher week and recovery in sleep below the usual too.
        val nightsLower =
            usualNight != null && (0..2).mapNotNull { nightLn(history, today - it) }.let { l -> l.size >= 2 && l.average() < usualNight }
        return StressLimits(
            usualRmssd = n?.let { exp(it.ln) },
            usualBpm = n?.bpm?.roundToInt(),
            confidence = n?.let { it.windows / (it.windows + PRIOR_WINDOWS) } ?: 0.0,
            windows = n?.windows ?: 0,
            usualNightRmssd = usualNight?.let { exp(it) },
            lastNightRmssd = lastNight?.let { exp(it) },
            todayAverage = todayDay?.scores?.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
            todayHighMinutes = (todayDay?.scores?.count { StressIndex.level(it) == StressLevel.HIGH } ?: 0) * 15,
            weekAboveUsual = weekAbove?.takeIf { it >= WEEK_ABOVE && nightsLower }
        )
    }

    fun dayOf(ms: Long, context: HrContext, zone: ZoneId) = HeartBaseline.dayOf(ms, context, zone)

    private fun median(values: List<Double>) = values.sorted().let {
        if (it.size % 2 ==
            1
        ) {
            it[it.size / 2]
        } else {
            (it[it.size / 2 - 1] + it[it.size / 2]) / 2
        }
    }
}

/**
 * Applies stress windows to the history and decides the notice. Pure: the watch's rhythm window
 * worker measures; the simulation drives this directly.
 *
 * Sustained high stress (about an hour): the scored awake windows of the last 75 minutes, at
 * least [SUSTAINED] spanning 45 minutes or more, with a high median, an average of at least
 * [SPELL_AVERAGE] and the latest high. The simulation chose this over "three in a row": a single
 * deep window made false notices and a single calmer one hid real spells.
 * The hour after exercise is not scored (HRV recovers slowly). Not before [MIN_NOTIFY_WINDOWS]
 * learnt windows, not in the quiet hours, at most once in 3 hours and twice a day, and never
 * when illness explains it (a warmer night or a raised heart rate in sleep: the combined notice
 * covers that).
 */
class StressMonitor(private val zone: ZoneId = ZoneId.systemDefault(), private val newId: () -> String) {
    /**
     * A readable window. [context]: awake and still ([HrContext.REST]) or asleep; other windows are
     * not stress. [illness]: a warmer night or raised sleeping heart rate today (from the other parts).
     * Returns the new history, the sample (to store and sync) and a notice, if any.
     */
    fun onWindow(
        history: StressHistory,
        tsMs: Long,
        rmssdMs: Double,
        bpm: Int,
        context: HrContext,
        settings: MonitorSettings,
        illness: Boolean = false
    ): Triple<StressHistory, StressSample?, HealthAlert?> {
        if (!settings.stressActive || rmssdMs <= 0) return Triple(history, null, null)
        val lnR = (ln(rmssdMs) * 1000).roundToInt() / 1000f
        val dayKey = StressBaseline.dayOf(tsMs, context, zone)
        val d = history.day(dayKey)
        if (context == HrContext.SLEEP) {
            val sample = StressSample(tsMs, rmssdMs, bpm, null, context)
            return Triple(history.with(d.copy(sleepLn = d.sleepLn + lnR)), sample, null)
        }
        if (context == HrContext.EXERCISE) return Triple(history.copy(lastExerciseMs = tsMs), null, null)
        if (context != HrContext.REST) return Triple(history, null, null)
        // Still recovering from exercise (Stanley 2013): low HRV then is not stress.
        if (history.lastExerciseMs?.let { tsMs - it < RECOVERY_MS } == true) return Triple(history, null, null)
        val normal = StressBaseline.normal(history, dayKey)
        val score = StressBaseline.score(rmssdMs, bpm, normal)
        val high = StressIndex.level(score) == StressLevel.HIGH
        val sample = StressSample(tsMs, rmssdMs, bpm, score, context)
        // High windows are not learnt: a stressful spell must not become the normal.
        var h = history.with(
            d.copy(
                awakeLn = if (high) d.awakeLn else d.awakeLn + lnR,
                awakeBpm = if (high) d.awakeBpm else d.awakeBpm + bpm,
                scores = d.scores + score,
                unusual = d.unusual || illness,
                ill = d.ill || illness
            )
        ).copy(recent = (history.recent + sample).filter { it.tsMs > tsMs - RECENT_MS })

        // Every scored window of the last 75 minutes: their median high, so one deep window (an
        // artefact, a sneeze) can't make a spell and one calmer window can't break it, and their
        // average at least [SPELL_AVERAGE].
        val last = h.recent.filter { it.tsMs > tsMs - LOOKBACK_MS }
        val scores = last.map { it.score ?: 0 }
        val median = scores.sorted().let {
            if (it.size % 2 ==
                1
            ) {
                it[it.size / 2].toDouble()
            } else {
                (it[it.size / 2 - 1] + it[it.size / 2]) / 2.0
            }
        }
        val sustained = high &&
            last.size >= SUSTAINED &&
            StressIndex.level(median.roundToInt()) == StressLevel.HIGH &&
            scores.average() >= SPELL_AVERAGE &&
            tsMs - last.first().tsMs >= MIN_SPAN_MS
        val confident = (normal?.windows ?: 0) >= MIN_NOTIFY_WINDOWS
        val local = Instant.ofEpochMilli(tsMs).atZone(zone)
        val quiet = settings.isQuiet(local.hour * 60 + local.minute)
        val cooled = h.lastAlertMs?.let { tsMs - it >= COOLDOWN_MS } ?: true
        val today = h.day(dayKey)
        if (!sustained || !confident || quiet || !cooled || illness || today.notices >= MAX_PER_DAY || !settings.stressNotifications) {
            return Triple(h, sample, null)
        }
        h = h.with(today.copy(notices = today.notices + 1, unusual = true)).copy(lastAlertMs = tsMs)
        val alert = HealthAlert(
            newId(),
            AlertKind.HIGH_HEART_RATE,
            tsMs,
            bpm,
            context = HrContext.REST,
            vital = VitalAlert.STRESS,
            value = score.toFloat()
        )
        return Triple(h, sample, alert)
    }

    companion object {
        const val SUSTAINED = 4
        const val MIN_SPAN_MS = 45 * 60_000L
        const val LOOKBACK_MS = 75 * 60_000L
        const val RECENT_MS = 90 * 60_000L
        const val COOLDOWN_MS = 3 * 3_600_000L
        const val MAX_PER_DAY = 2
        const val SPELL_AVERAGE = 62

        /** Notices only once the normal has settled: about 3–4 days of wearing (the score is personal from day one). */
        const val MIN_NOTIFY_WINDOWS = 100
        const val RECOVERY_MS = 60 * 60_000L
    }
}
