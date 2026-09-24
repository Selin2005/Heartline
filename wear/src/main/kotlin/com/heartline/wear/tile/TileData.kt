package com.heartline.wear.tile

import com.heartline.shared.model.RecordMeta
import com.heartline.shared.model.RecordSummary
import com.heartline.wear.ui.LauncherViewModel

/** What the Tiles show; built from local history so tiles work without the phone. */
data class TileData(val heartRate: Int?, val lastEcg: String?, val lastBp: String?, val bpDaysLeft: Int?) {
    companion object {
        fun from(recent: List<RecordMeta>, heartRate: Int?, bpDaysLeft: Int?, ecgLabel: (RecordSummary.Ecg) -> String?): TileData {
            val ecg = recent.firstOrNull { it.summary is RecordSummary.Ecg }
            val bp = recent.firstOrNull { it.summary is RecordSummary.BloodPressure }
            return TileData(
                heartRate = heartRate,
                lastEcg = ecg?.let { ecgLabel(it.summary as RecordSummary.Ecg) },
                lastBp = bp?.let(LauncherViewModel::lastValue),
                bpDaysLeft = bpDaysLeft,
            )
        }
    }
}
