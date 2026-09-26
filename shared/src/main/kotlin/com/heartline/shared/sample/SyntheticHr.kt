// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.sample

import com.heartline.shared.hr.HrSample
import kotlin.random.Random

/** 1 Hz HEART_RATE_CONTINUOUS-like samples with the IBIs of the beats in each second. */
object SyntheticHr {
    fun samples(
        startMs: Long,
        seconds: Int,
        bpm: Double = 64.0,
        irregularity: Double = 0.03,
        seed: Int = 1,
        moving: Boolean = false
    ): List<HrSample> {
        val random = Random(seed)
        val out = mutableListOf<HrSample>()
        var beatMs = startMs.toDouble()
        var pending = mutableListOf<Int>()
        var second = 0
        val meanRr = 60_000.0 / bpm
        while (second < seconds) {
            val rr = meanRr * (1 + irregularity * (random.nextDouble() * 2 - 1))
            beatMs += rr
            val beatSecond = ((beatMs - startMs) / 1000).toInt()
            while (second < beatSecond && second < seconds) {
                val recent = pending.ifEmpty { listOf(meanRr.toInt()) }
                out += HrSample(startMs + second * 1000L, (60_000 / recent.average()).toInt(), pending, moving = moving)
                pending = mutableListOf()
                second++
            }
            pending += rr.toInt()
        }
        return out
    }
}
