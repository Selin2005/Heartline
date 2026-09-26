// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.heartline.phone.link.WatchLinkUi
import com.heartline.phone.ui.components.WatchLinkCard
import com.heartline.shared.sync.PeerProbe
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.heartline.phone.R
import com.heartline.phone.ui.components.IconBadge
import com.heartline.phone.ui.components.MetricValue
import com.heartline.phone.ui.components.MiniWave
import com.heartline.phone.ui.components.ReachabilityScaffold
import com.heartline.phone.ui.components.ResultBadge
import com.heartline.phone.ui.components.RoundedCard
import com.heartline.phone.ui.components.gutter
import com.heartline.phone.ui.components.icon
import com.heartline.phone.ui.components.label
import com.heartline.phone.ui.model.HomeState
import com.heartline.phone.ui.model.TileValue
import com.heartline.phone.ui.theme.HeartlineTheme
import com.heartline.shared.model.Metric

@Composable
fun HomeScreen(
    state: HomeState,
    onOpenEcg: () -> Unit = {},
    onOpenMetric: (Metric) -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    watchLink: WatchLinkUi? = null,
    onWatchRetry: () -> Unit = {},
    onOpenWatch: () -> Unit = {},
) {
    val latest = state.latestEcg
    // A live probe wins over the stored name, so the header never says "connected" above a "no watch" card.
    val connectedName = when {
        watchLink == null -> state.watchName
        watchLink.probe == PeerProbe.REACHABLE -> watchLink.name ?: stringResource(R.string.link_your_watch)
        else -> null
    }
    ReachabilityScaffold(
        title = homeGreeting(state),
        subtitle = connectedName?.let { stringResource(R.string.home_watch_connected, it) },
        listState = listState,
    ) {
        if (watchLink != null && watchLink.probe != PeerProbe.REACHABLE) {
            item { WatchLinkCard(watchLink, onRetry = onWatchRetry, onOpenWatch = onOpenWatch, modifier = Modifier.gutter()) }
        }
        item {
            MetricCard(
                Metric.ECG,
                caption = latest?.let { "${it.date} ${it.time}" } ?: stringResource(R.string.measure_on_watch),
                onClick = onOpenEcg,
                modifier = Modifier.gutter(),
            ) {
                if (latest == null) {
                    SmallCaption(stringResource(R.string.ecg_empty_title))
                } else {
                    ResultBadge(latest.result)
                    latest.averageBpm?.let {
                        Spacer(Modifier.height(4.dp))
                        SmallCaption(stringResource(R.string.ecg_bpm_value, it))
                    }
                    latest.samples?.let { samples ->
                        Spacer(Modifier.height(12.dp))
                        MiniWave(
                            samples.copyOfRange(0, minOf(samples.size, latest.sampleRateHz * 5)),
                            HeartlineTheme.colors.ecg,
                            Modifier.fillMaxWidth().height(44.dp),
                        )
                    }
                }
            }
        }
        item {
            TileCard(Metric.BLOOD_PRESSURE, state.tiles[Metric.BLOOD_PRESSURE], large = true, onOpenMetric = onOpenMetric, modifier = Modifier.gutter())
        }
        item {
            Row(Modifier.gutter().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TileCard(Metric.HEART_RATE, state.tiles[Metric.HEART_RATE], onOpenMetric = onOpenMetric, modifier = Modifier.weight(1f).fillMaxHeight())
                TileCard(Metric.SPO2, state.tiles[Metric.SPO2], onOpenMetric = onOpenMetric, modifier = Modifier.weight(1f).fillMaxHeight())
            }
        }
        item {
            Row(Modifier.gutter().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TileCard(
                    Metric.SKIN_TEMPERATURE,
                    state.tiles[Metric.SKIN_TEMPERATURE],
                    onOpenMetric = onOpenMetric,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                TileCard(
                    Metric.BODY_COMPOSITION,
                    state.tiles[Metric.BODY_COMPOSITION],
                    onOpenMetric = onOpenMetric,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
        item {
            TileCard(Metric.STRESS, state.tiles[Metric.STRESS], large = true, onOpenMetric = onOpenMetric, modifier = Modifier.gutter())
        }
        if (state.irregularRhythmNotifications) {
            item {
                RoundedCard(Modifier.gutter()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(Icons.Rounded.NotificationsActive, HeartlineTheme.colors.primary)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.home_irn_on),
                                style = MaterialTheme.typography.titleSmall,
                                color = HeartlineTheme.colors.onBackground,
                            )
                            SmallCaption(stringResource(R.string.home_irn_caption))
                        }
                    }
                }
            }
        }
    }
}

/** Metric tile showing the latest value, or an empty hint until a measurement exists. */
@Composable
private fun TileCard(
    metric: Metric,
    value: TileValue?,
    modifier: Modifier = Modifier,
    large: Boolean = false,
    onOpenMetric: (Metric) -> Unit,
) {
    MetricCard(
        metric,
        caption = value?.caption,
        compact = !large,
        onClick = { onOpenMetric(metric) },
        modifier = modifier,
    ) {
        if (value == null) {
            SmallCaption(stringResource(R.string.no_data_yet))
        } else {
            MetricValue(value.value, value.unit, large = large && metric != Metric.STRESS)
            value.detail?.let { SmallCaption(it) }
        }
    }
}

@Composable
private fun SmallCaption(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = HeartlineTheme.colors.onSurfaceVariant, maxLines = 2)
}

/** Samsung Health-style tile: coloured icon, metric name and time, then free content. */
@Composable
fun MetricCard(
    metric: Metric,
    caption: String?,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val colors = HeartlineTheme.colors
    RoundedCard(modifier, onClick = onClick, contentPadding = if (compact) 16.dp else 20.dp) {
        if (compact) {
            // Samsung Health-style tile: title top-left, icon top-right.
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    stringResource(metric.label),
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.onBackground,
                    maxLines = 2,
                    modifier = Modifier.weight(1f).padding(top = 4.dp),
                )
                Spacer(Modifier.width(8.dp))
                IconBadge(metric.icon, colors.metric(metric), size = 30)
            }
            caption?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(metric.icon, colors.metric(metric), size = 36)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(metric.label), style = MaterialTheme.typography.titleSmall, color = colors.onBackground)
                    caption?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
                }
            }
        }
        Spacer(Modifier.height(if (compact) 8.dp else 14.dp))
        content()
    }
}

/** "Good evening, Sara" (or just "Good evening" before a name is set); "Happy birthday, Sara!" on the day. */
@Composable
private fun homeGreeting(state: HomeState): String {
    val name = state.name
    if (state.birthday && name != null) return stringResource(R.string.home_greeting_birthday, name)
    val part = stringResource(
        when (state.dayPart) {
            com.heartline.shared.profile.DayPart.MORNING -> R.string.home_greeting_morning
            com.heartline.shared.profile.DayPart.AFTERNOON -> R.string.home_greeting_afternoon
            com.heartline.shared.profile.DayPart.EVENING -> R.string.home_greeting_evening
            com.heartline.shared.profile.DayPart.NIGHT -> R.string.home_greeting_night
        },
    )
    return if (name == null) part else stringResource(R.string.home_greeting_named, part, name)
}
