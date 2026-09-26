// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared

import com.heartline.shared.ecg.EcgAnalyzer
import com.heartline.shared.ecg.EcgSession
import com.heartline.shared.ecg.RPeakDetector
import com.heartline.shared.ecg.RhythmFeatures
import com.heartline.shared.ecg.RhythmModel
import com.heartline.shared.model.EcgNote
import com.heartline.shared.model.EcgPoorReason
import com.heartline.shared.model.EcgResult
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Algorithm 3: unusual rhythms and shapes are findings, not noise. */
class EcgAlgorithm3Test {
    private val fs = 500

    /**
     * Beat-by-beat synthetic lead I at wrist amplitude. [beats]: (RR before the beat in s, ventricular?).
     * Normal beats have a P wave and a narrow QRS; ventricular ones no P, a wide, taller QRS and an
     * opposite T. [tWave] scales the T wave (tall T waves tempt detectors to count them).
     */
    private fun ecg(beats: List<Pair<Double, Boolean>>, tWave: Double = 0.25, noise: Double = 0.01, seed: Int = 1): FloatArray {
        val total = beats.sumOf { it.first } + 1.0
        val out = FloatArray((total * fs).toInt())
        val r = Random(seed)
        fun g(t: Double, c: Double, w: Double, a: Double) = a * exp(-0.5 * ((t - c) / w).let { it * it })
        var t0 = 0.0
        for ((rr, ventricular) in beats) {
            t0 += rr
            val from = ((t0 - 0.4) * fs).toInt().coerceAtLeast(0)
            val to = ((t0 + 0.6) * fs).toInt().coerceAtMost(out.size - 1)
            for (i in from..to) {
                val t = i.toDouble() / fs
                out[i] += if (ventricular) {
                    (g(t, t0, 0.035, 0.9) + g(t, t0 + 0.06, 0.03, -0.3) + g(t, t0 + 0.3, 0.07, -tWave)).toFloat()
                } else {
                    (
                        g(t, t0 - 0.16, 0.025, 0.06) + g(t, t0 - 0.02, 0.008, -0.05) + g(t, t0, 0.01, 0.45) + g(t, t0 + 0.025, 0.01, -0.1) +
                            g(t, t0 + 0.26, 0.045, tWave)
                        ).toFloat()
                }
            }
        }
        for (i in out.indices) out[i] += (noise * (r.nextDouble() * 2 - 1) + 0.05 * sin(2 * PI * 0.25 * i / fs)).toFloat()
        return out
    }

    private fun sinus(n: Int, rr: Double = 0.85) = List(n) { rr to false }

    @Test
    fun sinusIsSinus() {
        val a = EcgAnalyzer.analyze(ecg(sinus(36)), fs, EcgSession())
        assertEquals("${a.metrics}", EcgResult.SINUS_RHYTHM, a.result)
        assertEquals(3, a.metrics.algorithm)
    }

    @Test
    fun ventricularBigeminyIsNotAfibAndNotNoise() {
        // N V N V …: short coupling, long compensatory pause.
        val beats = List(40) { i -> if (i % 2 == 1) 0.55 to true else 1.15 to false }
        val a = EcgAnalyzer.analyze(ecg(beats), fs, EcgSession())
        assertNotEquals("${a.metrics}", EcgResult.AFIB_SIGNS, a.result)
        assertNotEquals("${a.metrics}", EcgResult.POOR_RECORDING, a.result)
        assertEquals(EcgPoorReason.NONE, a.metrics.poorReason)
        assertTrue("${a.metrics}", a.metrics.ectopicBeats >= 10)
        assertTrue("${a.metrics}", a.metrics.ventricularLikeBeats >= 10)
    }

    @Test
    fun occasionalExtraBeatsDontMakeItPoorOrAfib() {
        val beats = sinus(38).toMutableList()
        for (i in listOf(8, 20, 31)) {
            beats[i] = 0.5 to true
            beats[i + 1] = 1.2 to false
        }
        val a = EcgAnalyzer.analyze(ecg(beats), fs, EcgSession())
        assertTrue("${a.result} ${a.metrics}", a.result == EcgResult.SINUS_RHYTHM || a.result == EcgResult.INCONCLUSIVE)
        assertEquals(3, a.metrics.ectopicBeats)
        assertTrue(a.metrics.note == EcgNote.EXTRA_BEATS || a.metrics.note == EcgNote.FREQUENT_EXTRA_BEATS)
    }

    @Test
    fun aPauseIsAFindingNotLostContact() {
        val beats = sinus(18) + listOf(2.8 to false) + sinus(16)
        val a = EcgAnalyzer.analyze(ecg(beats), fs, EcgSession())
        assertEquals("${a.metrics}", EcgPoorReason.NONE, a.metrics.poorReason)
        assertEquals(1, a.metrics.pauses)
        assertEquals(2800.0, a.metrics.longestPauseMs!!.toDouble(), 40.0)
        assertTrue("usable ${a.metrics.usableSec}", a.metrics.usableSec >= 25f)
    }

    @Test
    fun tallTWavesAreNotCountedAsBeats() {
        val x = ecg(sinus(36, rr = 0.8), tWave = 0.55)
        val peaks = RPeakDetector.detect(com.heartline.shared.ecg.EcgFilter.clean(x, fs), fs)
        assertEquals(36.0, peaks.size.toDouble(), 1.0)
        val a = EcgAnalyzer.analyze(x, fs, EcgSession())
        assertEquals("${a.metrics} ${a.seconds.map { it.first }}", 75, a.averageBpm)
    }

    @Test
    fun noIntervalIsMeasuredAcrossASplice() {
        // Two stretches joined mid-beat, as the recorder does after a lift: without segments the
        // joint makes a bogus short interval.
        val first = ecg(sinus(18), seed = 2)
        val second = ecg(sinus(18), seed = 3)
        val cut = first.size - (0.4 * fs).toInt()
        val joined = first.copyOf(cut) + second.copyOfRange((0.1 * fs).toInt(), second.size)
        val split = EcgAnalyzer.analyze(joined, fs, EcgSession(segmentStarts = listOf(0, cut)))
        assertEquals(2, split.metrics.segments)
        val rr = split.runs.flatten().map { it.ms }
        assertTrue("$rr", rr.all { it in 800.0..900.0 })
        assertEquals(EcgResult.SINUS_RHYTHM, split.result)
    }

    @Test
    fun afibIsStillAfib() {
        val r = Random(5)
        val beats = List(45) { (0.45 + 0.5 * r.nextDouble()) to false }
        val x = ecg(beats).also { s -> for (i in s.indices) s[i] += (0.02 * sin(2 * PI * 6.3 * i / fs)).toFloat() }
        val a = EcgAnalyzer.analyze(x, fs, EcgSession())
        assertTrue("${a.result} ${a.metrics}", a.result == EcgResult.AFIB_SIGNS || a.result == EcgResult.INCONCLUSIVE)
    }

    @Test
    fun theBundledModelLoadsAndMatchesTheFeatures() {
        assertNotNull(RhythmModel.bundled)
        val model = RhythmModel.bundled!!
        assertEquals(RhythmFeatures.NAMES, model.features)
        assertEquals(listOf("normal", "af", "other", "noisy"), model.classes)
        val a = EcgAnalyzer.analyze(ecg(sinus(36)), fs, EcgSession())
        assertEquals(RhythmFeatures.NAMES.size, a.features.size)
        val p = a.evidence.modelProbabilities!!
        assertEquals(1.0, p.sum(), 1e-9)
        assertTrue("$p", p[0] > 0.5)
    }
}
