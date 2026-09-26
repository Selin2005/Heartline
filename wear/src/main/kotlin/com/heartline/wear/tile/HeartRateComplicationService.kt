// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.tile

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.annotation.DrawableRes
import androidx.wear.watchface.complications.data.ColorRamp
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.heartline.shared.design.Palette
import com.heartline.shared.model.EcgResult
import com.heartline.shared.model.Metric
import com.heartline.shared.nav.EntryLinks
import com.heartline.shared.nav.EntrySource
import com.heartline.shared.profile.StressLevel
import com.heartline.wear.MainActivity
import com.heartline.wear.R
import org.koin.android.ext.android.inject

/**
 * Builds every Heartline complication from [TileData]; pure apart from Android resources, so
 * previews, providers and tests share it.
 */
class Complications(private val context: Context) {
    private fun text(value: String) = PlainComplicationText.Builder(value).build()

    private fun mono(@DrawableRes icon: Int) = MonochromaticImage.Builder(Icon.createWithResource(context, icon)).build()

    fun tap(route: String): PendingIntent = PendingIntent.getActivity(
        context,
        route.hashCode(),
        Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, EntryLinks.tag(route, EntrySource.COMPLICATION)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private val noData: ComplicationData get() = NoDataComplicationData()

    fun heartRate(type: ComplicationType, data: TileData): ComplicationData {
        val bpm = data.heartRate
        val description = text(context.getString(R.string.complication_hr_description))
        val tap = tap(TileRoutes.HEART_RATE)
        val icon = mono(R.drawable.ic_metric_heart)
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(text(bpm?.toString() ?: "--"), description)
                .setTitle(text(context.getString(R.string.unit_bpm)))
                .setMonochromaticImage(icon)
                .setTapAction(tap)
                .build()
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder((bpm ?: 40).toFloat().coerceIn(40f, 180f), 40f, 180f, description)
                .setText(text(bpm?.toString() ?: "--"))
                .setMonochromaticImage(icon)
                .setColorRamp(ColorRamp(intArrayOf(Palette.Dark.HEART_RATE.toInt(), Palette.Dark.ECG.toInt()), true))
                .setTapAction(tap)
                .build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                text(
                    bpm?.let {
                        listOfNotNull(
                            context.getString(R.string.tile_bpm, it),
                            if (data.heartMin != null && data.heartMax != null) "${data.heartMin}–${data.heartMax}" else null,
                        ).joinToString(" · ")
                    } ?: context.getString(R.string.complication_no_data),
                ),
                description,
            )
                .setTitle(text(context.getString(R.string.metric_hr)))
                .setMonochromaticImage(icon)
                .setTapAction(tap)
                .build()
            else -> noData
        }
    }

    fun ecg(type: ComplicationType, data: TileData): ComplicationData {
        val description = text(context.getString(R.string.complication_ecg_description))
        val tap = tap(TileRoutes.ECG)
        val icon = mono(R.drawable.ic_metric_ecg)
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(text(data.ecgResult?.let { context.getString(it.shortLabel) } ?: "--"), description)
                .setTitle(text(context.getString(R.string.metric_ecg)))
                .setMonochromaticImage(icon)
                .setTapAction(tap)
                .build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(text(data.lastEcg ?: context.getString(R.string.tile_no_ecg)), description)
                .setTitle(text(context.getString(R.string.metric_ecg)))
                .setMonochromaticImage(icon)
                .setTapAction(tap)
                .build()
            else -> noData
        }
    }

    fun bloodPressure(type: ComplicationType, data: TileData): ComplicationData {
        val description = text(context.getString(R.string.tile_bp_description))
        val tap = tap(TileRoutes.BLOOD_PRESSURE)
        val icon = mono(R.drawable.ic_metric_bp)
        val days = data.bpDaysLeft?.let { context.getString(R.string.tile_bp_days, it) } ?: context.getString(R.string.tile_calibrate_on_phone)
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(text(data.lastBp ?: "--"), description)
                .setTitle(text(context.getString(R.string.complication_mmhg)))
                .setMonochromaticImage(icon)
                .setTapAction(tap)
                .build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(text(listOfNotNull(data.lastBp, days).joinToString(" · ")), description)
                .setTitle(text(context.getString(R.string.metric_bp)))
                .setMonochromaticImage(icon)
                .setTapAction(tap)
                .build()
            else -> noData
        }
    }

    fun stress(type: ComplicationType, data: TileData): ComplicationData {
        val description = text(context.getString(R.string.metric_stress))
        val tap = tap(TileRoutes.measure(Metric.STRESS))
        val icon = mono(R.drawable.ic_metric_stress)
        val score = data.stressScore
        return when (type) {
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder((score ?: 0).toFloat(), 0f, 100f, description)
                .setText(text(score?.toString() ?: "--"))
                .setMonochromaticImage(icon)
                .setColorRamp(ColorRamp(intArrayOf(Palette.Dark.STATUS_NORMAL.toInt(), Palette.Dark.STATUS_WARN.toInt(), Palette.Dark.STATUS_ALERT.toInt()), true))
                .setTapAction(tap)
                .build()
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(text(score?.toString() ?: "--"), description)
                .setTitle(text(data.stressLevel?.let { context.getString(it.label) } ?: context.getString(R.string.metric_stress)))
                .setMonochromaticImage(icon)
                .setTapAction(tap)
                .build()
            else -> noData
        }
    }

    fun spo2(type: ComplicationType, data: TileData): ComplicationData {
        val description = text(context.getString(R.string.metric_spo2))
        val tap = tap(TileRoutes.measure(Metric.SPO2))
        val icon = mono(R.drawable.ic_metric_spo2)
        val value = data.spo2
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(text(value?.let { "$it%" } ?: "--"), description)
                .setTitle(text(context.getString(R.string.tile_spo2_short)))
                .setMonochromaticImage(icon)
                .setTapAction(tap)
                .build()
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder((value ?: 80).toFloat().coerceIn(80f, 100f), 80f, 100f, description)
                .setText(text(value?.let { "$it%" } ?: "--"))
                .setMonochromaticImage(icon)
                .setColorRamp(ColorRamp(intArrayOf(Palette.Dark.STATUS_WARN.toInt(), Palette.Dark.SPO2.toInt()), true))
                .setTapAction(tap)
                .build()
            else -> noData
        }
    }

    /** Starts an ECG straight from the watch face. */
    fun ecgShortcut(type: ComplicationType): ComplicationData = shortcut(Metric.ECG, type)

    /** Starts [metric] straight from the watch face (a round colour badge, or its icon on monochrome faces). */
    fun shortcut(metric: Metric, type: ComplicationType): ComplicationData {
        val (badge, label) = when (metric) {
            Metric.ECG -> R.drawable.complication_ecg_badge to R.string.complication_ecg_shortcut
            Metric.BLOOD_PRESSURE -> R.drawable.complication_bp_badge to R.string.complication_bp_shortcut
            Metric.SPO2 -> R.drawable.complication_spo2_badge to R.string.complication_spo2_shortcut
            Metric.STRESS -> R.drawable.complication_stress_badge to R.string.complication_stress_shortcut
            else -> R.drawable.complication_body_badge to R.string.complication_body_shortcut
        }
        val description = text(context.getString(label))
        val tap = tap(TileRoutes.measure(metric))
        val icon = TileIcons.drawable(metric)
        return when (type) {
            ComplicationType.SMALL_IMAGE -> SmallImageComplicationData.Builder(
                SmallImage.Builder(Icon.createWithResource(context, badge), SmallImageType.ICON).build(),
                description,
            ).setTapAction(tap).build()
            ComplicationType.MONOCHROMATIC_IMAGE -> MonochromaticImageComplicationData.Builder(mono(icon), description).setTapAction(tap).build()
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(text(context.getString(metric.shortTitle)), description)
                .setMonochromaticImage(mono(icon))
                .setTapAction(tap)
                .build()
            else -> noData
        }
    }

    /** Body fat: "21.4%", a gauge (0–45 %), or "21.4% fat · 72.4 kg". */
    fun body(type: ComplicationType, data: TileData): ComplicationData {
        val description = text(context.getString(R.string.tile_body))
        val tap = tap(TileRoutes.measure(Metric.BODY_COMPOSITION))
        val icon = mono(R.drawable.ic_metric_body)
        val fat = data.body?.fatPercent
        val value = fat?.let { String.format(java.util.Locale.US, "%.1f%%", it) } ?: "--"
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(text(value), description)
                .setTitle(text(context.getString(R.string.complication_fat)))
                .setMonochromaticImage(icon)
                .setTapAction(tap)
                .build()
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder((fat ?: 0f).coerceIn(0f, 45f), 0f, 45f, description)
                .setText(text(value))
                .setMonochromaticImage(icon)
                .setColorRamp(ColorRamp(intArrayOf(Palette.Dark.BODY.toInt(), Palette.Dark.STATUS_WARN.toInt()), true))
                .setTapAction(tap)
                .build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                text(
                    data.body?.let { b ->
                        listOfNotNull(context.getString(R.string.complication_fat_value, value), b.weightKg?.let { context.getString(R.string.tile_body_weight, it) }).joinToString(" · ")
                    } ?: context.getString(R.string.complication_no_data),
                ),
                description,
            )
                .setTitle(text(context.getString(R.string.tile_body)))
                .setMonochromaticImage(icon)
                .setTapAction(tap)
                .build()
            else -> noData
        }
    }

    /** Skin temperature: the change from the user's usual, or the reading. */
    fun temperature(type: ComplicationType, data: TileData): ComplicationData {
        val description = text(context.getString(R.string.metric_skin_temp))
        val tap = tap(TileRoutes.measure(Metric.SKIN_TEMPERATURE))
        val icon = mono(R.drawable.ic_metric_temp)
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(text(data.temperature ?: "--"), description)
                .setTitle(text(context.getString(R.string.tile_temp_short)))
                .setMonochromaticImage(icon)
                .setTapAction(tap)
                .build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(text(data.temperature ?: context.getString(R.string.complication_no_data)), description)
                .setTitle(text(context.getString(R.string.metric_skin_temp)))
                .setMonochromaticImage(icon)
                .setTapAction(tap)
                .build()
            else -> noData
        }
    }

    /** Today's check-ins: "1/3", a ring filling as they are done, or "1 of 3 done · Next: BP". */
    fun today(type: ComplicationType, data: TileData): ComplicationData {
        val checks = TodayChecks.of(data)
        val done = checks.count { it.second }
        val next = checks.firstOrNull { !it.second }?.first
        val description = text(context.getString(R.string.complication_today))
        val tap = tap(next?.let { TileRoutes.measure(it) } ?: TileRoutes.LAUNCHER)
        val icon = mono(R.drawable.ic_heart)
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(text("$done/${checks.size}"), description)
                .setTitle(text(context.getString(R.string.complication_today)))
                .setMonochromaticImage(icon)
                .setTapAction(tap)
                .build()
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(done.toFloat(), 0f, checks.size.toFloat(), description)
                .setText(text("$done/${checks.size}"))
                .setMonochromaticImage(icon)
                .setColorRamp(ColorRamp(intArrayOf(Palette.Dark.PRIMARY.toInt(), Palette.Dark.STATUS_NORMAL.toInt()), true))
                .setTapAction(tap)
                .build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                text(
                    listOfNotNull(
                        context.getString(R.string.tile_today_done, done, checks.size),
                        next?.let { context.getString(R.string.tile_today_next, context.getString(it.shortTitle)) },
                    ).joinToString(" · "),
                ),
                description,
            )
                .setTitle(text(Greeting.title(context, data.name)))
                .setMonochromaticImage(icon)
                .setTapAction(tap)
                .build()
            else -> noData
        }
    }

    companion object {
        /** Realistic values for the complication picker. */
        val preview = TileData(
            heartRate = 68,
            lastEcg = "Sinus rhythm",
            lastBp = "118/76",
            bpDaysLeft = 21,
            heartMin = 52,
            heartMax = 118,
            ecgResult = EcgResult.SINUS_RHYTHM,
            spo2 = 97,
            stressScore = 38,
            stressLevel = StressLevel.MEDIUM,
            temperature = "+0.2°",
            body = TileData.Body(21.4f, 32.1f, 72.4f, -0.3f),
            doneToday = setOf(Metric.ECG),
        )
    }
}

