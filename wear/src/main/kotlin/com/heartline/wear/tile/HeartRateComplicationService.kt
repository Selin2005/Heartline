package com.heartline.wear.tile

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.heartline.wear.MainActivity
import com.heartline.wear.R
import com.heartline.wear.monitor.WatchSettingsStore
import org.koin.android.ext.android.inject

/** Short-text complication with the latest background heart rate; tapping opens the heart screen. */
class HeartRateComplicationService : SuspendingComplicationDataSourceService() {
    private val settings: WatchSettingsStore by inject()

    override fun getPreviewData(type: ComplicationType): ComplicationData? = if (type == ComplicationType.SHORT_TEXT) build(68) else null

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? =
        if (request.complicationType == ComplicationType.SHORT_TEXT) build(settings.latestHeartRate) else null

    private fun build(bpm: Int?): ShortTextComplicationData {
        val tap = PendingIntent.getActivity(
            this,
            3,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, MainActivity.ROUTE_HEART_RATE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return ShortTextComplicationData.Builder(
            PlainComplicationText.Builder(bpm?.toString() ?: "--").build(),
            PlainComplicationText.Builder(getString(R.string.complication_hr_description)).build(),
        )
            .setTitle(PlainComplicationText.Builder(getString(R.string.unit_bpm)).build())
            .setMonochromaticImage(MonochromaticImage.Builder(Icon.createWithResource(this, R.drawable.ic_heart)).build())
            .setTapAction(tap)
            .build()
    }
}
