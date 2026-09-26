// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.ui.ecg

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.heartline.phone.R
import com.heartline.phone.ui.components.PillButton
import com.heartline.phone.ui.components.label
import com.heartline.phone.ui.theme.HeartlineTheme
import com.heartline.shared.model.Symptom

/** Content of the symptoms bottom sheet (SHM lets you tag how you felt). */
@Composable
fun SymptomsSheetContent(initial: List<Symptom>, onSave: (List<Symptom>) -> Unit) {
    val colors = HeartlineTheme.colors
    val selected = remember(initial) { mutableStateListOf<Symptom>().apply { addAll(initial) } }
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.ecg_symptoms_title), style = MaterialTheme.typography.titleLarge, color = colors.onBackground)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.ecg_symptoms_hint), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Symptom.entries.forEach { symptom ->
            val checked = symptom in selected
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { if (checked) selected.remove(symptom) else selected.add(symptom) }
                    .padding(vertical = 4.dp),
            ) {
                Checkbox(
                    checked = checked,
                    onCheckedChange = null,
                    colors = CheckboxDefaults.colors(checkedColor = colors.primary),
                )
                Spacer(Modifier.width(12.dp))
                Text(stringResource(symptom.label), style = MaterialTheme.typography.bodyLarge, color = colors.onBackground)
            }
        }
        Spacer(Modifier.height(16.dp))
        PillButton(stringResource(R.string.action_save), onClick = { onSave(Symptom.entries.filter { it in selected }) })
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
fun DeleteRecordingDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val colors = HeartlineTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = { Text(stringResource(R.string.ecg_delete_confirm_title)) },
        text = { Text(stringResource(R.string.ecg_delete_confirm_body)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_delete), color = colors.statusAlert) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel), color = colors.primary) } },
    )
}
