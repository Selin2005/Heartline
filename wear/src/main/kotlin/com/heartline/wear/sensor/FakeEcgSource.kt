// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.sensor

import com.heartline.shared.sample.SyntheticEcg
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Plays a synthetic ECG in real time: 10 samples every 20 ms like the SDK, with lead-off for the
 * first second (finger not yet on the key).
 */
class FakeEcgSource(
    private val heartRateBpm: Double = 72.0,
    private val irregularity: Double = 0.02,
    private val chunkDelayMs: Long = 20,
    private val leadOffChunks: Int = 50,
    /** Contact lost for this many chunks once 5 s have been recorded. */
    private val midLeadOffChunks: Int = 0,
) : EcgSource {
    override fun stream(): Flow<EcgChunk> = flow {
        val signal = SyntheticEcg.generate(40.0, heartRateBpm, irregularity, seed = System.nanoTime().toInt())
        var chunkIndex = 0
        var offset = 0
        while (offset + CHUNK <= signal.size) {
            val recorded = chunkIndex - leadOffChunks
            val midStart = 5 * 500 / CHUNK
            val leadOff = chunkIndex < leadOffChunks || (midLeadOffChunks > 0 && recorded in midStart until midStart + midLeadOffChunks)
            emit(EcgChunk(if (leadOff) FloatArray(CHUNK) else signal.copyOfRange(offset, offset + CHUNK), leadOff))
            if (!leadOff || chunkIndex >= leadOffChunks) offset += CHUNK
            chunkIndex++
            delay(chunkDelayMs)
        }
    }

    private companion object {
        const val CHUNK = 10
    }
}
