package com.heartline.wear.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ProgressIndicatorDefaults
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.heartline.shared.model.Metric
import com.heartline.shared.model.Severity
import com.heartline.wear.R
import com.heartline.wear.ui.LauncherHeader
import com.heartline.wear.ui.components.agoText
import com.heartline.wear.ui.components.greeting
import com.heartline.wear.ui.components.icon
import com.heartline.wear.ui.components.label
import com.heartline.wear.ui.theme.WearColors

/** One metric row: its last value, when it was taken ([atMs]) and how it turned out. */
data class LauncherEntry(
    val metric: Metric,
    val lastValue: String?,
    val atMs: Long? = null,
    val severity: Severity? = null,
    val pinned: Boolean = false,
)

/**
 * The app's home: a greeting (with the user's name), today's check-ins, then the metrics the user
 * measures most (pinned ones first), History and Settings. Long-press a metric for more.
 */
@Composable
fun LauncherScreen(
    entries: List<LauncherEntry>,
    header: LauncherHeader? = null,
    onOpen: (Metric) -> Unit = {},
    onOptions: (Metric) -> Unit = {},
    onHistory: () -> Unit = {},
    onSettings: () -> Unit = {},
    nowMs: Long = System.currentTimeMillis(),
) {
    val state = rememberTransformingLazyColumnState()
    val quick = header?.next ?: entries.firstOrNull()?.metric
    ScreenScaffold(
        scrollState = state,
        edgeButton = {
            if (quick != null) {
                EdgeButton(onClick = { onOpen(quick) }, colors = ButtonDefaults.buttonColors(containerColor = WearColors.metric(quick), contentColor = Color.Black)) {
                    Text(stringResource(R.string.launcher_measure))
                }
            }
        },
    ) { contentPadding ->
        TransformingLazyColumn(state = state, contentPadding = contentPadding) {
            item {
                Text(
                    header?.let { greeting(it.part, it.name, it.birthday) } ?: stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp),
                )
            }
            header?.let { h ->
                item { TodayCard(h) { h.next?.let(onOpen) } }
            }
            entries.forEach { entry ->
                item { MetricRow(entry, nowMs, onClick = { onOpen(entry.metric) }, onLongClick = { onOptions(entry.metric) }) }
            }
            item {
                LauncherButton(Icons.Rounded.History, WearColors.onSurfaceVariant, stringResource(R.string.history), onHistory)
            }
            item {
                LauncherButton(Icons.Rounded.Settings, WearColors.onSurfaceVariant, stringResource(R.string.settings), onSettings)
            }
        }
    }
}

/** Today's check-ins: a ring of how many are done, and the next one to do. */
@Composable
private fun TodayCard(header: LauncherHeader, onNext: () -> Unit) {
    Card(onClick = onNext, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = WearColors.surface)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { header.done.toFloat() / header.total.coerceAtLeast(1) },
                    modifier = Modifier.size(40.dp),
                    strokeWidth = 4.dp,
                    colors = ProgressIndicatorDefaults.colors(indicatorColor = WearColors.primary, trackColor = WearColors.surfaceHigh),
                )
                if (header.next == null) {
                    Icon(Icons.Rounded.Check, contentDescription = null, tint = WearColors.primary, modifier = Modifier.size(20.dp))
                } else {
                    Text("${header.done}/${header.total}", style = MaterialTheme.typography.labelSmall)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.tile_today_done, header.done, header.total), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                Text(
                    header.next?.let { stringResource(R.string.tile_today_next, stringResource(it.label)) } ?: stringResource(R.string.tile_today_all_done),
                    style = MaterialTheme.typography.bodySmall,
                    color = header.next?.let { WearColors.metric(it) } ?: WearColors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun MetricRow(entry: LauncherEntry, nowMs: Long, onClick: () -> Unit, onLongClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    Button(
        onClick = onClick,
        onLongClick = {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onLongClick()
        },
        onLongClickLabel = stringResource(R.string.launcher_pin),
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.filledTonalButtonColors(),
        icon = { Icon(entry.metric.icon, contentDescription = null, tint = WearColors.metric(entry.metric), modifier = Modifier.size(24.dp)) },
        label = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(entry.metric.label), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (entry.pinned) {
                    Icon(Icons.Rounded.PushPin, contentDescription = stringResource(R.string.launcher_pinned), tint = WearColors.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp).size(12.dp))
                }
            }
        },
        secondaryLabel = entry.lastValue?.let { value ->
            {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    entry.severity?.takeIf { it != Severity.NEUTRAL }?.let {
                        Box(Modifier.padding(end = 4.dp).size(6.dp).clip(CircleShape).background(WearColors.severity(it)))
                    }
                    Text(
                        listOfNotNull(value, entry.atMs?.let { agoText(it, nowMs) }).joinToString(" · "),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = WearColors.onSurfaceVariant,
                    )
                }
            }
        },
    )
}

@Composable
private fun LauncherButton(icon: ImageVector, tint: Color, label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.filledTonalButtonColors(),
        icon = { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp)) },
        label = { Text(label, maxLines = 1) },
    )
}

/** Long-press options for a metric: pin it, measure it now, or see its history. */
@Composable
fun MetricOptionsScreen(metric: Metric, pinned: Boolean, onPin: () -> Unit, onMeasure: () -> Unit, onHistory: () -> Unit) {
    val state = rememberTransformingLazyColumnState()
    ScreenScaffold(scrollState = state) { contentPadding ->
        TransformingLazyColumn(state = state, contentPadding = contentPadding) {
            item {
                Text(
                    stringResource(metric.label),
                    style = MaterialTheme.typography.titleMedium,
                    color = WearColors.metric(metric),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                )
            }
            item { LauncherButton(metric.icon, WearColors.metric(metric), stringResource(R.string.launcher_measure_now), onMeasure) }
            item { LauncherButton(Icons.Rounded.PushPin, WearColors.onSurfaceVariant, stringResource(if (pinned) R.string.launcher_unpin else R.string.launcher_pin), onPin) }
            item { LauncherButton(Icons.Rounded.History, WearColors.onSurfaceVariant, stringResource(R.string.history), onHistory) }
        }
    }
}
