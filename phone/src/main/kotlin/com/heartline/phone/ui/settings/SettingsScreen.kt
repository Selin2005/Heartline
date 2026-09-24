package com.heartline.phone.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.heartline.phone.R
import com.heartline.phone.ui.components.CardRow
import com.heartline.phone.ui.components.IconBadge
import com.heartline.phone.ui.components.ReachabilityScaffold
import com.heartline.phone.ui.components.RoundedCard
import com.heartline.phone.ui.components.SectionHeader
import com.heartline.phone.ui.components.gutter
import com.heartline.phone.ui.theme.HeartlineTheme
import com.heartline.shared.hr.MonitorSettings

@Composable
fun SettingsScreen(
    monitor: MonitorSettings = MonitorSettings(),
    versionName: String = "0.1.0",
    onIrregularRhythm: (Boolean) -> Unit = {},
    onHeartRateAlerts: (Boolean) -> Unit = {},
    onDeleteAll: () -> Unit = {},
    onProfile: () -> Unit = {},
    onExport: () -> Unit = {},
    onAbout: () -> Unit = {},
) {
    val colors = HeartlineTheme.colors
    var reminders by remember { mutableStateOf(true) }
    ReachabilityScaffold(title = stringResource(R.string.tab_settings)) {
        item {
            RoundedCard(Modifier.gutter(), contentPadding = 0.dp) {
                CardRow(
                    stringResource(R.string.settings_profile),
                    subtitle = stringResource(R.string.settings_profile_summary),
                    leading = { IconBadge(Icons.Rounded.Person, colors.primary) },
                    showDivider = true,
                    onClick = onProfile,
                )
                CardRow(
                    stringResource(R.string.settings_device),
                    subtitle = stringResource(R.string.settings_device_summary),
                    leading = { IconBadge(Icons.Rounded.Watch, colors.body) },
                    onClick = {},
                )
            }
        }
        item { SectionHeader(stringResource(R.string.settings_notifications)) }
        item {
            RoundedCard(Modifier.gutter(), contentPadding = 0.dp) {
                CardRow(
                    stringResource(R.string.settings_irn),
                    subtitle = stringResource(R.string.settings_irn_summary),
                    leading = { IconBadge(Icons.Rounded.NotificationsActive, colors.ecg) },
                    trailing = { OneUiSwitch(monitor.irregularRhythmEnabled, onIrregularRhythm) },
                    showDivider = true,
                )
                CardRow(
                    stringResource(R.string.settings_hr_alerts),
                    subtitle = stringResource(R.string.settings_hr_alerts_summary, monitor.highBpm, monitor.lowBpm),
                    leading = { IconBadge(Icons.Rounded.NotificationsActive, colors.heartRate) },
                    trailing = { OneUiSwitch(monitor.heartRateAlertsEnabled, onHeartRateAlerts) },
                    showDivider = true,
                )
                CardRow(
                    stringResource(R.string.settings_reminders),
                    leading = { IconBadge(Icons.Rounded.NotificationsActive, colors.bp) },
                    trailing = { OneUiSwitch(reminders) { reminders = it } },
                )
            }
        }
        item { SectionHeader(stringResource(R.string.settings_data)) }
        item {
            RoundedCard(Modifier.gutter(), contentPadding = 0.dp) {
                CardRow(
                    stringResource(R.string.settings_units),
                    subtitle = stringResource(R.string.settings_units_summary),
                    leading = { IconBadge(Icons.Rounded.Straighten, colors.onSurfaceVariant) },
                    showDivider = true,
                    onClick = {},
                )
                CardRow(
                    stringResource(R.string.settings_export),
                    leading = { IconBadge(Icons.Rounded.Download, colors.onSurfaceVariant) },
                    showDivider = true,
                    onClick = onExport,
                )
                CardRow(stringResource(R.string.settings_delete_all), onClick = onDeleteAll)
            }
        }
        item { SectionHeader(stringResource(R.string.settings_about)) }
        item {
            RoundedCard(Modifier.gutter(), contentPadding = 0.dp) {
                CardRow(
                    stringResource(R.string.settings_disclaimer),
                    leading = { IconBadge(Icons.Rounded.Info, colors.onSurfaceVariant) },
                    showDivider = true,
                    onClick = onAbout,
                )
                CardRow(stringResource(R.string.settings_licenses), showDivider = true, onClick = onAbout)
                CardRow(stringResource(R.string.settings_version, versionName))
            }
        }
    }
}

@Composable
fun OneUiSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val colors = HeartlineTheme.colors
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = colors.primary,
            checkedBorderColor = colors.primary,
            uncheckedThumbColor = colors.onSurfaceVariant,
            uncheckedTrackColor = colors.surfaceVariant,
        ),
    )
}
