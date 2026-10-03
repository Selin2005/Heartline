// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.irn

import com.heartline.shared.hr.AlertKind
import com.heartline.shared.hr.HealthAlert
import com.heartline.shared.hr.HeartBaseline
import com.heartline.shared.hr.HeartLimits
import com.heartline.shared.hr.HrContext
import com.heartline.shared.hr.HrMinute
import com.heartline.shared.hr.MonitorSettings

/**
 * High/low heart rate notifications against the wearer's personal limits ([HeartLimits]), judged
 * by what the wearer was doing:
 * - awake and still: above the high limit or below the low limit;
 * - asleep: above the high limit, or below the (lower) sleep limit;
 * - exercise or moving about: never low, and high only above the personal exercise maximum, held
 *   for [exerciseSustainMs].
 *
 * Background heart rate arrives every few minutes at rest and every second during a workout, so a
 * rate is "held" when every reading over at least [restSustainMs] is beyond the limit, with at
 * least [minReadings] readings and no gap longer than [restMaxGapMs]. Minutes right after exercise
 * (recovery) are not judged by the rest limits. One alert per kind and context per [cooldownMs].
 */
class HeartRateAlertRules(
    private val restSustainMs: Long = 10 * MINUTE,
    private val restMaxGapMs: Long = 10 * MINUTE,
    private val exerciseSustainMs: Long = 3 * MINUTE,
    private val exerciseMaxGapMs: Long = 2 * MINUTE,
    private val minReadings: Int = 3,
    private val exerciseRecoveryMs: Long = 15 * MINUTE,
    private val activeRecoveryMs: Long = 5 * MINUTE,
    private val cooldownMs: Long = 3 * IrregularRhythmDetector.HOUR
) {
    /**
     * [minutes]: the recent minutes (any order). [limits]: the wearer's personal limits
     * ([com.heartline.shared.hr.HeartBaseline]). [lastAlerts]: when each alert last fired, by [key].
     */
    fun evaluate(
        minutes: List<HrMinute>,
        settings: MonitorSettings,
        limits: HeartLimits,
        lastAlerts: Map<String, Long>,
        idFactory: () -> String
    ): List<HealthAlert> {
        if (!settings.heartActive || !settings.heartRateAlertsEnabled || minutes.isEmpty()) return emptyList()
        val sorted = minutes.sortedBy { it.minuteStartMs }
        val now = sorted.last().minuteStartMs + MINUTE
        fun ready(kind: AlertKind, context: HrContext) = lastAlerts[key(kind, context)]?.let { now - it >= cooldownMs } ?: true
        val recovering = recoveryCheck(sorted)

        // Personal only once learnt: before that, a "usual" would be the population guess (a
        // comparison with nothing), and right after install the watch can't yet tell rest from
        // moving about. Then only the safety net applies, and no usual is named.
        fun known(context: HrContext) =
            (if (context == HrContext.SLEEP) limits.sleepConfidence else limits.restConfidence) >= HeartBaseline.PERSONAL_FROM
        fun highLimit(m: HrMinute) = if (known(m.activity)) limits.high else maxOf(limits.high, HeartBaseline.SAFETY_HIGH)
        fun lowLimit(m: HrMinute) = if (m.activity == HrContext.SLEEP) limits.sleepLow else limits.low
        fun normal(context: HrContext) = if (!known(context)) {
            null
        } else if (context ==
            HrContext.SLEEP
        ) {
            limits.sleepNormal
        } else {
            limits.restNormal
        }

        return buildList {
            if (sorted.last().activity.calm) {
                held(sorted, restSustainMs, restMaxGapMs) { it.activity.calm && !recovering(it) && it.avgBpm > highLimit(it) }?.let { run ->
                    val context = run.last().activity
                    if (ready(AlertKind.HIGH_HEART_RATE, context)) {
                        add(
                            HealthAlert(
                                idFactory(),
                                AlertKind.HIGH_HEART_RATE,
                                now,
                                run.maxOf {
                                    it.avgBpm
                                },
                                threshold = highLimit(run.last()),
                                context = context,
                                normal = normal(context)
                            )
                        )
                    }
                }
                held(sorted, restSustainMs, restMaxGapMs) {
                    settings.lowHeartRateActive && it.activity.calm && it.avgBpm < lowLimit(it)
                }?.let { run ->
                    val context = run.last().activity
                    if (ready(AlertKind.LOW_HEART_RATE, context)) {
                        add(
                            HealthAlert(
                                idFactory(),
                                AlertKind.LOW_HEART_RATE,
                                now,
                                run.minOf {
                                    it.avgBpm
                                },
                                threshold = lowLimit(run.last()),
                                context = context,
                                normal = normal(context)
                            )
                        )
                    }
                }
            } else {
                held(sorted, exerciseSustainMs, exerciseMaxGapMs) { !it.activity.calm && it.avgBpm > limits.exerciseMax }?.let { run ->
                    if (ready(AlertKind.HIGH_HEART_RATE, HrContext.EXERCISE)) {
                        add(
                            HealthAlert(
                                idFactory(),
                                AlertKind.HIGH_HEART_RATE,
                                now,
                                run.maxOf {
                                    it.avgBpm
                                },
                                threshold = limits.exerciseMax,
                                context = HrContext.EXERCISE
                            )
                        )
                    }
                }
            }
        }
    }

    /**
     * The newest run of minutes that all match [beyond], or null unless it covers [sustainMs] with
     * [minReadings] readings and no gap over [maxGapMs].
     */
    private fun held(sorted: List<HrMinute>, sustainMs: Long, maxGapMs: Long, beyond: (HrMinute) -> Boolean): List<HrMinute>? {
        val run = ArrayDeque<HrMinute>()
        for (m in sorted.asReversed()) {
            if (!beyond(m)) break
            if (run.isNotEmpty() && run.first().minuteStartMs - m.minuteStartMs > maxGapMs) break
            run.addFirst(m)
        }
        if (run.size < minReadings) return null
        val covered = run.last().minuteStartMs - run.first().minuteStartMs + MINUTE
        return run.toList().takeIf { covered >= sustainMs }
    }

    /** True for minutes soon after exercise or moving about, when a raised rate is just recovery. */
    private fun recoveryCheck(sorted: List<HrMinute>): (HrMinute) -> Boolean {
        val lastExercise = mutableListOf<Long>()
        val lastActive = mutableListOf<Long>()
        sorted.forEach {
            when (it.activity) {
                HrContext.EXERCISE -> lastExercise += it.minuteStartMs
                HrContext.ACTIVE -> lastActive += it.minuteStartMs
                else -> Unit
            }
        }
        return { m ->
            lastExercise.any { it < m.minuteStartMs && m.minuteStartMs - it < exerciseRecoveryMs } ||
                lastActive.any { it < m.minuteStartMs && m.minuteStartMs - it < activeRecoveryMs }
        }
    }

    companion object {
        const val MINUTE = 60_000L

        /** Cooldown key: exercise alerts are spaced separately from rest/sleep ones. */
        fun key(kind: AlertKind, context: HrContext?): String = when (context) {
            HrContext.EXERCISE, HrContext.ACTIVE -> "$kind/EXERCISE"
            else -> kind.name
        }

        fun key(alert: HealthAlert) = when (alert.trend) {
            null -> key(alert.kind, alert.context)
            else -> "TREND/${alert.trend}"
        }
    }
}

/** Awake-and-still or asleep: the minutes judged by the rest limits. */
val HrContext.calm: Boolean get() = this == HrContext.REST || this == HrContext.SLEEP
