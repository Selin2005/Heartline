// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.sensor

import com.heartline.shared.model.Metric

/**
 * Mirror of the Samsung SDK's HealthTrackerType values we use, so that the rest of the code
 * never depends on the SDK directly.
 */
enum class TrackerKind(val onDemand: Boolean) {
    ECG_ON_DEMAND(true),
    PPG_ON_DEMAND(true),
    SPO2_ON_DEMAND(true),
    SKIN_TEMPERATURE_ON_DEMAND(true),
    BIA_ON_DEMAND(true),
    MF_BIA_ON_DEMAND(true),
    HEART_RATE_CONTINUOUS(false),
    PPG_CONTINUOUS(false),
    SKIN_TEMPERATURE_CONTINUOUS(false),
    EDA_CONTINUOUS(false),
    ACCELEROMETER_CONTINUOUS(false)
}

/** Which trackers each product metric needs; a metric is offered only if all are supported. */
object MetricRequirements {
    fun trackersFor(metric: Metric): Set<TrackerKind> = when (metric) {
        Metric.ECG -> setOf(TrackerKind.ECG_ON_DEMAND)
        Metric.BLOOD_PRESSURE -> setOf(TrackerKind.PPG_ON_DEMAND)
        Metric.HEART_RATE -> setOf(TrackerKind.HEART_RATE_CONTINUOUS)
        Metric.SPO2 -> setOf(TrackerKind.SPO2_ON_DEMAND)
        Metric.SKIN_TEMPERATURE -> setOf(TrackerKind.SKIN_TEMPERATURE_ON_DEMAND)
        Metric.BODY_COMPOSITION -> setOf(TrackerKind.BIA_ON_DEMAND)
        Metric.STRESS -> setOf(TrackerKind.HEART_RATE_CONTINUOUS)
    }

    fun supportedMetrics(trackers: Set<TrackerKind>): List<Metric> = Metric.entries.filter { trackers.containsAll(trackersFor(it)) }
}

/** Runtime permission names per tracker (docs/SAMSUNG_HEALTH_SENSOR_SDK.md §4). */
object PermissionPolicy {
    const val BODY_SENSORS = "android.permission.BODY_SENSORS"
    const val READ_ADDITIONAL_HEALTH_DATA = "com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA"
    const val READ_HEART_RATE = "android.permission.health.READ_HEART_RATE"
    const val READ_OXYGEN_SATURATION = "android.permission.health.READ_OXYGEN_SATURATION"
    const val READ_SKIN_TEMPERATURE = "android.permission.health.READ_SKIN_TEMPERATURE"
    const val ACTIVITY_RECOGNITION = "android.permission.ACTIVITY_RECOGNITION"

    /** Android 16 (API 36) moved body sensors to granular health permissions. */
    const val GRANULAR_HEALTH_PERMISSIONS_SDK = 36

    fun permissionsFor(tracker: TrackerKind, sdkInt: Int): Set<String> {
        if (tracker == TrackerKind.ACCELEROMETER_CONTINUOUS) return setOf(ACTIVITY_RECOGNITION)
        if (sdkInt < GRANULAR_HEALTH_PERMISSIONS_SDK) return setOf(BODY_SENSORS)
        return when (tracker) {
            TrackerKind.HEART_RATE_CONTINUOUS -> setOf(READ_HEART_RATE)
            TrackerKind.SPO2_ON_DEMAND -> setOf(READ_OXYGEN_SATURATION)
            TrackerKind.SKIN_TEMPERATURE_ON_DEMAND, TrackerKind.SKIN_TEMPERATURE_CONTINUOUS -> setOf(READ_SKIN_TEMPERATURE)
            else -> setOf(READ_ADDITIONAL_HEALTH_DATA)
        }
    }

    fun permissionsFor(metric: Metric, sdkInt: Int): Set<String> =
        MetricRequirements.trackersFor(metric).flatMap { permissionsFor(it, sdkInt) }.toSet()
}
