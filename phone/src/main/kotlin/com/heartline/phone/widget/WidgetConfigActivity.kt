// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.lifecycle.lifecycleScope
import com.heartline.phone.R
import com.heartline.phone.ui.components.Chip
import com.heartline.phone.ui.components.PillButton
import com.heartline.phone.ui.components.RoundedCard
import com.heartline.phone.ui.theme.HeartlineTheme
import com.heartline.shared.model.Metric
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatform

/** Which widget is being set up, what it can be set to, and how big its preview is. */
internal data class WidgetKind(val widget: HeartlineWidget, val previewSize: DpSize, val pick: MetricPick? = null) {
    enum class MetricPick { SHOW, MEASURE }

    companion object {
        private val measurable = listOf(Metric.ECG, Metric.BLOOD_PRESSURE, Metric.SPO2, Metric.STRESS, Metric.SKIN_TEMPERATURE, Metric.BODY_COMPOSITION, Metric.HEART_RATE)

        fun choices(pick: MetricPick): List<Metric> = if (pick == MetricPick.SHOW) Metric.entries.sortedBy { measurable.indexOf(it).let { i -> if (i < 0) 99 else i } } else measurable

        fun of(receiver: String?): WidgetKind? = when (receiver) {
            DashboardWidgetReceiver::class.java.name -> WidgetKind(DashboardWidget(), DpSize(300.dp, 150.dp))
            HeartRateWidgetReceiver::class.java.name -> WidgetKind(HeartRateWidget(), DpSize(160.dp, 160.dp))
            EcgWidgetReceiver::class.java.name -> WidgetKind(EcgWidget(), DpSize(160.dp, 160.dp))
            BpWidgetReceiver::class.java.name -> WidgetKind(BpWidget(), DpSize(160.dp, 160.dp))
            StressWidgetReceiver::class.java.name -> WidgetKind(StressWidget(), DpSize(160.dp, 160.dp))
            QuickMeasureWidgetReceiver::class.java.name -> WidgetKind(QuickMeasureWidget(), DpSize(300.dp, 64.dp))
            HeartDayWidgetReceiver::class.java.name -> WidgetKind(HeartDayWidget(), DpSize(300.dp, 160.dp))
            MetricTileSmallReceiver::class.java.name -> WidgetKind(MetricTileWidget(), DpSize(72.dp, 72.dp), MetricPick.SHOW)
            MetricTileSlimReceiver::class.java.name -> WidgetKind(MetricTileWidget(), DpSize(160.dp, 64.dp), MetricPick.SHOW)
            MetricTileReceiver::class.java.name -> WidgetKind(MetricTileWidget(), DpSize(160.dp, 160.dp), MetricPick.SHOW)
            MeasureButtonReceiver::class.java.name -> WidgetKind(MeasureButtonWidget(), DpSize(72.dp, 72.dp), MetricPick.MEASURE)
            else -> null
        }
    }
}

/**
 * Set-up screen shown when a widget is added (the health tiles ask what to show) or reconfigured:
 * the metric, background transparency and colours, with a live preview of the real widget, as on
 * Samsung's own widgets.
 */
class WidgetConfigActivity : ComponentActivity() {
    private val appWidgetId by lazy {
        intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Backing out keeps the widget with its current style.
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        val provider = AppWidgetManager.getInstance(this).getAppWidgetInfo(appWidgetId)?.provider?.className
        val kind = WidgetKind.of(provider) ?: WidgetKind(MetricTileWidget(), DpSize(160.dp, 160.dp))
        enableEdgeToEdge()
        lifecycleScope.launch {
            val saved = WidgetPrefs.load(this@WidgetConfigActivity, appWidgetId)
            val initial = saved.copy(metric = saved.metric ?: kind.pick?.let { if (it == WidgetKind.MetricPick.MEASURE) Metric.ECG else Metric.HEART_RATE })
            val snapshot = runCatching { KoinPlatform.getKoin().get<WidgetDataSource>().load() }.getOrNull()?.takeIf { it != WidgetSnapshot() } ?: WidgetSamples.snapshot
            setContent {
                HeartlineTheme {
                    val scope = rememberCoroutineScope()
                    WidgetStyleScreen(initial, kind, snapshot) { style ->
                        scope.launch {
                            WidgetPrefs.save(this@WidgetConfigActivity, appWidgetId, style)
                            refresh(kind)
                            finish()
                        }
                    }
                }
            }
        }
    }

