// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.widget

import android.content.Context
import com.heartline.phone.R
import com.heartline.phone.ui.bp.label
import com.heartline.phone.ui.components.label
import com.heartline.shared.bp.BpCategory
import com.heartline.shared.model.EcgResult
import com.heartline.shared.model.Metric
import com.heartline.shared.model.Severity
import com.heartline.shared.profile.StressLevel

/** One metric as a single tile shows it: the value, its status line and when it was measured. */
data class MetricInfo(
    val metric: Metric,
    val title: String,
    val shortTitle: String,
    val value: String?,
    val unit: String?,
    /** Short value for the smallest tile (no unit, few characters). */
    val tinyValue: String?,
    val status: String? = null,
    val severity: Severity? = null,
    val at: String? = null,
) {
    val hasData: Boolean get() = value != null
}

object WidgetMetrics {
    fun info(context: Context, s: WidgetSnapshot, metric: Metric): MetricInfo {
        val title = context.getString(metric.title)
        val short = context.getString(metric.shortLabel)
        return when (metric) {
            Metric.HEART_RATE -> s.heartRate.let { hr ->
                MetricInfo(metric, title, short, hr?.bpm?.toString(), context.getString(R.string.unit_bpm), hr?.bpm?.toString(), hr?.resting?.let { context.getString(R.string.widget_resting, it) }, null, hr?.at)
            }
            Metric.ECG -> s.ecg.let { e ->
                MetricInfo(metric, title, short, e?.let { context.getString(it.result.label) }, null, e?.let { context.getString(it.result.shortLabel) }, e?.bpm?.let { context.getString(R.string.widget_bpm_value, it) }, e?.result?.severity, e?.at)
            }
            Metric.BLOOD_PRESSURE -> s.bp.let { bp ->
                MetricInfo(
                    metric,
                    title,
                    short,
                    bp?.let { "${it.systolic}/${it.diastolic}" },
                    context.getString(R.string.unit_mmhg),
                    bp?.let { "${it.systolic}/${it.diastolic}" },
                    bp?.let { context.getString(it.category.label) } ?: calibrationText(context, s.calibration),
                    bp?.category?.severity(),
                    bp?.at,
                )
            }
            Metric.STRESS -> s.stress.let { st ->
                MetricInfo(metric, title, short, st?.score?.toString(), null, st?.score?.toString(), st?.let { context.getString(it.level.label()) }, st?.level?.severity(), st?.at)
            }
            Metric.SPO2 -> reading(metric, title, short, s.spo2)
            Metric.SKIN_TEMPERATURE -> reading(metric, title, short, s.temperature)
            Metric.BODY_COMPOSITION -> reading(metric, title, short, s.body).copy(
                status = s.weightKg?.let { context.getString(R.string.widget_weight, it) },
            )
        }
    }

    private fun reading(metric: Metric, title: String, short: String, r: WidgetSnapshot.Reading?) =
        MetricInfo(metric, title, short, r?.value, r?.unit, r?.value, at = r?.at)
}

val Metric.title: Int
    get() = when (this) {
        Metric.ECG -> R.string.metric_ecg
        Metric.BLOOD_PRESSURE -> R.string.metric_bp
        Metric.HEART_RATE -> R.string.metric_hr
        Metric.SPO2 -> R.string.metric_spo2
        Metric.SKIN_TEMPERATURE -> R.string.metric_skin_temp
        Metric.BODY_COMPOSITION -> R.string.metric_body
        Metric.STRESS -> R.string.metric_stress
    }

/** Short names for small tiles and round buttons, where full names don't fit. */
val Metric.shortLabel: Int
    get() = when (this) {
        Metric.ECG -> R.string.widget_short_ecg
        Metric.BLOOD_PRESSURE -> R.string.widget_short_bp
        Metric.HEART_RATE -> R.string.widget_short_hr
        Metric.SPO2 -> R.string.widget_short_spo2
        Metric.SKIN_TEMPERATURE -> R.string.widget_short_temp
        Metric.BODY_COMPOSITION -> R.string.widget_short_body
        Metric.STRESS -> R.string.widget_short_stress
    }

val EcgResult.shortLabel: Int
    get() = when (this) {
        EcgResult.SINUS_RHYTHM -> R.string.widget_ecg_short_sinus
        EcgResult.AFIB_SIGNS -> R.string.widget_ecg_short_afib
        EcgResult.HIGH_HEART_RATE -> R.string.widget_ecg_short_high
        EcgResult.LOW_HEART_RATE -> R.string.widget_ecg_short_low
        EcgResult.INCONCLUSIVE -> R.string.widget_ecg_short_inconclusive
        EcgResult.POOR_RECORDING -> R.string.widget_ecg_short_poor
    }

fun BpCategory.severity() = when (this) {
    BpCategory.NORMAL -> Severity.NORMAL
    BpCategory.ELEVATED, BpCategory.HIGH_STAGE_1 -> Severity.WARN
    BpCategory.HIGH_STAGE_2, BpCategory.CRISIS -> Severity.ALERT
}

fun StressLevel.severity() = when (this) {
    StressLevel.LOW -> Severity.NORMAL
    StressLevel.MEDIUM -> Severity.WARN
    StressLevel.HIGH -> Severity.ALERT
}

fun StressLevel.label() = when (this) {
    StressLevel.LOW -> R.string.widget_stress_low
    StressLevel.MEDIUM -> R.string.widget_stress_medium
    StressLevel.HIGH -> R.string.widget_stress_high
}

fun calibrationText(context: Context, calibration: WidgetSnapshot.Calibration) = when (calibration) {
    is WidgetSnapshot.Calibration.Valid -> context.getString(R.string.widget_calibration_days, calibration.daysLeft)
    WidgetSnapshot.Calibration.Expired -> context.getString(R.string.widget_calibration_expired)
    WidgetSnapshot.Calibration.None -> context.getString(R.string.widget_calibration_needed)
}

/**
 * Where a blood-pressure reading sits on the category bar (AHA bands), 0–1, with the bands'
 * widths and colours.
 */
object BpBar {
    val bands = listOf(0.4f to ChartTone.NORMAL, 0.15f to ChartTone.WARN, 0.2f to ChartTone.WARN, 0.25f to ChartTone.ALERT)

    fun position(systolic: Int, diastolic: Int): Float = when (BpCategory.of(systolic, diastolic)) {
        BpCategory.NORMAL -> 0.4f * ((systolic - 90).coerceIn(0, 30) / 30f)
        BpCategory.ELEVATED -> 0.4f + 0.15f * ((systolic - 120).coerceIn(0, 10) / 10f)
        BpCategory.HIGH_STAGE_1 -> 0.55f + 0.2f * ((systolic - 130).coerceIn(0, 10) / 10f)
        BpCategory.HIGH_STAGE_2, BpCategory.CRISIS -> 0.75f + 0.25f * ((systolic - 140).coerceIn(0, 40) / 40f)
    }.coerceIn(0.03f, 0.97f)
}
