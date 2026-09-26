package com.heartline.phone.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Dashboard
import com.heartline.phone.qs.QuickTilePrefs
import com.heartline.phone.widget.title
import com.heartline.shared.model.Metric
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material3.OutlinedTextField
import com.heartline.phone.data.SettingsRepository
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.ShowChart
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.ui.Alignment
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
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
import com.heartline.shared.profile.ReportName
import com.heartline.phone.ui.components.CardRow
import com.heartline.phone.ui.components.IconBadge
import com.heartline.phone.ui.components.ReachabilityScaffold
import com.heartline.phone.ui.components.RoundedCard
import com.heartline.phone.ui.components.SectionHeader
import com.heartline.phone.ui.components.gutter
import com.heartline.phone.ui.theme.HeartlineTheme
import com.heartline.shared.hr.MonitorSettings

/** Everything the settings screen can change; each maps to one field of [MonitorSettings]. */
sealed interface SettingChange {
    data class IrregularRhythm(val on: Boolean) : SettingChange
    data class HeartRateAlerts(val on: Boolean) : SettingChange
    data class HighBpm(val bpm: Int) : SettingChange
    data class LowBpm(val bpm: Int) : SettingChange
    data class BackgroundHeartRate(val on: Boolean) : SettingChange
    data class IrnInterval(val minutes: Int) : SettingChange
    data class CalibrationReminder(val on: Boolean) : SettingChange
    data class DailyReminder(val on: Boolean) : SettingChange
    data class DailyReminderTime(val minuteOfDay: Int) : SettingChange
    data class Haptics(val on: Boolean) : SettingChange
    data class LiveWave(val on: Boolean) : SettingChange
    data class Fahrenheit(val on: Boolean) : SettingChange

    fun applyTo(s: MonitorSettings): MonitorSettings = when (this) {
        is IrregularRhythm -> s.copy(irregularRhythmEnabled = on)
        is HeartRateAlerts -> s.copy(heartRateAlertsEnabled = on)
        is HighBpm -> s.copy(highBpm = bpm)
        is LowBpm -> s.copy(lowBpm = bpm)
        is BackgroundHeartRate -> s.copy(backgroundHeartRate = on)
        is IrnInterval -> s.copy(irnIntervalMinutes = minutes)
        is CalibrationReminder -> s.copy(calibrationReminder = on)
        is DailyReminder -> s.copy(dailyReminder = on)
        is DailyReminderTime -> s.copy(dailyReminderMinute = minuteOfDay)
        is Haptics -> s.copy(haptics = on)
        is LiveWave -> s.copy(liveWave = on)
        is Fahrenheit -> s.copy(temperatureFahrenheit = on)
    }
}

