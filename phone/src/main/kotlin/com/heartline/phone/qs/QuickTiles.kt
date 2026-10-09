// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.qs

import com.heartline.datalayer.diag.HLog
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.heartline.phone.R
import com.heartline.phone.link.OpenResult
import com.heartline.phone.link.WatchOpener
import com.heartline.phone.widget.WidgetDataSource
import com.heartline.phone.widget.WidgetMetrics
import com.heartline.phone.widget.WidgetRoutes
import com.heartline.phone.widget.WidgetSnapshot
import com.heartline.phone.widget.appIntent
import com.heartline.phone.widget.title
import com.heartline.phone.widget.widgetIcon
import com.heartline.shared.model.Metric
import com.heartline.shared.nav.EntrySource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.mp.KoinPlatform

private val Context.quickTileStore by preferencesDataStore("quick_tiles")

/** Which measurement the "Measure" Quick Settings tile starts (chosen in the app's settings). */
object QuickTilePrefs {
    private val METRIC = stringPreferencesKey("measure_metric")

    val choices = listOf(Metric.SPO2, Metric.STRESS, Metric.SKIN_TEMPERATURE, Metric.BODY_COMPOSITION, Metric.HEART_RATE, Metric.ECG, Metric.BLOOD_PRESSURE)

    fun metric(context: Context) = context.quickTileStore.data.map { p -> Metric.entries.firstOrNull { it.name == p[METRIC] } ?: Metric.SPO2 }

    suspend fun setMetric(context: Context, metric: Metric) {
        context.quickTileStore.edit { it[METRIC] = metric.name }
        TileService.requestListeningState(context, ComponentName(context, MeasureQsTile::class.java))
    }
}

/**
 * A Quick Settings tile that starts [metric] on the watch with one tap, and shows the latest
 * reading and when it was taken as its subtitle. Without a watch it opens the reading in the app.
 */
abstract class HeartlineQsTile : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    abstract suspend fun metric(): Metric

    override fun onStartListening() {
        super.onStartListening()
        scope.launch { refresh() }
    }

    private suspend fun refresh() {
        val tile = qsTile ?: return
        val metric = metric()
        val snapshot = withContext(Dispatchers.IO) {
            runCatching { KoinPlatform.getKoin().get<WidgetDataSource>().load() }.getOrNull() ?: WidgetSnapshot()
        }
        val info = WidgetMetrics.info(this, snapshot, metric)
        tile.label = getString(metric.title)
        tile.icon = Icon.createWithResource(this, metric.widgetIcon)
        tile.contentDescription = getString(R.string.qs_measure_description, getString(metric.title))
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = if (info.hasData) listOfNotNull(info.tinyValue?.let { v -> info.unit?.let { "$v $it" } ?: v }, info.at).joinToString(" · ") else getString(R.string.qs_tap_to_measure)
        }
        tile.state = Tile.STATE_INACTIVE
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        scope.launch {
            val metric = metric()
            qsTile?.let {
                it.state = Tile.STATE_ACTIVE
                it.updateTile()
            }
            if (metric.measuresOnPhone) {
                // The phone runs the measurement and shows it; the watch measures and shows it too.
                HLog.i(TAG, "measure ${metric.name} from Quick Settings on the phone")
                openInApp(metric, WidgetRoutes.measure(metric))
                refresh()
                return@launch
            }
            val result = runCatching { KoinPlatform.getKoin().get<WatchOpener>().open(WidgetRoutes.watch(metric)) }.getOrDefault(OpenResult.NO_WATCH)
            HLog.i(TAG, "measure ${metric.name} from Quick Settings: $result")
            when (result) {
                OpenResult.OPENED -> Toast.makeText(this@HeartlineQsTile, R.string.widget_opened_on_watch, Toast.LENGTH_SHORT).show()
                OpenResult.NOTIFIED -> Toast.makeText(this@HeartlineQsTile, R.string.widget_check_watch, Toast.LENGTH_SHORT).show()
                OpenResult.NO_WATCH -> openInApp(metric)
            }
            refresh()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openInApp(metric: Metric, route: String = WidgetRoutes.metric(metric)) {
        val intent = appIntent(this, route, EntrySource.QUICK_SETTINGS)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, metric.ordinal, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val TAG = "Heartline/QsTile"

        val ALL = listOf(EcgQsTile::class.java, BpQsTile::class.java, MeasureQsTile::class.java)

        /** Asks every tile to redraw (new readings). */
        fun refreshAll(context: Context) {
            ALL.forEach { runCatching { requestListeningState(context, ComponentName(context, it)) } }
        }

        /**
         * Offers to add a tile to Quick Settings (Android 13+ shows a system dialog). Returns false
         * when the phone can't ask, so the caller explains how to add it by hand.
         */
        fun requestAdd(context: Context, tile: Class<out HeartlineQsTile>, label: String, icon: Int, onResult: (Boolean) -> Unit): Boolean {
            if (Build.VERSION.SDK_INT < 33) return false
            val manager = context.getSystemService(StatusBarManager::class.java) ?: return false
            manager.requestAddTileService(ComponentName(context, tile), label, Icon.createWithResource(context, icon), context.mainExecutor) { code ->
                onResult(code == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED || code == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED)
            }
            return true
        }
    }
}

/** Starts an ECG on the watch. */
class EcgQsTile : HeartlineQsTile() {
    override suspend fun metric() = Metric.ECG
}

/** Starts a blood-pressure reading on the watch. */
class BpQsTile : HeartlineQsTile() {
    override suspend fun metric() = Metric.BLOOD_PRESSURE
}

/** Starts the measurement chosen in settings (blood oxygen by default). */
class MeasureQsTile : HeartlineQsTile() {
    override suspend fun metric() = QuickTilePrefs.metric(this).first()
}
