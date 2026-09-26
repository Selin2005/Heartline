// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared

import com.heartline.shared.dsp.StreamingEcgFilter
import com.heartline.shared.sample.SyntheticEcg
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingFilterTest {
    private val fs = SyntheticEcg.SAMPLE_RATE_HZ

    @Test
    fun removesOffsetAndKeepsQrs() {
        val ecg = SyntheticEcg.generate(6.0, 72.0, noiseMv = 0.0)
        val shifted = FloatArray(ecg.size) { ecg[it] + 2.5f }
        val filter = StreamingEcgFilter(fs)
        // Fed in SDK-sized chunks of 10.
        val out = shifted.toList().chunked(10).flatMap { filter.process(it.toFloatArray()).toList() }
        val tail = out.drop(2 * fs)
        val median = tail.sorted()[tail.size / 2]
        assertTrue("baseline $median", abs(median) < 0.1f)
        assertTrue("QRS kept: ${tail.max()}", tail.max() > 0.6f * ecg.max())
    }

    @Test
    fun touchStepDoesNotRing() {
        val ecg = SyntheticEcg.generate(4.0, 72.0, noiseMv = 0.0)
        val filter = StreamingEcgFilter(fs)
        filter.process(FloatArray(fs) { 0f })
        // Finger lands: level jumps by 5 mV.
        val out = filter.process(FloatArray(ecg.size) { ecg[it] + 5f })
        val afterHalfSecond = out.drop(fs / 2)
        assertTrue("max ${afterHalfSecond.maxOf { abs(it) }}", afterHalfSecond.maxOf { abs(it) } < 1.5f * ecg.maxOf { abs(it) })
    }
}