internal val Metric.shortTitle: Int
    get() = when (this) {
        Metric.ECG -> R.string.metric_ecg
        Metric.BLOOD_PRESSURE -> R.string.complication_bp_short
        Metric.HEART_RATE -> R.string.metric_hr
        Metric.SPO2 -> R.string.tile_spo2_short
        Metric.SKIN_TEMPERATURE -> R.string.tile_temp_short
        Metric.BODY_COMPOSITION -> R.string.complication_body_short
        Metric.STRESS -> R.string.metric_stress
    }

internal val EcgResult.shortLabel: Int
    get() = when (this) {
        EcgResult.SINUS_RHYTHM -> R.string.ecg_short_sinus
        EcgResult.AFIB_SIGNS -> R.string.ecg_short_afib
        EcgResult.HIGH_HEART_RATE -> R.string.ecg_short_high
        EcgResult.LOW_HEART_RATE -> R.string.ecg_short_low
        EcgResult.INCONCLUSIVE -> R.string.ecg_short_inconclusive
        EcgResult.POOR_RECORDING -> R.string.ecg_short_poor
    }

/** Shared provider: loads the data and hands it to one [Complications] builder. */
abstract class HeartlineComplicationService : SuspendingComplicationDataSourceService() {
    private val loader: TileDataLoader by inject()
    protected val complications by lazy { Complications(this) }

