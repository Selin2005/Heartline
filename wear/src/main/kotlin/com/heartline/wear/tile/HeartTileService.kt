package com.heartline.wear.tile

import android.content.Context
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material3.ButtonColors
import androidx.wear.protolayout.material3.CardColors
import androidx.wear.protolayout.material3.ColorScheme
import androidx.wear.protolayout.material3.MaterialScope
import androidx.wear.protolayout.material3.ProgressIndicatorColors
import androidx.wear.protolayout.material3.Typography
import androidx.wear.protolayout.material3.buttonGroup
import androidx.wear.protolayout.material3.circularProgressIndicator
import androidx.wear.protolayout.material3.graphicDataCard
import androidx.wear.protolayout.material3.icon
import androidx.wear.protolayout.material3.iconButton
import androidx.wear.protolayout.material3.materialScope
import androidx.wear.protolayout.material3.primaryLayout
import androidx.wear.protolayout.material3.text
import androidx.wear.protolayout.material3.textEdgeButton
import androidx.wear.protolayout.types.LayoutColor
import androidx.wear.protolayout.types.layoutString
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import com.heartline.shared.bp.BpCategory
import com.heartline.shared.design.Palette
import com.heartline.shared.model.Metric
import com.heartline.shared.nav.EntryLinks
import com.heartline.shared.nav.EntrySource
import com.heartline.shared.model.Severity
import com.heartline.shared.profile.StressLevel
import com.heartline.wear.MainActivity
import com.heartline.wear.R
import com.heartline.wear.bp.WatchBpStore
import com.heartline.wear.data.WatchRecordStore
import com.heartline.wear.monitor.WatchSettingsStore
import com.heartline.wear.quick.WatchProfileStore
import com.heartline.wear.ui.components.label
import com.heartline.wear.ui.screens.label
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

private const val RESOURCES_VERSION = "2"

/** Watch routes the tiles open (MainActivity.EXTRA_ROUTE); must match the watch nav graph. */
internal object TileRoutes {
    const val LAUNCHER = "launcher"
    const val HEART_RATE = MainActivity.ROUTE_HEART_RATE
    const val ECG = MainActivity.ROUTE_ECG
    const val BLOOD_PRESSURE = MainActivity.ROUTE_BP

    fun quick(metric: Metric) = "quick/${metric.name}"

    fun measure(metric: Metric) = when (metric) {
        Metric.ECG -> ECG
        Metric.BLOOD_PRESSURE -> BLOOD_PRESSURE
        Metric.HEART_RATE -> HEART_RATE
        else -> quick(metric)
    }
}

/** Opens the app on a given screen (MainActivity.EXTRA_ROUTE); swiping back returns to the tile. */
internal fun Context.launch(route: String): ModifiersBuilders.Clickable = ModifiersBuilders.Clickable.Builder()
    .setId(route)
    .setOnClick(
        ActionBuilders.LaunchAction.Builder()
            .setAndroidActivity(
                ActionBuilders.AndroidActivity.Builder()
                    .setPackageName(packageName)
                    .setClassName(MainActivity::class.java.name)
                    .addKeyToExtraMapping(MainActivity.EXTRA_ROUTE, ActionBuilders.AndroidStringExtra.Builder().setValue(EntryLinks.tag(route, EntrySource.TILE)).build())
                    .build(),
            )
            .build(),
    )
    .build()

/** Heartline colours for tiles: the app's dark palette on the watch's black background. */
internal object TileColors {
    fun of(argb: Long) = LayoutColor(argb.toInt())

    fun metric(metric: Metric) = of(
        when (metric) {
            Metric.ECG -> Palette.Dark.ECG
            Metric.BLOOD_PRESSURE -> Palette.Dark.BP
            Metric.HEART_RATE -> Palette.Dark.HEART_RATE
            Metric.SPO2 -> Palette.Dark.SPO2
            Metric.SKIN_TEMPERATURE -> Palette.Dark.TEMP
            Metric.BODY_COMPOSITION -> Palette.Dark.BODY
            Metric.STRESS -> Palette.Dark.STRESS
        },
    )

    /** The metric colour at low strength over black, for button and card containers. */
    fun metricContainer(metric: Metric): LayoutColor {
        val c = metric(metric).staticArgb.toLong() and 0xFFFFFFFF
        fun ch(shift: Int) = (((c shr shift) and 0xFF) * 0.24f).toLong() shl shift
        return of(0xFF000000 or ch(16) or ch(8) or ch(0))
    }

    fun severity(severity: Severity) = of(
        when (severity) {
            Severity.NORMAL -> Palette.Dark.STATUS_NORMAL
            Severity.WARN -> Palette.Dark.STATUS_WARN
            Severity.ALERT -> Palette.Dark.STATUS_ALERT
            Severity.NEUTRAL -> Palette.Dark.ON_SURFACE_VARIANT
        },
    )