    private suspend fun refresh(kind: WidgetKind) {
        val glanceId = runCatching { androidx.glance.appwidget.GlanceAppWidgetManager(this).getGlanceIdBy(appWidgetId) }.getOrNull() ?: return
        kind.widget.update(this, glanceId)
    }
}

@Composable
internal fun WidgetStyleScreen(initial: WidgetStyle, kind: WidgetKind, snapshot: WidgetSnapshot, onDone: (WidgetStyle) -> Unit) {
    val colors = HeartlineTheme.colors
    var opacity by remember { mutableStateOf(initial.opacity) }
    var theme by remember { mutableStateOf(initial.theme) }
    var metric by remember { mutableStateOf(initial.metric) }
    val style = WidgetStyle(opacity, theme, metric)
    Column(Modifier.fillMaxSize().background(colors.background).safeDrawingPadding()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.widget_style_title), style = MaterialTheme.typography.headlineSmall, color = colors.onBackground)
            LivePreview(kind, snapshot, style)
            kind.pick?.let { pick ->
                RoundedCard(Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(if (pick == WidgetKind.MetricPick.SHOW) R.string.widget_style_metric else R.string.widget_style_measure_metric),
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.onBackground,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WidgetKind.choices(pick).forEach { m ->
                            Chip(stringResource(m.title), metric == m) { metric = m }
                        }
                    }
                }
            }
            RoundedCard(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.widget_style_opacity), style = MaterialTheme.typography.titleSmall, color = colors.onBackground)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Slider(
                        value = (100 - opacity).toFloat(),
                        onValueChange = { opacity = 100 - it.toInt() },
                        valueRange = 0f..100f,
                        steps = 9,
                        colors = SliderDefaults.colors(thumbColor = colors.primary, activeTrackColor = colors.primary),
                        modifier = Modifier.weight(1f),
                    )
                    Text("${100 - opacity}%", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp))
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.widget_style_theme), style = MaterialTheme.typography.titleSmall, color = colors.onBackground)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(stringResource(R.string.widget_theme_system), theme == WidgetTheme.SYSTEM) { theme = WidgetTheme.SYSTEM }
                    if (Build.VERSION.SDK_INT >= 31) {
                        Chip(stringResource(R.string.widget_theme_wallpaper), theme == WidgetTheme.WALLPAPER) { theme = WidgetTheme.WALLPAPER }
                    }
                    Chip(stringResource(R.string.widget_theme_light), theme == WidgetTheme.LIGHT) { theme = WidgetTheme.LIGHT }
                    Chip(stringResource(R.string.widget_theme_dark), theme == WidgetTheme.DARK) { theme = WidgetTheme.DARK }
                }
            }
        }
        PillButton(stringResource(R.string.action_done), onClick = { onDone(style) }, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
    }
}

/**
 * The real widget, drawn by Glance exactly as the launcher will show it, on a wallpaper-like
 * gradient so transparency is visible.
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
@Composable
private fun LivePreview(kind: WidgetKind, snapshot: WidgetSnapshot, style: WidgetStyle) {
    val context = LocalContext.current
    var views by remember { mutableStateOf<android.widget.RemoteViews?>(null) }
    LaunchedEffect(style) {
        val model = WidgetModel(snapshot, style, wallpaper = WallpaperTones.of(context))
        views = runCatching { GlanceRemoteViews().compose(context, kind.previewSize) { kind.widget.Content(model) }.remoteViews }.getOrNull()
    }
    Box(
        Modifier.fillMaxWidth().height(kind.previewSize.height + 64.dp).clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF6D8BFF), Color(0xFFB36BFF), Color(0xFFFF8A7A)))),
        contentAlignment = Alignment.Center,
    ) {
        views?.let { rv ->
            AndroidView(
                factory = { FrameLayout(it) },
                update = { frame ->
                    frame.removeAllViews()
                    runCatching { frame.addView(rv.apply(frame.context, frame)) }
                },
                modifier = Modifier.size(kind.previewSize.width, kind.previewSize.height),
            )
        }
    }
}
