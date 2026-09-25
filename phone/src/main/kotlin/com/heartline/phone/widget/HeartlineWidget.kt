package com.heartline.phone.widget

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import com.heartline.shared.hr.RangeBucket
import com.heartline.shared.bp.BpCategory
import com.heartline.shared.model.EcgResult
import com.heartline.shared.profile.StressLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatform

/** What one widget draws: the data, the user's style, and the current half-hour of the day. */
data class WidgetModel(val snapshot: WidgetSnapshot, val style: WidgetStyle = WidgetStyle(), val nowSlot: Int = 28) {
    val colors = WidgetColors(style)
}

/**
 * Shared plumbing: loads the snapshot and this widget's style, then draws [Content] at the size
 * the launcher gave it (responsive sizes). The widget picker preview uses sample data.
 */
abstract class HeartlineWidget : GlanceAppWidget() {
    protected abstract val sizes: Set<DpSize>

    override val sizeMode: SizeMode get() = SizeMode.Responsive(sizes)

    @Composable
    abstract fun Content(model: WidgetModel)

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val source = runCatching { KoinPlatform.getKoin().get<WidgetDataSource>() }.getOrNull()
        val snapshot = source?.let { runCatching { it.load() }.onFailure { e -> Log.w(TAG, "widget data failed", e) }.getOrNull() } ?: WidgetSnapshot()
        val appWidgetId = runCatching { GlanceAppWidgetManager(context).getAppWidgetId(id) }.getOrNull()
        val style = appWidgetId?.let { WidgetPrefs.load(context, it) } ?: WidgetStyle()
        val model = WidgetModel(snapshot, style, source?.nowSlot() ?: 28)
        provideContent { Content(model) }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent { Content(WidgetModel(WidgetSamples.snapshot)) }
    }

    companion object {
        const val TAG = "Heartline/Widget"
    }
}

/** Receivers only differ by their widget; removing a widget also drops its style. */
abstract class HeartlineWidgetReceiver : GlanceAppWidgetReceiver() {
    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                WidgetPrefs.delete(context, appWidgetIds)
            } finally {
                pending.finish()
            }
        }
    }
}

class DashboardWidgetReceiver : HeartlineWidgetReceiver() {
    override val glanceAppWidget = DashboardWidget()
}

class HeartRateWidgetReceiver : HeartlineWidgetReceiver() {
    override val glanceAppWidget = HeartRateWidget()
}

class EcgWidgetReceiver : HeartlineWidgetReceiver() {
    override val glanceAppWidget = EcgWidget()
}

class BpWidgetReceiver : HeartlineWidgetReceiver() {
    override val glanceAppWidget = BpWidget()
}

class StressWidgetReceiver : HeartlineWidgetReceiver() {
    override val glanceAppWidget = StressWidget()
}

class QuickMeasureWidgetReceiver : HeartlineWidgetReceiver() {
    override val glanceAppWidget = QuickMeasureWidget()
}

class HeartDayWidgetReceiver : HeartlineWidgetReceiver() {
    override val glanceAppWidget = HeartDayWidget()
}

/** Realistic values for the widget picker and screenshots. */
object WidgetSamples {
    private val day = (0 until 29).map { i ->
        val base = when {
            i < 14 -> 54 + (i % 3)
            i in 17..19 -> 88 + (i % 2) * 14
            else -> 66 + (i % 4) * 3
        }
        RangeBucket(i, base - 4, base + 9 + (i % 5) * 2, base + 2)
    }

    val snapshot = WidgetSnapshot(
        heartRate = WidgetSnapshot.HeartRate(72, "Today 14:32", 56, 50, 121, day),
        ecg = WidgetSnapshot.Ecg(EcgResult.SINUS_RHYTHM, "Today 09:41", 68),
        bp = WidgetSnapshot.Bp(118, 76, BpCategory.NORMAL, "Today 08:05"),
        calibration = WidgetSnapshot.Calibration.Valid(21),
        spo2 = WidgetSnapshot.Reading("97", "%", "Today 07:12"),
        temperature = WidgetSnapshot.Reading("+0.2", "°C", "Today 07:12"),
        body = WidgetSnapshot.Reading("21.4", "%", "Yesterday 21:10"),
        stress = WidgetSnapshot.Stress(38, StressLevel.MEDIUM, 42, "Today 13:05"),
    )
}
