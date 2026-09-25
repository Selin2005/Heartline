package com.heartline.phone.widget

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.updateAll
import com.heartline.phone.data.BpRepository
import com.heartline.phone.data.HeartRepository
import com.heartline.phone.data.RecordRepository
import com.heartline.shared.model.RecordKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.reflect.KClass

/**
 * Keeps the home-screen widgets current: any new record, heart-rate minute or calibration
 * redraws them (debounced, so a sync burst is one update). The launcher also refreshes them every
 * 30 minutes (updatePeriodMillis) so "Today"/"Yesterday" labels roll over.
 */
class WidgetUpdater(
    private val context: Context,
    private val records: RecordRepository,
    private val heart: HeartRepository,
    private val bp: BpRepository,
) {
    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope) {
        combine(
            records.observe(RecordKind.ECG),
            combine(
                records.observe(RecordKind.BLOOD_PRESSURE),
                records.observe(RecordKind.SPO2),
                records.observe(RecordKind.SKIN_TEMPERATURE),
                records.observe(RecordKind.BODY_COMPOSITION),
                records.observe(RecordKind.STRESS),
            ) { a, b, c, d, e -> listOf(a.size, b.size, c.size, d.size, e.size) },
            heart.latestMinute,
            bp.calibration,
        ) { ecg, others, minute, calibration -> listOf(ecg.firstOrNull()?.entity?.id, others, minute?.minuteStartMs, calibration?.id) }
            .drop(1)
            .debounce(1_500)
            .onEach { updateAll() }
            .launchIn(scope)
        scope.launch { publishPreviews() }
    }

    suspend fun updateAll() {
        runCatching {
            DashboardWidget().updateAll(context)
            HeartRateWidget().updateAll(context)
            EcgWidget().updateAll(context)
            BpWidget().updateAll(context)
            StressWidget().updateAll(context)
            QuickMeasureWidget().updateAll(context)
            HeartDayWidget().updateAll(context)
        }.onFailure { Log.w(HeartlineWidget.TAG, "widget update failed", it) }
    }

    /** Android 15+ widget picker: live previews drawn by the widgets themselves (sample data). */
    private suspend fun publishPreviews() {
        if (Build.VERSION.SDK_INT < 35) return
        val manager = GlanceAppWidgetManager(context)
        RECEIVERS.forEach { receiver ->
            runCatching { manager.setWidgetPreviews(receiver) }.onFailure { Log.w(HeartlineWidget.TAG, "preview for ${receiver.simpleName} failed", it) }
        }
    }

    companion object {
        val RECEIVERS: List<KClass<out GlanceAppWidgetReceiver>> = listOf(
            DashboardWidgetReceiver::class,
            HeartRateWidgetReceiver::class,
            EcgWidgetReceiver::class,
            BpWidgetReceiver::class,
            StressWidgetReceiver::class,
            QuickMeasureWidgetReceiver::class,
            HeartDayWidgetReceiver::class,
        )
    }
}
