// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.tile

import android.content.Context
import com.heartline.shared.bp.BpCategory
import com.heartline.shared.model.Metric
import com.heartline.shared.model.Severity
import com.heartline.shared.profile.StressLevel
import com.heartline.wear.MainActivity
import com.heartline.wear.R
import com.heartline.wear.bp.WatchBpStore
import com.heartline.wear.data.WatchRecordStore
import com.heartline.wear.monitor.WatchSettingsStore
import com.heartline.wear.quick.WatchProfileStore
import com.heartline.wear.ui.components.label
import kotlinx.coroutines.flow.first

/** Watch routes the tiles open (MainActivity.EXTRA_ROUTE); must match the watch nav graph. */
internal object TileRoutes {
    const val LAUNCHER = "launcher"
    const val HEART_RATE = MainActivity.ROUTE_HEART_RATE
    const val ECG = MainActivity.ROUTE_ECG
    const val BLOOD_PRESSURE = MainActivity.ROUTE_BP

    fun quick(metric: Metric) = "quick/${metric.name}"

    fun measure(metric: Metric) = when (metric) {
        Metric.ECG -> ECG
        Metric.BLOOD_PRESSURE -> BLOOD_PRESSURE
        Metric.HEART_RATE -> HEART_RATE
        else -> quick(metric)
    }
}

/** The app's metric icons (vector drawables), used by the cards and complications. */
internal object TileIcons {
    fun drawable(metric: Metric) = when (metric) {
        Metric.ECG -> R.drawable.ic_metric_ecg
        Metric.BLOOD_PRESSURE -> R.drawable.ic_metric_bp
        Metric.HEART_RATE -> R.drawable.ic_metric_heart
        Metric.SPO2 -> R.drawable.ic_metric_spo2
        Metric.SKIN_TEMPERATURE -> R.drawable.ic_metric_temp
        Metric.BODY_COMPOSITION -> R.drawable.ic_metric_body
        Metric.STRESS -> R.drawable.ic_metric_stress
    }

    /** The same icons for the cards: copies without arc commands, which Remote Compose can't draw yet. */
    fun card(metric: Metric) = when (metric) {
        Metric.BLOOD_PRESSURE -> R.drawable.ic_card_bp
        Metric.STRESS -> R.drawable.ic_card_stress
        else -> drawable(metric)
    }
}

internal fun BpCategory.severity() = when (this) {
    BpCategory.NORMAL -> Severity.NORMAL
    BpCategory.ELEVATED, BpCategory.HIGH_STAGE_1 -> Severity.WARN
    BpCategory.HIGH_STAGE_2, BpCategory.CRISIS -> Severity.ALERT
}

internal fun StressLevel.severity() = when (this) {
    StressLevel.LOW -> Severity.NORMAL
    StressLevel.MEDIUM -> Severity.WARN
    StressLevel.HIGH -> Severity.ALERT
}

internal val StressLevel.label: Int
    get() = when (this) {
        StressLevel.LOW -> R.string.stress_low
        StressLevel.MEDIUM -> R.string.stress_medium
        StressLevel.HIGH -> R.string.stress_high
    }

/** Reads what tiles and complications show from the watch's own stores (works without the phone). */
class TileDataLoader(
    private val context: Context,
    private val records: WatchRecordStore,
    private val settings: WatchSettingsStore,
    private val bp: WatchBpStore,
    private val profiles: WatchProfileStore? = null,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun load(): TileData {
        val nowMs = now()
        val monitor = settings.settings.value
        val showName = monitor.showNameOnWatch
        val zone = java.time.ZoneId.systemDefault()
        val streak = com.heartline.shared.profile.Streak.of(
            com.heartline.shared.profile.DailyGoal.byDay(records.history.first(), zone),
            monitor.dailyGoal,
            java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate(),
        )
        return TileData.from(
            records.recent.first(),
            settings.latestHeartRate,
            bp.calibration.value?.takeIf { it.isValid(nowMs) }?.daysLeft(nowMs),
            settings.heartToday(),
            nowMs = nowMs,
            name = profiles?.profile?.value?.displayName?.takeIf { showName },
        ) { ecg -> ecg.result?.let { context.getString(it.label) } }.copy(goal = monitor.dailyGoal, streak = streak, accent = monitor.accent)
    }
}
