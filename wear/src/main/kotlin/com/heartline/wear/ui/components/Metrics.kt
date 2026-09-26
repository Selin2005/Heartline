// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.ui.components

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.ui.graphics.vector.ImageVector
import com.heartline.shared.model.EcgResult
import com.heartline.shared.model.Metric
import com.heartline.wear.R

val Metric.icon: ImageVector
    get() = when (this) {
        Metric.ECG -> Icons.Rounded.MonitorHeart
        Metric.BLOOD_PRESSURE -> Icons.Rounded.Speed
        Metric.HEART_RATE -> Icons.Rounded.Favorite
        Metric.SPO2 -> Icons.Rounded.WaterDrop
        Metric.SKIN_TEMPERATURE -> Icons.Rounded.Thermostat
        Metric.BODY_COMPOSITION -> Icons.Rounded.Accessibility
        Metric.STRESS -> Icons.Rounded.SelfImprovement
    }

val Metric.label: Int
    @StringRes get() = when (this) {
        Metric.ECG -> R.string.metric_ecg
        Metric.BLOOD_PRESSURE -> R.string.metric_bp
        Metric.HEART_RATE -> R.string.metric_hr
        Metric.SPO2 -> R.string.metric_spo2
        Metric.SKIN_TEMPERATURE -> R.string.metric_skin_temp
        Metric.BODY_COMPOSITION -> R.string.metric_body
        Metric.STRESS -> R.string.metric_stress
    }

val EcgResult.label: Int
    @StringRes get() = when (this) {
        EcgResult.SINUS_RHYTHM -> R.string.ecg_result_sinus
        EcgResult.AFIB_SIGNS -> R.string.ecg_result_afib
        EcgResult.HIGH_HEART_RATE -> R.string.ecg_result_high_hr
        EcgResult.LOW_HEART_RATE -> R.string.ecg_result_low_hr
        EcgResult.INCONCLUSIVE -> R.string.ecg_result_inconclusive
        EcgResult.POOR_RECORDING -> R.string.ecg_result_poor
    }
