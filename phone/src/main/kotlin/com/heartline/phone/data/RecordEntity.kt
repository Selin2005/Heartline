package com.heartline.phone.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.heartline.shared.model.RecordKind

/** One measurement of any kind; the kind-specific values live in [summaryJson] (RecordSummary). */
@Entity(tableName = "records", indices = [Index("kind", "startedAtMs")])
data class RecordEntity(
    @PrimaryKey val id: String,
    val kind: RecordKind,
    val startedAtMs: Long,
    val durationMs: Long,
    val sampleRateHz: Int,
    val sampleCount: Int,
    val summaryJson: String,
    /** Relative path under filesDir of the Float32 waveform, or null. */
    val wavePath: String?,
    val note: String? = null,
    val receivedAtMs: Long,
)