    val scheme = ColorScheme(
        primary = of(Palette.Dark.PRIMARY),
        onPrimary = of(0xFF0B1A3A),
        primaryContainer = of(0xFF1E3A78),
        onPrimaryContainer = of(0xFFDCE6FF),
        surfaceContainerLow = of(Palette.Watch.SURFACE),
        surfaceContainer = of(Palette.Watch.SURFACE),
        surfaceContainerHigh = of(Palette.Watch.SURFACE_HIGH),
        onSurface = of(Palette.Dark.ON_BACKGROUND),
        onSurfaceVariant = of(Palette.Dark.ON_SURFACE_VARIANT),
        background = of(Palette.Watch.BACKGROUND),
        onBackground = of(Palette.Dark.ON_BACKGROUND),
    )
}

/**
 * Typography for the big numbers and button labels. The render test swaps in styles its
 * off-device renderer can draw (it can't draw the variable-font DISPLAY/NUMERAL/LABEL_LARGE ones).
 */
internal object TileType {
    var big = Typography.NUMERAL_MEDIUM
    var value = Typography.NUMERAL_SMALL
    var button = Typography.LABEL_LARGE
}

/** Image resource ids registered by every tile (the app's metric icons, tinted where used). */
internal object TileIcons {
    fun id(metric: Metric) = "metric_${metric.name.lowercase()}"

    fun drawable(metric: Metric) = when (metric) {
        Metric.ECG -> R.drawable.ic_metric_ecg
        Metric.BLOOD_PRESSURE -> R.drawable.ic_metric_bp
        Metric.HEART_RATE -> R.drawable.ic_metric_heart
        Metric.SPO2 -> R.drawable.ic_metric_spo2
        Metric.SKIN_TEMPERATURE -> R.drawable.ic_metric_temp
        Metric.BODY_COMPOSITION -> R.drawable.ic_metric_body
        Metric.STRESS -> R.drawable.ic_metric_stress
    }

    fun resources(): ResourceBuilders.Resources = ResourceBuilders.Resources.Builder()
        .setVersion(RESOURCES_VERSION)
        .apply {
            Metric.entries.forEach { metric ->
                addIdToImageMapping(
                    id(metric),
                    ResourceBuilders.ImageResource.Builder()
                        .setAndroidResourceByResId(ResourceBuilders.AndroidImageResourceByResId.Builder().setResourceId(drawable(metric)).build())
                        .build(),
                )
            }
        }
        .build()
}

internal fun BpCategory.severity() = when (this) {
    BpCategory.NORMAL -> Severity.NORMAL
    BpCategory.ELEVATED, BpCategory.HIGH_STAGE_1 -> Severity.WARN
    BpCategory.HIGH_STAGE_2, BpCategory.CRISIS -> Severity.ALERT
}

internal fun StressLevel.severity() = when (this) {
    StressLevel.LOW -> Severity.NORMAL
    StressLevel.MEDIUM -> Severity.WARN
    StressLevel.HIGH -> Severity.ALERT
}

internal val StressLevel.label: Int
    get() = when (this) {
        StressLevel.LOW -> R.string.stress_low
        StressLevel.MEDIUM -> R.string.stress_medium
        StressLevel.HIGH -> R.string.stress_high
    }

/** Reads what tiles and complications show from the watch's own stores (works without the phone). */
class TileDataLoader(
    private val context: Context,
    private val records: WatchRecordStore,
    private val settings: WatchSettingsStore,
    private val bp: WatchBpStore,
    private val profiles: WatchProfileStore? = null,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun load(): TileData {
        val nowMs = now()
        val monitor = settings.settings.value
        val showName = monitor.showNameOnWatch
        val zone = java.time.ZoneId.systemDefault()
        val streak = com.heartline.shared.profile.Streak.of(
            com.heartline.shared.profile.DailyGoal.byDay(records.history.first(), zone),
            monitor.dailyGoal,
            java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate(),
        )
        return TileData.from(
            records.recent.first(),
            settings.latestHeartRate,
            bp.calibration.value?.takeIf { it.isValid(nowMs) }?.daysLeft(nowMs),
            settings.heartToday(),
            nowMs = nowMs,
            name = profiles?.profile?.value?.displayName?.takeIf { showName },
        ) { ecg -> ecg.result?.let { context.getString(it.label) } }.copy(goal = monitor.dailyGoal, streak = streak, accent = monitor.accent)
    }
}

/** Shared plumbing: loads [TileData] off the main thread and renders it with [layout]. */
abstract class HeartlineTileService : TileService() {
    private val loader: TileDataLoader by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Internal so the render test can draw a tile from sample data. */
    internal abstract fun MaterialScope.layout(context: Context, data: TileData): LayoutElementBuilders.LayoutElement

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        CallbackToFutureAdapter.getFuture { completer ->
            scope.launch {
                runCatching {
                    val data = loader.load()
                    val root = materialScope(this@HeartlineTileService, requestParams.deviceConfiguration, defaultColorScheme = TileColors.scheme) {
                        layout(this@HeartlineTileService, data)
                    }
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
            completer.set(TileIcons.resources())
            "heartline-resources"
        }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

