package com.heartline.wear.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeveloperMode
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.heartline.wear.R
import com.heartline.wear.ui.theme.WearColors

data class WatchSettingsUi(
    val irregularRhythm: Boolean,
    val heartRateAlerts: Boolean,
    val serviceVersion: String?,
    val trackers: List<String>,
    val appVersion: String,
)

@Composable
private fun Row(icon: ImageVector, label: String, secondary: String?, onClick: () -> Unit = {}) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.filledTonalButtonColors(),
        icon = { Icon(icon, contentDescription = null, tint = WearColors.primary, modifier = Modifier.size(22.dp)) },
        label = { Text(label, maxLines = 2) },
        secondaryLabel = secondary?.let { { Text(it, maxLines = 2, color = WearColors.onSurfaceVariant) } },
    )
}

@Composable
fun WatchSettingsScreen(state: WatchSettingsUi, onDevMode: () -> Unit = {}, onDiagnostics: () -> Unit = {}) {
    val list = rememberTransformingLazyColumnState()
    val on = stringResource(R.string.state_on)
    val off = stringResource(R.string.state_off)
    ScreenScaffold(scrollState = list) { padding ->
        TransformingLazyColumn(state = list, contentPadding = padding) {
            item { ListHeader { Text(stringResource(R.string.settings)) } }
            item { Row(Icons.Rounded.MonitorHeart, stringResource(R.string.settings_irn), if (state.irregularRhythm) on else off) }
            item { Row(Icons.Rounded.NotificationsActive, stringResource(R.string.settings_hr_alerts), if (state.heartRateAlerts) on else off) }
            item {
                Text(
                    stringResource(R.string.settings_change_on_phone),
                    style = MaterialTheme.typography.bodySmall,
                    color = WearColors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            item { Row(Icons.Rounded.DeveloperMode, stringResource(R.string.dev_mode_title), null, onDevMode) }
            item { Row(Icons.Rounded.Info, stringResource(R.string.diagnostics), state.serviceVersion ?: "–", onDiagnostics) }
            item { Row(Icons.Rounded.Info, stringResource(R.string.version), state.appVersion) }
        }
    }
}

@Composable
fun DiagnosticsScreen(state: WatchSettingsUi) {
    val list = rememberTransformingLazyColumnState()
    ScreenScaffold(scrollState = list) { padding ->
        TransformingLazyColumn(state = list, contentPadding = padding) {
            item { ListHeader { Text(stringResource(R.string.diagnostics)) } }
            item { Centered(stringResource(R.string.diag_service, state.serviceVersion ?: "–")) }
            item { Centered(stringResource(R.string.diag_sdk)) }
            item { ListHeader { Text(stringResource(R.string.diag_trackers), textAlign = TextAlign.Center) } }
            state.trackers.forEach { tracker ->
                item { Centered(tracker, small = true) }
            }
        }
    }
}

/** How to enable Developer mode in Health Sensor Service (needed until Heartline is a Samsung partner app). */
@Composable
fun DevModeGuideScreen() {
    val list = rememberTransformingLazyColumnState()
    val steps = listOf(R.string.dev_step_1, R.string.dev_step_2, R.string.dev_step_3, R.string.dev_step_4, R.string.dev_step_5)
    ScreenScaffold(scrollState = list) { padding ->
        TransformingLazyColumn(state = list, contentPadding = padding) {
            item { ListHeader { Text(stringResource(R.string.dev_mode_title)) } }
            steps.forEachIndexed { i, step ->
                item {
                    Centered("${i + 1}. ${stringResource(step)}")
                }
            }
            item {
                Centered(stringResource(R.string.dev_mode_note), small = true)
            }
        }
    }
}

/** Body text for round screens: centred, inset from the curved edge. */
@Composable
private fun Centered(text: String, small: Boolean = false) = Text(
    text,
    style = if (small) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
    color = if (small) WearColors.onSurfaceVariant else WearColors.onSurface,
    textAlign = TextAlign.Center,
    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 3.dp),
)
