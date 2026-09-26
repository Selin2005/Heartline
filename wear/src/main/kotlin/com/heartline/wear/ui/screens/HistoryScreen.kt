// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.heartline.shared.model.EcgResult
import com.heartline.shared.model.Metric
import com.heartline.wear.R
import com.heartline.wear.ui.components.icon
import com.heartline.wear.ui.components.label
import com.heartline.wear.ui.theme.WearColors

/** One past measurement; [result] is set for ECG, [value] for the other metrics. */
data class HistoryItem(val id: String, val metric: Metric, val result: EcgResult?, val value: String?, val time: String, val synced: Boolean)

@Composable
fun HistoryScreen(items: List<HistoryItem>) {
    val state = rememberTransformingLazyColumnState()
    ScreenScaffold(scrollState = state) { contentPadding ->
        TransformingLazyColumn(state = state, contentPadding = contentPadding) {
            item { ListHeader { Text(stringResource(R.string.history)) } }
            if (items.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.history_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = WearColors.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            items.forEach { entry ->
                item(key = entry.id) {
                    val tint = entry.result?.let { WearColors.severity(it.severity) } ?: WearColors.metric(entry.metric)
                    Button(
                        onClick = {},
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.filledTonalButtonColors(),
                        icon = { Icon(entry.metric.icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp)) },
                        label = { Text(entry.result?.let { stringResource(it.label) } ?: entry.value.orEmpty(), maxLines = 1) },
                        secondaryLabel = {
                            val time = if (entry.synced) entry.time else stringResource(R.string.history_pending, entry.time)
                            Text(time, maxLines = 2, color = WearColors.onSurfaceVariant)
                        },
                    )
                }
            }
        }
    }
}
