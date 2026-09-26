// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.sensor

import com.heartline.shared.sample.SyntheticPpg
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Green PPG samples at 100 Hz; [contact] is false while the sensor reports poor skin contact.
 * [ir] and [red] are the other wavelengths when the watch delivers them (same length as
 * [samples], NaN where a point had no value). [points] is every data point exactly as the SDK
 * gave it, for the raw session log.
 */
class PpgChunk(
    val samples: FloatArray,
    val contact: Boolean,
    val ir: FloatArray? = null,
    val red: FloatArray? = null,
    val points: List<PpgPoint> = emptyList(),
)

/** One SDK data point; NaN / -1 for a channel or status it didn't carry. [timestampMs] as given by the SDK. */
data class PpgPoint(
    val timestampMs: Long,
    val green: Float,
    val ir: Float,
    val red: Float,
    val greenStatus: Int,
    val irStatus: Int,
    val redStatus: Int,
)

/** PPG_ON_DEMAND (green), used for blood pressure. */
interface PpgSource {
    val sampleRateHz: Int get() = 100

    fun stream(): Flow<PpgChunk>
}

class FakePpgSource(private val heartRateBpm: Double = 68.0, private val stiffness: Double = 0.5, private val chunkDelayMs: Long = 50) : PpgSource {
    override fun stream(): Flow<PpgChunk> = flow {
        val signal = SyntheticPpg.generate(40.0, heartRateBpm, stiffness, seed = System.nanoTime().toInt())
        var offset = 0
        while (offset + CHUNK <= signal.size) {
            emit(PpgChunk(signal.copyOfRange(offset, offset + CHUNK), contact = true))
            offset += CHUNK
            delay(chunkDelayMs)
        }
    }

    private companion object {
        const val CHUNK = 5
    }
}
