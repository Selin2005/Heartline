package com.heartline.phone.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.lifecycle.lifecycleScope
import com.heartline.phone.R
import com.heartline.phone.ui.components.Chip
import com.heartline.phone.ui.components.PillButton
import com.heartline.phone.ui.components.RoundedCard
import com.heartline.phone.ui.theme.HeartlineTheme
import com.heartline.shared.design.Palette
import kotlinx.coroutines.launch

/**
 * Style screen shown when a widget is added (optional) or reconfigured: background transparency
 * and colours, as on Samsung's own widgets.
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
        enableEdgeToEdge()
        lifecycleScope.launch {
            val initial = WidgetPrefs.load(this@WidgetConfigActivity, appWidgetId)
            setContent {
                HeartlineTheme {
                    val scope = rememberCoroutineScope()
                    WidgetStyleScreen(initial) { style ->
                        scope.launch {
                            WidgetPrefs.save(this@WidgetConfigActivity, appWidgetId, style)
                            refresh()
                            finish()
                        }
                    }
                }
            }
        }
    }

    private suspend fun refresh() {
        val manager = GlanceAppWidgetManager(this)
        val glanceId = runCatching { manager.getGlanceIdBy(appWidgetId) }.getOrNull() ?: return
        val provider = AppWidgetManager.getInstance(this).getAppWidgetInfo(appWidgetId)?.provider?.className
        widgetFor(provider)?.update(this, glanceId)
    }

    private fun widgetFor(receiver: String?): GlanceAppWidget? = when (receiver) {
        DashboardWidgetReceiver::class.java.name -> DashboardWidget()
        HeartRateWidgetReceiver::class.java.name -> HeartRateWidget()
        EcgWidgetReceiver::class.java.name -> EcgWidget()
        BpWidgetReceiver::class.java.name -> BpWidget()
        StressWidgetReceiver::class.java.name -> StressWidget()
        QuickMeasureWidgetReceiver::class.java.name -> QuickMeasureWidget()
        HeartDayWidgetReceiver::class.java.name -> HeartDayWidget()
        else -> null
    }
}

@Composable
fun WidgetStyleScreen(initial: WidgetStyle, onDone: (WidgetStyle) -> Unit) {
    val colors = HeartlineTheme.colors
    var opacity by remember { mutableStateOf(initial.opacity) }
    var theme by remember { mutableStateOf(initial.theme) }
    Column(
        Modifier.fillMaxSize().background(colors.background).safeDrawingPadding().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.widget_style_title), style = MaterialTheme.typography.headlineSmall, color = colors.onBackground)
        StylePreview(WidgetStyle(opacity, theme))
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(stringResource(R.string.widget_theme_system), theme == WidgetTheme.SYSTEM) { theme = WidgetTheme.SYSTEM }
                Chip(stringResource(R.string.widget_theme_light), theme == WidgetTheme.LIGHT) { theme = WidgetTheme.LIGHT }
                Chip(stringResource(R.string.widget_theme_dark), theme == WidgetTheme.DARK) { theme = WidgetTheme.DARK }
            }
        }
        Spacer(Modifier.weight(1f))
        PillButton(stringResource(R.string.action_done), onClick = { onDone(WidgetStyle(opacity, theme)) })
    }
}

/** A small widget mock on a wallpaper-like gradient, so transparency is visible. */
@Composable
private fun StylePreview(style: WidgetStyle) {
    val dark = when (style.theme) {
        WidgetTheme.SYSTEM -> HeartlineTheme.colors.background == Color(Palette.Dark.BACKGROUND)
        WidgetTheme.LIGHT -> false
        WidgetTheme.DARK -> true
    }
    val surface = Color(if (dark) Palette.Dark.SURFACE else Palette.Light.SURFACE).copy(alpha = style.opacity / 100f)
    val text = Color(if (dark) Palette.Dark.ON_BACKGROUND else Palette.Light.ON_BACKGROUND)
    val sub = Color(if (dark) Palette.Dark.ON_SURFACE_VARIANT else Palette.Light.ON_SURFACE_VARIANT)
    Box(
        Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF6D8BFF), Color(0xFFB36BFF), Color(0xFFFF8A7A)))),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.size(150.dp).clip(RoundedCornerShape(26.dp)).background(surface).padding(14.dp)) {
            Text(stringResource(R.string.metric_hr), color = text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.Bottom) {
                Text("72", color = text, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.unit_bpm), color = sub, fontSize = 14.sp, modifier = Modifier.padding(start = 3.dp, bottom = 6.dp))
            }
            Text(stringResource(R.string.widget_resting, 56), color = sub, fontSize = 11.sp)
        }
    }
}
