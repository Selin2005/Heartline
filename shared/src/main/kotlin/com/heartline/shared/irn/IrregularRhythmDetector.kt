package com.heartline.shared.irn

import com.heartline.shared.hr.AlertKind
import com.heartline.shared.hr.HealthAlert
import com.heartline.shared.hr.HrMinute
import com.heartline.shared.hr.HrSample
import com.heartline.shared.hr.MonitorSettings
import com.heartline.shared.hr.RrFeatures
import kotlinx.serialization.Serializable

@Serializable
data class WindowResult(val startMs: Long, val irregular: Boolean, val meanBpm: Int)

/** Persisted detector state (a few dozen windows at most). */
@Serializable
data class IrnState(val windows: List<WindowResult> = emptyList(), val lastAlertMs: Long? = null)

/**
 * Irregular rhythm notification logic (MASTER_PLAN F9): the watch checks one-minute
 * tachograms while the wearer is still. A window is irregular when its IBIs are
 * irregularly irregular (RrFeatures). An alert needs [required] irregular out of the last
 * [consider] analysed windows within [lookbackMs], and is followed by a [cooldownMs] quiet period.
 */
class IrregularRhythmDetector(
    private val required: Int = 5,
    private val consider: Int = 6,
    private val lookbackMs: Long = 48 * HOUR,
    private val cooldownMs: Long = 24 * HOUR,
    private val minBeatsPerWindow: Int = 40
) {
    /** Analyses one window of samples; returns the new state and an alert if one fires. */
    fun onWindow(state: IrnState, samples: List<HrSample>, idFactory: () -> String): Pair<IrnState, HealthAlert?> {
        if (samples.isEmpty() || samples.any { it.moving || !it.onBody }) return state to null
        val ibis = samples.flatMap { it.ibiMs }.filter { it in 300..2000 }
        if (ibis.size < minBeatsPerWindow) return state to null
        val features = RrFeatures.of(ibis.map { it.toDouble() }) ?: return state to null
        val start = samples.first().tsMs
        val window = WindowResult(start, features.isIrregular, (60_000 / features.meanMs).toInt())
        val now = samples.last().tsMs
        val windows = (state.windows + window).filter { now - it.startMs <= lookbackMs }.takeLast(consider * 4)
        var next = state.copy(windows = windows)

        val recent = windows.takeLast(consider)
        val inCooldown = state.lastAlertMs?.let { now - it < cooldownMs } == true
        if (!inCooldown && recent.size >= consider && recent.count { it.irregular } >= required) {
            val irregular = recent.filter { it.irregular }
            val alert =
                HealthAlert(
                    idFactory(),
                    AlertKind.IRREGULAR_RHYTHM,
                    now,
                    irregular.map {
                        it.meanBpm
                    }.average().toInt(),
                    irregular.map { it.startMs }
                )
            next = next.copy(lastAlertMs = now, windows = emptyList())
            return next to alert
        }
        return next to null
    }

    companion object {
        const val HOUR = 3_600_000L
    }
}

/**
 * High/low heart rate notifications: the rate must stay beyond the threshold for [sustainMinutes]
 * consecutive resting minutes; one alert per kind per [cooldownMs].
 */
class HeartRateAlertRules(private val sustainMinutes: Int = 10, private val cooldownMs: Long = 3 * IrregularRhythmDetector.HOUR) {
    fun evaluate(
        minutes: List<HrMinute>,
        settings: MonitorSettings,
        lastAlerts: Map<AlertKind, Long>,
        idFactory: () -> String
    ): List<HealthAlert> {
        if (!settings.heartRateAlertsEnabled || minutes.size < sustainMinutes) return emptyList()
        val tail = minutes.sortedBy { it.minuteStartMs }.takeLast(sustainMinutes)
        val contiguous = tail.zipWithNext().all { (a, b) -> b.minuteStartMs - a.minuteStartMs == 60_000L }
        if (!contiguous || tail.any { !it.resting }) return emptyList()
        val now = tail.last().minuteStartMs + 60_000L
        fun ready(kind: AlertKind) = lastAlerts[kind]?.let { now - it >= cooldownMs } ?: true
        return buildList {
            if (tail.all { it.avgBpm > settings.highBpm } && ready(AlertKind.HIGH_HEART_RATE)) {
                add(HealthAlert(idFactory(), AlertKind.HIGH_HEART_RATE, now, tail.maxOf { it.avgBpm }))
            }
            if (tail.all { it.avgBpm < settings.lowBpm } && ready(AlertKind.LOW_HEART_RATE)) {
                add(HealthAlert(idFactory(), AlertKind.LOW_HEART_RATE, now, tail.minOf { it.avgBpm }))
            }
        }
    }
}
