package com.heartline.wear.tile

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.heartline.wear.bp.WatchBpStore
import com.heartline.wear.data.WatchRecordStore
import com.heartline.wear.monitor.WatchSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/**
 * Pushes fresh data to the tiles and watch-face complications: a new record or calibration
 * updates everything at once; heart rate (a new value every minute) at most every 5 minutes.
 */
class TileUpdates(
    private val context: Context,
    private val records: WatchRecordStore,
    private val bp: WatchBpStore,
    private val settings: WatchSettingsStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var lastHeartPush = 0L

    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope) {
        combine(records.recent.map { list -> list.firstOrNull()?.id }, bp.calibration.map { it?.id }) { a, b -> a to b }
            .distinctUntilChanged()
            .drop(1)
            .debounce(1_000)
            .onEach { requestAll("data") }
            .launchIn(scope)
        settings.heart
            .drop(1)
            .onEach {
                if (now() - lastHeartPush >= HEART_EVERY_MS) {
                    lastHeartPush = now()
                    requestAll("heart")
                }
            }
            .launchIn(scope)
    }

    fun requestAll(reason: String) {
        Log.i(TAG, "update tiles and complications ($reason)")
        val updater = TileService.getUpdater(context)
        TILES.forEach { runCatching { updater.requestUpdate(it) } }
        COMPLICATIONS.forEach { cls ->
            runCatching { ComplicationDataSourceUpdateRequester.create(context, ComponentName(context, cls)).requestUpdateAll() }
        }
    }

    companion object {
        private const val TAG = "Heartline/Tiles"
        private const val HEART_EVERY_MS = 5 * 60_000L

        val TILES = listOf(
            HeartTileService::class.java,
            BpTileService::class.java,
            QuickMeasureTileService::class.java,
            WellnessTileService::class.java,
            StressTileService::class.java,
            TodayTileService::class.java,
            BodyTileService::class.java,
        )

        val COMPLICATIONS: List<Class<*>> = listOf(
            HeartRateComplicationService::class.java,
            EcgComplicationService::class.java,
            BpComplicationService::class.java,
            StressComplicationService::class.java,
            Spo2ComplicationService::class.java,
            EcgShortcutComplicationService::class.java,
            BodyComplicationService::class.java,
            TemperatureComplicationService::class.java,
            TodayComplicationService::class.java,
        )
    }
}
