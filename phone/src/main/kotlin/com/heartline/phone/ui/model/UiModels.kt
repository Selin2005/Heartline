// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

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
    /** Greeting on top of Home: the user's name (when set) and the part of the day. */
    val name: String? = null,
    val dayPart: com.heartline.shared.profile.DayPart = com.heartline.shared.profile.DayPart.MORNING,
    val birthday: Boolean = false,
)
