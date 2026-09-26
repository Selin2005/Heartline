// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.heartline.phone.R
import com.heartline.phone.ui.theme.HeartlineTheme
import com.heartline.shared.model.EcgResult
import com.heartline.shared.model.Metric
import com.heartline.shared.model.Symptom

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

val EcgResult.explanation: Int
    @StringRes get() = when (this) {
        EcgResult.SINUS_RHYTHM -> R.string.ecg_explain_sinus
        EcgResult.AFIB_SIGNS -> R.string.ecg_explain_afib
        EcgResult.HIGH_HEART_RATE -> R.string.ecg_explain_high_hr
        EcgResult.LOW_HEART_RATE -> R.string.ecg_explain_low_hr
        EcgResult.INCONCLUSIVE -> R.string.ecg_explain_inconclusive
        EcgResult.POOR_RECORDING -> R.string.ecg_explain_poor
    }

val Symptom.label: Int
    @StringRes get() = when (this) {
        Symptom.PALPITATIONS -> R.string.symptom_palpitations
        Symptom.DIZZINESS -> R.string.symptom_dizziness
        Symptom.FATIGUE -> R.string.symptom_fatigue
        Symptom.SHORTNESS_OF_BREATH -> R.string.symptom_breath
        Symptom.CHEST_PRESSURE -> R.string.symptom_chest
        Symptom.FAINTING -> R.string.symptom_fainting
        Symptom.OTHER -> R.string.symptom_other
    }

/** Coloured dot + result name, as on the SHM result list. */
@Composable
fun ResultBadge(result: EcgResult, modifier: Modifier = Modifier, large: Boolean = false) {
    val color = HeartlineTheme.colors.severity(result.severity)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier) {
        androidx.compose.foundation.layout.Box(
            Modifier
                .size(if (large) 12.dp else 9.dp)
                .clip(CircleShape)
                .background(color),
        )
        Text(
            stringResource(result.label),
            style = if (large) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall,
            color = HeartlineTheme.colors.onBackground,
        )
    }
}
