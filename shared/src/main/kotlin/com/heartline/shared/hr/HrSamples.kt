// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.hr

/** Turns one HEART_RATE_CONTINUOUS reading into an [HrSample] (shared by the watch and log replays). */
object HrSamples {
    const val STATUS_SUCCESS = 1
    const val STATUS_OFF_BODY = -3

    /** The tracker's first reading after it starts: no rate of its own yet. */
    const val STATUS_INITIAL = 0

    /** Beats at least in a held batch, and the share of them the tracker must trust ([isPackedFirstReading]). */
    const val PACKED_BEATS = 20
    const val PACKED_TRUSTED = 0.9

    /**
     * With the screen off the tracker holds its beats and hands them over on the window's first
     * reading, status 0 and no rate: in a real log 463 beats, 93 % of them marked reliable by the
     * tracker itself, were all thrown away for that reading's status. Such a batch counts when the
     * tracker trusted at least [PACKED_TRUSTED] of its beats; status −10 batches never do (their
     * beats were the noise behind old false notices).
     */
    fun isPackedFirstReading(status: Int, ibis: List<Int>, ibiStatus: List<Int>): Boolean {
        if (status != STATUS_INITIAL || ibis.size < PACKED_BEATS || ibiStatus.size != ibis.size) return false
        return ibiStatus.count { it == 0 } >= PACKED_TRUSTED * ibis.size
    }

    /**
     * Status 1 is a good reading (and so is a trusted held batch, [isPackedFirstReading]); others
     * mean the tracker is still searching, the signal is weak, the arm moves or the watch is off
     * the wrist. Their "intervals" are noise, and off-wrist noise
     * looks just like an irregular rhythm, so they are never used. IBI status 0 marks a reliable
     * interval. Without a status list at all (older trackers) a good reading's intervals are kept;
     * with one, an interval it doesn't cover is not trusted.
     */
    fun fromTracker(tsMs: Long, bpm: Int, status: Int, ibis: List<Int>, ibiStatus: List<Int>): HrSample {
        val packed = isPackedFirstReading(status, ibis, ibiStatus)
        val reliable = status == STATUS_SUCCESS || packed
        val ok = ibis.indices.map { i -> reliable && (ibiStatus.isEmpty() || ibiStatus.getOrNull(i) == 0) }
        val good = ibis.filterIndexed { i, _ -> ok[i] }
        return HrSample(
            tsMs = tsMs,
            bpm = if (packed && bpm <= 0) 60_000 / good.sorted()[good.size / 2] else bpm,
            ibiMs = good,
            onBody = status != STATUS_OFF_BODY,
            reliable = reliable,
            rejectedIbis = ibis.size - good.size,
            rawIbiMs = ibis,
            rawIbiOk = ok
        )
    }
}
