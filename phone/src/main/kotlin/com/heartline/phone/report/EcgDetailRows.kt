package com.heartline.phone.report

import android.content.res.Resources
import com.heartline.phone.R
import com.heartline.shared.model.EcgMetrics
import com.heartline.shared.model.EcgPoorReason
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

/** "Recording details" rows, shared by the ECG detail screen and the PDF report. */
object EcgDetailRows {
    fun rows(res: Resources, m: EcgMetrics, zone: ZoneId = ZoneId.systemDefault()): List<Pair<String, String>> = buildList {
        val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM).withZone(zone)
        val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(zone)
        if (m.startedAtMs > 0) {
            val start = Instant.ofEpochMilli(m.startedAtMs)
            val end = Instant.ofEpochMilli(m.endedAtMs.coerceAtLeast(m.startedAtMs))
            add(res.getString(R.string.ecg_detail_recorded) to "${date.format(start)}, ${time.format(start)} – ${time.format(end)}")
        }
        add(res.getString(R.string.ecg_duration) to res.getString(R.string.value_seconds_1, m.durationSec))
        add(res.getString(R.string.ecg_detail_usable) to res.getString(R.string.value_seconds_percent, m.usableSec, m.usablePercent))
        add(res.getString(R.string.ecg_detail_noise) to noise(res, m))
        if (m.leadOffSec > 0f) add(res.getString(R.string.ecg_detail_lead_off) to res.getString(R.string.value_seconds_1, m.leadOffSec))
        m.averageBpm?.let { add(res.getString(R.string.ecg_avg_hr) to res.getString(R.string.ecg_bpm_value, it)) }
        if (m.minBpm != null && m.maxBpm != null) add(res.getString(R.string.ecg_detail_range) to res.getString(R.string.value_bpm_range, m.minBpm, m.maxBpm))
        add(res.getString(R.string.ecg_detail_beats) to "${m.beats}")
        m.meanRrMs?.let { add(res.getString(R.string.ecg_detail_rr) to res.getString(R.string.value_ms, it)) }
        m.sdnnMs?.let { add(res.getString(R.string.ecg_detail_sdnn) to res.getString(R.string.value_ms, it)) }
        m.rmssdMs?.let { add(res.getString(R.string.ecg_detail_rmssd) to res.getString(R.string.value_ms, it)) }
        add(res.getString(R.string.ecg_detail_quality) to "${m.qualityScore}/100")
        if (m.poorReason != EcgPoorReason.NONE) add(res.getString(R.string.ecg_detail_why) to res.getString(reason(m.poorReason)))
        add(res.getString(R.string.ecg_detail_sampling) to res.getString(R.string.value_hz, m.sampleRateHz.roundToInt()))
    }

    private fun noise(res: Resources, m: EcgMetrics): String {
        val parts = buildList {
            if (m.motionSec > 0f) add(res.getString(R.string.ecg_noise_motion, m.motionSec.roundToInt()))
            if (m.muscleNoiseSec > 0f) add(res.getString(R.string.ecg_noise_muscle, m.muscleNoiseSec.roundToInt()))
        }
        val total = res.getString(R.string.value_seconds_1, m.noiseSec)
        return if (parts.isEmpty()) total else "$total (${parts.joinToString(", ")})"
    }

    fun reason(r: EcgPoorReason): Int = when (r) {
        EcgPoorReason.MOTION -> R.string.ecg_poor_motion
        EcgPoorReason.MUSCLE_NOISE -> R.string.ecg_poor_muscle
        EcgPoorReason.LOW_AMPLITUDE -> R.string.ecg_poor_low
        EcgPoorReason.LEAD_OFF -> R.string.ecg_poor_lead_off
        EcgPoorReason.TOO_SHORT -> R.string.ecg_poor_short
        EcgPoorReason.TOO_FEW_BEATS, EcgPoorReason.NONE -> R.string.ecg_poor_beats
    }
}
