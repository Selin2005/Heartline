package com.heartline.wear.tile

import android.content.ComponentName
import android.content.Context
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material3.MaterialScope
import androidx.wear.protolayout.material3.Typography
import androidx.wear.protolayout.material3.materialScope
import androidx.wear.protolayout.material3.primaryLayout
import androidx.wear.protolayout.material3.text
import androidx.wear.protolayout.material3.textEdgeButton
import androidx.wear.protolayout.types.layoutString
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import com.heartline.wear.MainActivity
import com.heartline.wear.R
import com.heartline.wear.bp.WatchBpStore
import com.heartline.wear.data.WatchRecordStore
import com.heartline.wear.monitor.WatchSettingsStore
import com.heartline.wear.ui.components.label
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

private const val RESOURCES_VERSION = "1"

/** Opens the app on a given screen (MainActivity.EXTRA_ROUTE). */
internal fun Context.launch(route: String): ModifiersBuilders.Clickable = ModifiersBuilders.Clickable.Builder()
    .setId(route)
    .setOnClick(
        ActionBuilders.LaunchAction.Builder()
            .setAndroidActivity(
                ActionBuilders.AndroidActivity.Builder()
                    .setPackageName(packageName)
                    .setClassName(MainActivity::class.java.name)
                    .addKeyToExtraMapping(MainActivity.EXTRA_ROUTE, ActionBuilders.AndroidStringExtra.Builder().setValue(route).build())
                    .build(),
            )
            .build(),
    )
    .build()

/** Shared plumbing: loads [TileData] off the main thread and renders it with [layout]. */
abstract class HeartlineTileService : TileService() {
    private val records: WatchRecordStore by inject()
    private val settings: WatchSettingsStore by inject()
    private val bp: WatchBpStore by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    protected abstract fun MaterialScope.layout(context: Context, data: TileData): LayoutElementBuilders.LayoutElement

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        CallbackToFutureAdapter.getFuture { completer ->
            scope.launch {
                runCatching {
                    val now = System.currentTimeMillis()
                    val data = TileData.from(
                        records.recent.first(),
                        settings.latestHeartRate,
                        bp.calibration.value?.takeIf { it.isValid(now) }?.daysLeft(now),
                    ) { ecg -> ecg.result?.let { getString(it.label) } }
                    val root = materialScope(this@HeartlineTileService, requestParams.deviceConfiguration) { layout(this@HeartlineTileService, data) }
                    TileBuilders.Tile.Builder()
                        .setResourcesVersion(RESOURCES_VERSION)
                        .setFreshnessIntervalMillis(15 * 60_000L)
                        .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(root))
                        .build()
                }.onSuccess(completer::set).onFailure(completer::setException)
            }
            "heartline-tile"
        }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        CallbackToFutureAdapter.getFuture { completer ->
            completer.set(ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build())
            "heartline-resources"
        }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

/** Heart tile: latest heart rate and ECG result, with a button that starts an ECG. */
class HeartTileService : HeartlineTileService() {
    override fun MaterialScope.layout(context: Context, data: TileData) = primaryLayout(
        titleSlot = { text(context.getString(R.string.tile_heart).layoutString) },
        mainSlot = {
            LayoutElementBuilders.Column.Builder()
                .addContent(
                    text(
                        (data.heartRate?.let { context.getString(R.string.tile_bpm, it) } ?: "–").layoutString,
                        typography = Typography.DISPLAY_SMALL,
                    ),
                )
                .addContent(
                    text(
                        (data.lastEcg?.let { context.getString(R.string.tile_last_ecg, it) } ?: context.getString(R.string.tile_no_ecg)).layoutString,
                        typography = Typography.BODY_SMALL,
                    ),
                )
                .build()
        },
        bottomSlot = {
            textEdgeButton(onClick = context.launch(MainActivity.ROUTE_ECG)) { text(context.getString(R.string.metric_ecg).layoutString) }
        },
    )
}

/** Blood pressure tile: latest reading and calibration days, with a Measure button. */
class BpTileService : HeartlineTileService() {
    override fun MaterialScope.layout(context: Context, data: TileData) = primaryLayout(
        titleSlot = { text(context.getString(R.string.metric_bp).layoutString) },
        mainSlot = {
            LayoutElementBuilders.Column.Builder()
                .addContent(text((data.lastBp ?: "–").layoutString, typography = Typography.DISPLAY_SMALL))
                .addContent(
                    text(
                        (data.bpDaysLeft?.let { context.getString(R.string.tile_bp_days, it) } ?: context.getString(R.string.bp_needs_calibration_title))
                            .layoutString,
                        typography = Typography.BODY_SMALL,
                    ),
                )
                .build()
        },
        bottomSlot = {
            textEdgeButton(onClick = context.launch(MainActivity.ROUTE_BP)) { text(context.getString(R.string.tile_measure).layoutString) }
        },
    )
}