private sealed interface Picker {
    data object Interval : Picker
    data object High : Picker
    data object Low : Picker
    data object Time : Picker
    data object Temperature : Picker
    data object Name : Picker
    data object QuickTile : Picker
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    monitor: MonitorSettings = MonitorSettings(),
    versionName: String = "0.1.0",
    watchConnected: Boolean? = null,
    onChange: (SettingChange) -> Unit = {},
    onDeleteAll: () -> Unit = {},
    onProfile: () -> Unit = {},
    onWatch: () -> Unit = {},
    onExport: () -> Unit = {},
    onAbout: () -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    sharing: SettingsRepository.SharingPrefs = SettingsRepository.SharingPrefs(),
    onAiPrompt: (String?) -> Unit = {},
    onAiAttachPdf: (Boolean) -> Unit = {},
    onReportName: (ReportName) -> Unit = {},
    quickTileMetric: Metric = Metric.SPO2,
    onQuickTileMetric: (Metric) -> Unit = {},
    onAddQuickTiles: () -> Unit = {},
) {
    var editingPrompt by remember { mutableStateOf(false) }
    val colors = HeartlineTheme.colors
    var picker by remember { mutableStateOf<Picker?>(null) }
    val on = stringResource(R.string.state_on)
    val off = stringResource(R.string.state_off)
    ReachabilityScaffold(title = stringResource(R.string.tab_settings), listState = listState) {
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
                    subtitle = stringResource(
                        when (watchConnected) {
                            true -> R.string.settings_watch_connected
                            false -> R.string.settings_watch_not_connected
                            null -> R.string.link_checking_title
                        },
                    ),
                    leading = { IconBadge(Icons.Rounded.Watch, colors.body) },
                    onClick = onWatch,
                )
            }
        }

        item { SectionHeader(stringResource(R.string.settings_monitoring)) }
        item {
            RoundedCard(Modifier.gutter(), contentPadding = 0.dp) {
                CardRow(
                    stringResource(R.string.settings_background_hr),
                    subtitle = stringResource(R.string.settings_background_hr_summary),
                    leading = { IconBadge(Icons.Rounded.MonitorHeart, colors.heartRate) },
                    trailing = { OneUiSwitch(monitor.backgroundHeartRate) { onChange(SettingChange.BackgroundHeartRate(it)) } },
                    showDivider = true,
                )
                CardRow(
                    stringResource(R.string.settings_irn),
                    subtitle = stringResource(R.string.settings_irn_summary),
                    leading = { IconBadge(Icons.Rounded.NotificationsActive, colors.ecg) },
                    trailing = { OneUiSwitch(monitor.irregularRhythmEnabled) { onChange(SettingChange.IrregularRhythm(it)) } },
                    showDivider = true,
                )
                if (monitor.irregularRhythmEnabled) {
                    CardRow(
                        stringResource(R.string.settings_irn_interval),
                        subtitle = stringResource(R.string.settings_every_minutes, monitor.irnIntervalMinutes),
                        dividerStart = 76.dp,
                        leading = { Spacer(Modifier.size(40.dp)) },
                        showDivider = true,
                        onClick = { picker = Picker.Interval },
                    )
                }
                CardRow(
                    stringResource(R.string.settings_hr_alerts),
                    subtitle = stringResource(R.string.settings_hr_alerts_summary, monitor.highBpm, monitor.lowBpm),
                    leading = { IconBadge(Icons.Rounded.NotificationsActive, colors.heartRate) },
                    trailing = { OneUiSwitch(monitor.heartRateAlertsEnabled) { onChange(SettingChange.HeartRateAlerts(it)) } },
                    showDivider = monitor.heartRateAlertsEnabled,
                )
                if (monitor.heartRateAlertsEnabled) {
                    CardRow(
                        stringResource(R.string.settings_high_threshold),
                        subtitle = stringResource(R.string.settings_bpm, monitor.highBpm),
                        leading = { Spacer(Modifier.size(40.dp)) },
                        showDivider = true,
                        onClick = { picker = Picker.High },
                    )
                    CardRow(
                        stringResource(R.string.settings_low_threshold),
                        subtitle = stringResource(R.string.settings_bpm, monitor.lowBpm),
                        leading = { Spacer(Modifier.size(40.dp)) },
                        onClick = { picker = Picker.Low },
                    )
                }
            }
        }
        item {
            Text(
                stringResource(R.string.settings_sync_note),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 28.dp),
            )
        }

        item { SectionHeader(stringResource(R.string.settings_watch_section)) }
        item {
            RoundedCard(Modifier.gutter(), contentPadding = 0.dp) {
                CardRow(
                    stringResource(R.string.settings_haptics),
                    leading = { IconBadge(Icons.Rounded.Vibration, colors.primary) },
                    trailing = { OneUiSwitch(monitor.haptics) { onChange(SettingChange.Haptics(it)) } },
                    showDivider = true,
                )
                CardRow(
                    stringResource(R.string.settings_live_wave),
                    subtitle = stringResource(R.string.settings_live_wave_summary),
                    leading = { IconBadge(Icons.Rounded.ShowChart, colors.ecg) },
                    trailing = { OneUiSwitch(monitor.liveWave) { onChange(SettingChange.LiveWave(it)) } },
                )
            }
        }

        item { SectionHeader(stringResource(R.string.settings_quick_tiles)) }
        item {
            RoundedCard(Modifier.gutter(), contentPadding = 0.dp) {
                CardRow(
                    stringResource(R.string.settings_quick_tiles),
                    subtitle = stringResource(R.string.settings_quick_tiles_text),
                    leading = { IconBadge(Icons.Rounded.Dashboard, colors.primary) },
                    trailing = {
                        TextButton(onClick = onAddQuickTiles) { Text(stringResource(R.string.settings_quick_tiles_add)) }
                    },
                    showDivider = true,
                )
                CardRow(
                    stringResource(R.string.settings_quick_tile_measure),
                    subtitle = stringResource(quickTileMetric.title),
                    leading = { Spacer(Modifier.size(40.dp)) },
                    onClick = { picker = Picker.QuickTile },
                )
            }
        }

        item { SectionHeader(stringResource(R.string.settings_reminders_section)) }
        item {
            RoundedCard(Modifier.gutter(), contentPadding = 0.dp) {
                CardRow(
                    stringResource(R.string.settings_reminders),
                    subtitle = stringResource(R.string.settings_calibration_reminder_summary),
                    leading = { IconBadge(Icons.Rounded.Speed, colors.bp) },
                    trailing = { OneUiSwitch(monitor.calibrationReminder) { onChange(SettingChange.CalibrationReminder(it)) } },
                    showDivider = true,
                )
                CardRow(
                    stringResource(R.string.settings_daily_reminder),
                    subtitle = if (monitor.dailyReminder) formatMinute(monitor.dailyReminderMinute) else off,
                    leading = { IconBadge(Icons.Rounded.Alarm, colors.primary) },
                    trailing = { OneUiSwitch(monitor.dailyReminder) { onChange(SettingChange.DailyReminder(it)) } },
                    onClick = { if (monitor.dailyReminder) picker = Picker.Time },
                )
            }
        }

        item { SectionHeader(stringResource(R.string.settings_sharing)) }
        item {
            RoundedCard(Modifier.gutter(), contentPadding = 0.dp) {
                CardRow(
                    stringResource(R.string.settings_report_name),
                    subtitle = stringResource(sharing.reportName.label),
                    leading = { IconBadge(Icons.Rounded.Badge, colors.primary) },
                    showDivider = true,
                    onClick = { picker = Picker.Name },
                )
                CardRow(
                    stringResource(R.string.settings_ai_prompt),
                    subtitle = sharing.prompt ?: stringResource(R.string.settings_ai_prompt_default),
                    leading = { IconBadge(Icons.Rounded.AutoAwesome, colors.primary) },
                    showDivider = true,
                    onClick = { editingPrompt = true },
                )
                CardRow(
                    stringResource(R.string.settings_ai_attach_pdf),
                    leading = { IconBadge(Icons.Rounded.PictureAsPdf, colors.onSurfaceVariant) },
                    trailing = { OneUiSwitch(sharing.attachPdf, onAiAttachPdf) },
                )
            }
        }
        item { SectionHeader(stringResource(R.string.settings_data)) }
        item {
            RoundedCard(Modifier.gutter(), contentPadding = 0.dp) {
                CardRow(
                    stringResource(R.string.settings_temperature_unit),
                    subtitle = stringResource(if (monitor.temperatureFahrenheit) R.string.unit_fahrenheit else R.string.unit_celsius),
                    leading = { IconBadge(Icons.Rounded.Straighten, colors.onSurfaceVariant) },
                    showDivider = true,
                    onClick = { picker = Picker.Temperature },
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

    if (editingPrompt) {
        val default = stringResource(R.string.ai_default_prompt)
        var text by remember { mutableStateOf(sharing.prompt ?: default) }
        AlertDialog(
            onDismissRequest = { editingPrompt = false },
            title = { Text(stringResource(R.string.settings_ai_prompt)) },
            text = { OutlinedTextField(text, { text = it.take(500) }, minLines = 3, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(onClick = {
                    onAiPrompt(text.takeIf { it.isNotBlank() && it != default })
                    editingPrompt = false
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { TextButton(onClick = { editingPrompt = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    when (picker) {
        Picker.Interval -> ChoiceDialog(
            stringResource(R.string.settings_irn_interval),
            MonitorSettings.IRN_INTERVALS.map { stringResource(R.string.settings_every_minutes, it) to it },
            monitor.irnIntervalMinutes,
            onDismiss = { picker = null },
        ) { onChange(SettingChange.IrnInterval(it)) }
        Picker.High -> ChoiceDialog(
            stringResource(R.string.settings_high_threshold),
            MonitorSettings.HIGH_BPM_RANGE.step(5).map { stringResource(R.string.settings_bpm, it) to it },
            monitor.highBpm,
            onDismiss = { picker = null },
        ) { onChange(SettingChange.HighBpm(it)) }
        Picker.Low -> ChoiceDialog(
            stringResource(R.string.settings_low_threshold),
            MonitorSettings.LOW_BPM_RANGE.step(5).map { stringResource(R.string.settings_bpm, it) to it },
            monitor.lowBpm,
            onDismiss = { picker = null },
        ) { onChange(SettingChange.LowBpm(it)) }
        Picker.Temperature -> ChoiceDialog(
            stringResource(R.string.settings_temperature_unit),
            listOf(stringResource(R.string.unit_celsius) to false, stringResource(R.string.unit_fahrenheit) to true),
            monitor.temperatureFahrenheit,
            onDismiss = { picker = null },
        ) { onChange(SettingChange.Fahrenheit(it)) }
        Picker.QuickTile -> ChoiceDialog(
            stringResource(R.string.settings_quick_tile_measure),
            QuickTilePrefs.choices.map { stringResource(it.title) to it },
            quickTileMetric,
            onDismiss = { picker = null },
        ) { onQuickTileMetric(it) }
        Picker.Name -> ChoiceDialog(
            stringResource(R.string.settings_report_name),
            ReportName.entries.map { stringResource(it.label) to it },
            sharing.reportName,
            onDismiss = { picker = null },
        ) { onReportName(it) }
        Picker.Time -> {
            val state = rememberTimePickerState(monitor.dailyReminderMinute / 60, monitor.dailyReminderMinute % 60)
            AlertDialog(
                onDismissRequest = { picker = null },
                title = { Text(stringResource(R.string.settings_daily_reminder)) },
                text = { TimePicker(state) },
                confirmButton = {
                    TextButton(onClick = {
                        onChange(SettingChange.DailyReminderTime(state.hour * 60 + state.minute))
                        picker = null
                    }) { Text(stringResource(R.string.action_done)) }
                },
                dismissButton = { TextButton(onClick = { picker = null }) { Text(stringResource(R.string.action_cancel)) } },
            )
        }
        null -> Unit
    }
}

private val ReportName.label: Int get() = when (this) {
    ReportName.PREFERRED_NAME -> R.string.report_name_preferred
    ReportName.FULL_NAME -> R.string.report_name_full
    ReportName.NONE -> R.string.report_name_none
}

private fun formatMinute(minute: Int): String =
    LocalTime.of(minute / 60, minute % 60).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

/** Single-choice list in a dialog (One UI style radio list). */
@Composable
private fun <T> ChoiceDialog(title: String, options: List<Pair<String, T>>, selected: T, onDismiss: () -> Unit, onSelect: (T) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { (label, value) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelect(value)
                                onDismiss()
                            }
                            .padding(vertical = 4.dp),
                    ) {
                        RadioButton(selected = value == selected, onClick = {
                            onSelect(value)
                            onDismiss()
                        })
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
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
