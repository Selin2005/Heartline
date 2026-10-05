// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.sensor

import com.heartline.shared.hr.HrSample
import com.heartline.shared.sample.SyntheticHr
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** HEART_RATE_CONTINUOUS: ~1 Hz heart rate with inter-beat intervals. */
interface HrSource {
    fun stream(): Flow<HrSample>

    /**
     * Asks the tracker for the readings it is still holding (it sends them in batches, minutes
     * late while the screen is off). True once they were delivered; false when it can't.
     */
    suspend fun flush(): Boolean = false
}

/** Real-time synthetic heart rate for development without sensors. */
class FakeHrSource(
    private val bpm: Double = 66.0,
    private val irregularity: Double = 0.04,
    private val periodMs: Long = 1_000,
    private val now: () -> Long = System::currentTimeMillis,
) : HrSource {
    override fun stream(): Flow<HrSample> = flow {
        var seed = 1
        while (true) {
            val start = now()
            for (sample in SyntheticHr.samples(start, 60, bpm, irregularity, seed++)) {
                emit(sample.copy(tsMs = now()))
                delay(periodMs)
            }
        }
    }
}
