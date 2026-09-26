package com.heartline.wear.tile

import android.content.Context
import com.heartline.shared.model.Metric
import com.heartline.wear.R

/** The daily check-ins the Today tile tracks, in order, and whether each was done today. */
object TodayChecks {
    val DEFAULT = listOf(Metric.ECG, Metric.BLOOD_PRESSURE, Metric.SPO2)

    fun of(data: TileData, checks: List<Metric> = data.goal.ifEmpty { DEFAULT }): List<Pair<Metric, Boolean>> = checks.map { it to (it in data.doneToday) }
}

/** "Good morning, Sara" (or without a name) by the time of day. */
object Greeting {
    fun part(hour: Int): Int = when (hour) {
        in 5..11 -> R.string.greeting_morning
        in 12..16 -> R.string.greeting_afternoon
        in 17..21 -> R.string.greeting_evening
        else -> R.string.greeting_night
    }

    fun title(context: Context, name: String?, hour: Int = java.time.LocalTime.now().hour): String {
        val part = context.getString(part(hour))
        return if (name.isNullOrBlank()) part else context.getString(R.string.greeting_named, part, name)
    }
}

internal val Metric.labelRes: Int
    get() = when (this) {
        Metric.ECG -> R.string.metric_ecg
        Metric.BLOOD_PRESSURE -> R.string.metric_bp
        Metric.HEART_RATE -> R.string.metric_hr
        Metric.SPO2 -> R.string.metric_spo2
        Metric.SKIN_TEMPERATURE -> R.string.metric_skin_temp
        Metric.BODY_COMPOSITION -> R.string.metric_body
        Metric.STRESS -> R.string.metric_stress
    }
