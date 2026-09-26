// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared

import com.heartline.shared.ecg.EcgAnalyzer
import com.heartline.shared.ecg.EcgSession
import com.heartline.shared.model.EcgPoorReason
import com.heartline.shared.model.EcgResult
import com.heartline.shared.sample.SyntheticEcg
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wrist-ECG conditions the plain synthetic signal lacks: small amplitudes (lead I on a watch),
 * the electrode settling transient, baseline wander, a DC offset, dropped samples, noise bursts.
 */
class EcgRealisticTest {
    private val fs = SyntheticEcg.SAMPLE_RATE_HZ

    private fun wrist(
        seconds: Double = 30.0,
        bpm: Double = 72.0,
        scale: Double = 0.4,
        noiseMv: Double = 0.03,
        stepMv: Double = 4.0,
        seed: Int = 4,
        irregular: Double = 0.02
    ): FloatArray {
        val base = SyntheticEcg.generate(seconds, bpm, irregular, noiseMv = 0.0, seed = seed)
        val r = Random(seed)
        return FloatArray(base.size) { i ->
            val t = i.toDouble() / fs
            (base[i] * scale + noiseMv * r.gauss() + 0.35 + stepMv * exp(-t / 0.35) + 0.12 * sin(2 * PI * 0.3 * t)).toFloat()
        }
    }

    private fun Random.gauss(): Double =
        sqrt(-2 * kotlin.math.ln(nextDouble().coerceAtLeast(1e-12))) * kotlin.math.cos(2 * PI * nextDouble())

    @Test
    fun smallWristSignalWithSettlingIsSinus() {
        for (seed in 1..5) {
            val a = EcgAnalyzer.analyze(wrist(seed = seed), fs, EcgSession())
            assertEquals("seed $seed ${a.metrics}", EcgResult.SINUS_RHYTHM, a.result)
            assertEquals(72.0, a.averageBpm!!.toDouble(), 3.0)
        }
    }

    @Test
    fun metricsDescribeTheRecording() {
        val a = EcgAnalyzer.analyze(wrist(), fs, EcgSession(startedAtMs = 1_000, endedAtMs = 33_000, leadOffSec = 2f))
        val m = a.metrics
        assertEquals(30f, m.durationSec, 0.01f)
        assertTrue("usable ${m.usableSec}", m.usableSec >= 28f)
        assertEquals(EcgPoorReason.NONE, m.poorReason)
        assertTrue(m.minBpm!! <= 72 && m.maxBpm!! >= 72 && m.maxBpm!! - m.minBpm!! < 12)
        assertEquals(36.0, m.beats.toDouble(), 2.0)
        assertTrue(m.qualityScore >= 85)
        assertEquals(2f, m.leadOffSec)
        assertEquals(1_000L, m.startedAtMs)
    }

    @Test
    fun motionBurstIsLocatedAndExcluded() {
        val signal = wrist()
        val r = Random(9)
        // 6 s of arm movement in the middle.
        for (i in 10 * fs until 16 * fs) signal[i] += (3.0 * sin(2 * PI * 1.3 * i / fs) + 0.8 * r.gauss()).toFloat()
        val m = EcgAnalyzer.analyze(signal, fs, EcgSession()).metrics
        assertTrue("noisy ${m.noisySeconds}", (10..15).all { it in m.noisySeconds })
        assertTrue("usable ${m.usableSec}", m.usableSec in 20f..25f)
        assertEquals(EcgPoorReason.NONE, m.poorReason)
    }

    @Test
    fun buriedBeatsArePoorWithAReason() {
        val m = EcgAnalyzer.analyze(wrist(scale = 0.15, noiseMv = 0.15), fs, EcgSession()).metrics
        assertTrue(m.poorReason != EcgPoorReason.NONE)
        assertTrue(m.usableSec < EcgAnalyzer.MIN_USABLE_SEC || m.poorReason == EcgPoorReason.TOO_FEW_BEATS)
    }

    @Test
    fun droppedSamplesDoNotBreakAnalysis() {
        val signal = wrist()
        for (i in signal.indices step 97) signal[i] = Float.NaN
        assertEquals(EcgResult.SINUS_RHYTHM, EcgAnalyzer.analyze(signal, fs, EcgSession()).result)
    }

    @Test
    fun invertedLeadsAreStillSinus() {
        val signal = wrist().map { -it }.toFloatArray()
        val a = EcgAnalyzer.analyze(signal, fs, EcgSession())
        assertTrue(a.metrics.inverted)
        assertEquals(72.0, a.averageBpm!!.toDouble(), 3.0)
    }

    @Test
    fun smallAmplitudeAfibIsStillAfib() {
        for (seed in 1..3) {
            assertEquals(
                EcgResult.AFIB_SIGNS,
                EcgAnalyzer.analyze(wrist(bpm = 90.0, irregular = 0.3, seed = seed), fs, EcgSession()).result
            )
        }
    }

    @Test
    fun hrRangeIgnoresASingleEctopicBeat() {
        val rr = List(30) { 833.0 }.toMutableList().also { it[10] = 450.0 }
        val (min, max) = EcgAnalyzer.hrRange(rr)
        assertEquals(72, min)
        assertEquals(72, max)
    }
}
