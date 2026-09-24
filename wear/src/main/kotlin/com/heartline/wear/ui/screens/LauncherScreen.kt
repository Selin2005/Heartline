package com.heartline.wear.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.heartline.shared.model.Metric
import com.heartline.wear.R
import com.heartline.wear.ui.components.icon
import com.heartline.wear.ui.components.label
import com.heartline.wear.ui.theme.WearColors

data class LauncherEntry(val metric: Metric, val lastValue: String?)

/** App list shown on launch; only metrics the watch supports are passed in (capability gating). */
@Composable
fun LauncherScreen(
    entries: List<LauncherEntry>,
    onOpen: (Metric) -> Unit = {},
    onHistory: () -> Unit = {},
    onSettings: () -> Unit = {},
) {
    val state = rememberTransformingLazyColumnState()
    ScreenScaffold(scrollState = state) { contentPadding ->
        TransformingLazyColumn(state = state, contentPadding = contentPadding) {
            item { ListHeader { Text(stringResource(R.string.app_name)) } }
            entries.forEach { entry ->
                item {
                    LauncherButton(
                        icon = entry.metric.icon,
                        tint = WearColors.metric(entry.metric),
                        label = stringResource(entry.metric.label),
                        secondary = entry.lastValue,
                        onClick = { onOpen(entry.metric) },
                    )
                }
            }
            item {
                LauncherButton(Icons.Rounded.History, WearColors.onSurfaceVariant, stringResource(R.string.history), null, onHistory)
            }
            item {
                LauncherButton(Icons.Rounded.Settings, WearColors.onSurfaceVariant, stringResource(R.string.settings), null, onSettings)
            }
        }
    }
}

@Composable
private fun LauncherButton(icon: ImageVector, tint: Color, label: String, secondary: String?, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.filledTonalButtonColors(),
        icon = { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp)) },
        label = { Text(label, maxLines = 1) },
        secondaryLabel = secondary?.let { { Text(it, maxLines = 1, color = WearColors.onSurfaceVariant) } },
    )
}
