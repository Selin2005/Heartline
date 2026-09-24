package com.heartline.phone.ui.model

import com.heartline.shared.model.EcgMetrics
import com.heartline.shared.model.EcgResult
import com.heartline.shared.model.Metric
import com.heartline.shared.model.Symptom

/** An ECG record ready for display; [samples] is loaded only where a waveform is shown. */
data class EcgRecordUi(
    val id: String,
    val month: String,
    val date: String,
    val time: String,
    val result: EcgResult,
    val averageBpm: Int?,
    val symptoms: List<Symptom>,
    val durationSec: Int,
    val sampleRateHz: Int,
    val samples: FloatArray? = null,
    val metrics: EcgMetrics? = null,
)

data class EcgListState(val records: List<EcgRecordUi> = emptyList(), val loading: Boolean = true) {
    val latest get() = records.firstOrNull()
}

/** Latest value of a non-ECG metric for its Home tile. */
data class TileValue(val value: String, val unit: String?, val caption: String, val detail: String? = null)

data class HomeState(
    val watchName: String? = null,
    val latestEcg: EcgRecordUi? = null,
    val tiles: Map<Metric, TileValue> = emptyMap(),
    val irregularRhythmNotifications: Boolean = true,
)
