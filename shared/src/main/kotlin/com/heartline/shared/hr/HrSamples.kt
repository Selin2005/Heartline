// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.hr

/** Turns one HEART_RATE_CONTINUOUS reading into an [HrSample] (shared by the watch and log replays). */
object HrSamples {
    const val STATUS_SUCCESS = 1
    const val STATUS_OFF_BODY = -3

    /**
     * Status 1 is a good reading; others mean the tracker is still searching, the signal is weak,
     * the arm moves or the watch is off the wrist. Their "intervals" are noise, and off-wrist noise
     * looks just like an irregular rhythm, so they are never used. IBI status 0 marks a reliable
     * interval. Without a status list at all (older trackers) a good reading's intervals are kept;
     * with one, an interval it doesn't cover is not trusted.
     */
    fun fromTracker(tsMs: Long, bpm: Int, status: Int, ibis: List<Int>, ibiStatus: List<Int>): HrSample {
        val reliable = status == STATUS_SUCCESS
        val good = when {
            !reliable -> emptyList()
            ibiStatus.isEmpty() -> ibis
            else -> ibis.filterIndexed { i, _ -> ibiStatus.getOrNull(i) == 0 }
        }
        return HrSample(
            tsMs = tsMs,
            bpm = bpm,
            ibiMs = good,
            onBody = status != STATUS_OFF_BODY,
            reliable = reliable,
            rejectedIbis = ibis.size - good.size
        )
    }
}