    protected abstract fun build(type: ComplicationType, data: TileData): ComplicationData

    override fun getPreviewData(type: ComplicationType): ComplicationData? = build(type, Complications.preview).takeUnless { it is NoDataComplicationData }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? = build(request.complicationType, loader.load())
}

/** Heart rate: number, gauge (40–180) or "68 bpm · 52–118". */
class HeartRateComplicationService : HeartlineComplicationService() {
    override fun build(type: ComplicationType, data: TileData) = complications.heartRate(type, data)
}

class EcgComplicationService : HeartlineComplicationService() {
    override fun build(type: ComplicationType, data: TileData) = complications.ecg(type, data)
}

class BpComplicationService : HeartlineComplicationService() {
    override fun build(type: ComplicationType, data: TileData) = complications.bloodPressure(type, data)
}

class StressComplicationService : HeartlineComplicationService() {
    override fun build(type: ComplicationType, data: TileData) = complications.stress(type, data)
}

class Spo2ComplicationService : HeartlineComplicationService() {
    override fun build(type: ComplicationType, data: TileData) = complications.spo2(type, data)
}

class EcgShortcutComplicationService : HeartlineComplicationService() {
    override fun build(type: ComplicationType, data: TileData) = complications.ecgShortcut(type)

    // The shortcut needs no data.
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? = complications.ecgShortcut(request.complicationType)
}

class BodyComplicationService : HeartlineComplicationService() {
    override fun build(type: ComplicationType, data: TileData) = complications.body(type, data)
}

class TemperatureComplicationService : HeartlineComplicationService() {
    override fun build(type: ComplicationType, data: TileData) = complications.temperature(type, data)
}

class TodayComplicationService : HeartlineComplicationService() {
    override fun build(type: ComplicationType, data: TileData) = complications.today(type, data)
}

/** "Start a measurement" shortcuts for the watch face; they need no data. */
abstract class ShortcutComplicationService(private val metric: Metric) : HeartlineComplicationService() {
    override fun build(type: ComplicationType, data: TileData) = complications.shortcut(metric, type)

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? = complications.shortcut(metric, request.complicationType)
}

class BpShortcutComplicationService : ShortcutComplicationService(Metric.BLOOD_PRESSURE)

class Spo2ShortcutComplicationService : ShortcutComplicationService(Metric.SPO2)

class StressShortcutComplicationService : ShortcutComplicationService(Metric.STRESS)

class BodyShortcutComplicationService : ShortcutComplicationService(Metric.BODY_COMPOSITION)
