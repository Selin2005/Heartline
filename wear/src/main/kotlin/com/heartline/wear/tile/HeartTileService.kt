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
import com.heartline.shared.model.Severity
import com.heartline.shared.profile.StressLevel
import com.heartline.wear.MainActivity
import com.heartline.wear.R
import com.heartline.wear.bp.WatchBpStore
import com.heartline.wear.data.WatchRecordStore
import com.heartline.wear.monitor.WatchSettingsStore
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

/** Shared plumbing: loads [TileData] off the main thread and renders it with [layout]. */
abstract class HeartlineTileService : TileService() {
    private val records: WatchRecordStore by inject()
    private val settings: WatchSettingsStore by inject()
    private val bp: WatchBpStore by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Internal so the render test can draw a tile from sample data. */
    internal abstract fun MaterialScope.layout(context: Context, data: TileData): LayoutElementBuilders.LayoutElement

    /** Loads what the tiles and complications show. */
    internal suspend fun load(): TileData {
        val now = System.currentTimeMillis()
        return TileData.from(
            records.recent.first(),
            settings.latestHeartRate,
            bp.calibration.value?.takeIf { it.isValid(now) }?.daysLeft(now),
            settings.heartToday(),
        ) { ecg -> ecg.result?.let { getString(it.label) } }
    }

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        CallbackToFutureAdapter.getFuture { completer ->
            scope.launch {
                runCatching {
                    val data = load()
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

/** Card with a ring on the left (value within its range) and the reading on the right. */
internal fun MaterialScope.ringCard(
    context: Context,
    route: String,
    metric: Metric,
    value: String,
    detail: String,
    progress: Float,
    ringColor: LayoutColor = TileColors.metric(metric),
): LayoutElementBuilders.LayoutElement = graphicDataCard(
    onClick = context.launch(route),
    title = { text(value.layoutString, typography = TileType.big, color = TileColors.of(Palette.Dark.ON_BACKGROUND)) },
    content = { text(detail.layoutString, typography = Typography.LABEL_SMALL, color = TileColors.of(Palette.Dark.ON_SURFACE_VARIANT), maxLines = 2) },
    height = expand(),
    colors = CardColors(
        backgroundColor = TileColors.metricContainer(metric),
        titleColor = TileColors.of(Palette.Dark.ON_BACKGROUND),
        contentColor = TileColors.of(Palette.Dark.ON_SURFACE_VARIANT),
        graphicProgressIndicatorColors = ProgressIndicatorColors(ringColor, TileColors.of(0x33FFFFFF)),
    ),
    graphic = {
        LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .addContent(circularProgressIndicator(staticProgress = progress.coerceIn(0.02f, 1f), colors = ProgressIndicatorColors(ringColor, TileColors.of(0x33FFFFFF))))
            .addContent(icon(TileIcons.id(metric), tintColor = ringColor))
            .build()
    },
)

/** Heart: live heart rate with today's range as a ring, last ECG, and an ECG button. */
class HeartTileService : HeartlineTileService() {
    override fun MaterialScope.layout(context: Context, data: TileData) = primaryLayout(
        titleSlot = { text(context.getString(R.string.tile_heart).layoutString) },
        mainSlot = {
            val bpm = data.heartRate
            val range = if (data.heartMin != null && data.heartMax != null) "${data.heartMin}–${data.heartMax}" else null
            LayoutElementBuilders.Column.Builder()
                .setWidth(expand())
                .setHeight(expand())
                .addContent(
                    ringCard(
                        context,
                        TileRoutes.HEART_RATE,
                        Metric.HEART_RATE,
                        bpm?.toString() ?: "–",
                        listOfNotNull(context.getString(R.string.unit_bpm), range?.let { context.getString(R.string.tile_today_range, it) }).joinToString("\n"),
                        progress = bpm?.let { (it - 40) / 140f } ?: 0f,
                    ),
                )
                .addContent(
                    text(
                        (data.lastEcg?.let { context.getString(R.string.tile_last_ecg, it) } ?: context.getString(R.string.tile_no_ecg)).layoutString,
                        typography = Typography.LABEL_SMALL,
                        color = data.ecgResult?.let { TileColors.severity(it.severity) } ?: TileColors.of(Palette.Dark.ON_SURFACE_VARIANT),
                    ),
                )
                .build()
        },
        bottomSlot = {
            textEdgeButton(onClick = context.launch(TileRoutes.ECG), colors = ButtonColors(containerColor = TileColors.metric(Metric.ECG), labelColor = TileColors.of(0xFF000000))) {
                text(context.getString(R.string.metric_ecg).layoutString, typography = TileType.button)
            }
        },
    )
}

/** Blood pressure: latest reading and category, calibration days as a ring, and Measure. */
class BpTileService : HeartlineTileService() {
    override fun MaterialScope.layout(context: Context, data: TileData) = primaryLayout(
        titleSlot = { text(context.getString(R.string.metric_bp).layoutString) },
        mainSlot = {
            val days = data.bpDaysLeft
            ringCard(
                context,
                TileRoutes.BLOOD_PRESSURE,
                Metric.BLOOD_PRESSURE,
                data.lastBp ?: "–",
                listOfNotNull(
                    data.bpCategory?.let { context.getString(it.label) },
                    days?.let { context.getString(R.string.tile_days_left, it) } ?: context.getString(R.string.tile_calibrate_on_phone),
                ).joinToString("\n"),
                progress = days?.let { it / 28f } ?: 0f,
                ringColor = data.bpCategory?.let { TileColors.severity(it.severity()) } ?: TileColors.metric(Metric.BLOOD_PRESSURE),
            )
        },
        bottomSlot = {
            textEdgeButton(onClick = context.launch(TileRoutes.BLOOD_PRESSURE), colors = ButtonColors(containerColor = TileColors.metric(Metric.BLOOD_PRESSURE), labelColor = TileColors.of(0xFF000000))) {
                text(context.getString(if (data.bpDaysLeft != null) R.string.tile_measure else R.string.tile_calibrate).layoutString, typography = TileType.button)
            }
        },
    )
}

/** Four round buttons that start a measurement, and "All" for the app. */
class QuickMeasureTileService : HeartlineTileService() {
    override fun MaterialScope.layout(context: Context, data: TileData) = primaryLayout(
        titleSlot = { text(context.getString(R.string.tile_quick_title).layoutString) },
        mainSlot = {
            fun MaterialScope.metricButton(metric: Metric) = iconButton(
                onClick = context.launch(TileRoutes.measure(metric)),
                iconContent = { icon(TileIcons.id(metric), tintColor = TileColors.metric(metric)) },
                width = expand(),
                height = expand(),
                colors = ButtonColors(containerColor = TileColors.metricContainer(metric), iconColor = TileColors.metric(metric)),
            )
            LayoutElementBuilders.Column.Builder()
                .setWidth(expand())
                .setHeight(expand())
                .addContent(
                    buttonGroup(height = expand()) {
                        buttonGroupItem { metricButton(Metric.ECG) }
                        buttonGroupItem { metricButton(Metric.BLOOD_PRESSURE) }
                    },
                )
                .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(androidx.wear.protolayout.DimensionBuilders.dp(6f)).build())
                .addContent(
                    buttonGroup(height = expand()) {
                        buttonGroupItem { metricButton(Metric.SPO2) }
                        buttonGroupItem { metricButton(Metric.STRESS) }
                    },
                )
                .build()
        },
        bottomSlot = {
            textEdgeButton(onClick = context.launch(TileRoutes.LAUNCHER)) { text(context.getString(R.string.tile_all).layoutString, typography = TileType.button) }
        },
    )
}

/** SpO2, stress and skin temperature side by side; each opens its measurement. */
class WellnessTileService : HeartlineTileService() {
    override fun MaterialScope.layout(context: Context, data: TileData) = primaryLayout(
        titleSlot = { text(context.getString(R.string.tile_wellness).layoutString) },
        mainSlot = {
            fun MaterialScope.valueCard(metric: Metric, value: String?, label: Int) = LayoutElementBuilders.Box.Builder()
                .setWidth(expand())
                .setHeight(expand())
                .setModifiers(
                    ModifiersBuilders.Modifiers.Builder()
                        .setClickable(context.launch(TileRoutes.measure(metric)))
                        .setBackground(
                            ModifiersBuilders.Background.Builder()
                                .setColor(androidx.wear.protolayout.ColorBuilders.argb(TileColors.metricContainer(metric).staticArgb))
                                .setCorner(ModifiersBuilders.Corner.Builder().setRadius(androidx.wear.protolayout.DimensionBuilders.dp(22f)).build())
                                .build(),
                        )
                        .build(),
                )
                .addContent(
                    LayoutElementBuilders.Column.Builder()
                        .addContent(icon(TileIcons.id(metric), tintColor = TileColors.metric(metric)))
                        .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(androidx.wear.protolayout.DimensionBuilders.dp(4f)).build())
                        .addContent(text((value ?: "–").layoutString, typography = TileType.value, color = TileColors.of(Palette.Dark.ON_BACKGROUND)))
                        .addContent(text(context.getString(label).layoutString, typography = Typography.LABEL_SMALL, color = TileColors.of(Palette.Dark.ON_SURFACE_VARIANT)))
                        .build(),
                )
                .build()
            buttonGroup(height = expand()) {
                buttonGroupItem { valueCard(Metric.SPO2, data.spo2?.let { "$it%" }, R.string.tile_spo2_short) }
                buttonGroupItem { valueCard(Metric.STRESS, data.stressScore?.toString(), R.string.metric_stress) }
                buttonGroupItem { valueCard(Metric.SKIN_TEMPERATURE, data.temperature, R.string.tile_temp_short) }
            }
        },
    )
}

/** Stress: score on a ring coloured by level, HRV, and Measure. */
class StressTileService : HeartlineTileService() {
    override fun MaterialScope.layout(context: Context, data: TileData) = primaryLayout(
        titleSlot = { text(context.getString(R.string.metric_stress).layoutString) },
        mainSlot = {
            ringCard(
                context,
                TileRoutes.measure(Metric.STRESS),
                Metric.STRESS,
                data.stressScore?.toString() ?: "–",
                listOfNotNull(
                    data.stressLevel?.let { context.getString(it.label) },
                    data.hrvMs?.let { context.getString(R.string.stress_hrv, it) },
                ).joinToString("\n").ifEmpty { context.getString(R.string.tile_not_measured) },
                progress = (data.stressScore ?: 0) / 100f,
                ringColor = data.stressLevel?.let { TileColors.severity(it.severity()) } ?: TileColors.metric(Metric.STRESS),
            )
        },
        bottomSlot = {
            textEdgeButton(onClick = context.launch(TileRoutes.measure(Metric.STRESS)), colors = ButtonColors(containerColor = TileColors.metric(Metric.STRESS), labelColor = TileColors.of(0xFF000000))) {
                text(context.getString(R.string.tile_measure).layoutString, typography = TileType.button)
            }
        },
    )
}
