// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared

import com.heartline.shared.ecg.EcgAnalyzer
import com.heartline.shared.ecg.EcgModels
import com.heartline.shared.ecg.EcgMorphology
import com.heartline.shared.ecg.EcgSession
import com.heartline.shared.ecg.EctopicBeats
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

/** Algorithms 3 and 4: unusual rhythms and shapes are findings, not noise. */
class EcgAlgorithm3Test {
    private val fs = 500

    /**
     * Beat-by-beat synthetic lead I at wrist amplitude. [beats]: (RR before the beat in s, ventricular?).
     * Normal beats have a P wave and a narrow QRS; ventricular ones no P, a wide, taller QRS and an
     * opposite T. [tWave] scales the T wave (tall T waves tempt detectors to count them).
     */
    private fun ecg(
        beats: List<Pair<Double, Boolean>>,
        tWave: Double = 0.25,
        noise: Double = 0.01,
        seed: Int = 1,
        pr: Double = 0.16
    ): FloatArray {
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
                        g(t, t0 - pr, 0.025, 0.06) + g(t, t0 - 0.02, 0.008, -0.05) + g(t, t0, 0.01, 0.45) + g(t, t0 + 0.025, 0.01, -0.1) +
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
        assertEquals(EcgAnalyzer.ALGORITHM, a.metrics.algorithm)
        assertEquals("${a.metrics}", EcgNote.NONE, a.metrics.note)
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
        // Algorithm 4: if the rhythm is named at all, it is frequent extra beats from the lower chambers.
        if (a.result != EcgResult.INCONCLUSIVE) {
            assertEquals("${a.metrics}", EcgResult.FREQUENT_EXTRA_BEATS, a.result)
            assertTrue("${a.metrics}", a.metrics.note == EcgNote.EXTRA_BEATS_LOWER || a.metrics.note == EcgNote.EXTRA_BEATS_BOTH)
        }
    }

    @Test
    fun earlyNarrowBeatsAreFromTheUpperChambers() {
        // Early beats of the normal shape, with their own P wave, that reset the rhythm (no full pause).
        val beats = sinus(38).toMutableList()
        for (i in listOf(8, 20, 31)) {
            beats[i] = 0.55 to false
            beats[i + 1] = 0.9 to false
        }
        val a = EcgAnalyzer.analyze(ecg(beats), fs, EcgSession())
        assertNotEquals("${a.metrics}", EcgResult.AFIB_SIGNS, a.result)
        assertEquals("${a.metrics}", 0, a.metrics.ventricularBeats)
        assertTrue("${a.metrics}", a.metrics.supraventricularBeats >= 2)
        if (a.result == EcgResult.SINUS_RHYTHM) assertEquals("${a.metrics}", EcgNote.EXTRA_BEATS_UPPER, a.metrics.note)
    }

    @Test
    fun aRegularSlowOrSomewhatFastRhythmWithPWavesIsNamedSinus() {
        val slow = EcgAnalyzer.analyze(ecg(sinus(24, rr = 1.35)), fs, EcgSession())
        assertEquals("${slow.metrics}", EcgResult.LOW_HEART_RATE, slow.result)
        assertEquals("${slow.metrics}", EcgNote.SINUS_SLOW, slow.metrics.note)
        val fast = EcgAnalyzer.analyze(ecg(sinus(54, rr = 0.55), tWave = 0.2), fs, EcgSession())
        assertEquals("${fast.metrics}", EcgResult.INCONCLUSIVE, fast.result)
        assertEquals("${fast.metrics}", EcgNote.SINUS_FAST, fast.metrics.note)
        // Above 120 bpm flutter and SVT can look the same on one lead: no "sinus" there.
        val faster = EcgAnalyzer.analyze(ecg(sinus(64, rr = 0.46), tWave = 0.15), fs, EcgSession())
        assertEquals("${faster.metrics}", EcgResult.HIGH_HEART_RATE, faster.result)
        assertNotEquals("${faster.metrics}", EcgNote.SINUS_FAST, faster.metrics.note)
    }

    @Test
    fun aLongPrIntervalIsNotedOnlyWhenItIsLong() {
        val usual = EcgAnalyzer.analyze(ecg(sinus(36)), fs, EcgSession())
        assertEquals("${usual.metrics}", false, usual.metrics.longPr)
        val long = EcgAnalyzer.analyze(ecg(sinus(36, rr = 0.95), pr = 0.30), fs, EcgSession())
        assertEquals("${long.metrics}", EcgResult.SINUS_RHYTHM, long.result)
        assertEquals("${long.metrics}", true, long.metrics.longPr)
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
        // Algorithm 4: the beat model names them (wide, early, no P wave: from the lower chambers).
        assertEquals("${a.metrics}", 3, a.metrics.ventricularBeats)
        assertEquals("${a.metrics}", 0, a.metrics.supraventricularBeats)
        assertEquals("${a.result} ${a.metrics}", EcgNote.EXTRA_BEATS_LOWER, a.metrics.note)
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

    @Test
    fun theAlgorithm4ModelsLoadAndMatchTheirFeatures() {
        assertEquals(EctopicBeats.NAMES, EctopicBeats.bundled!!.features)
        assertEquals(listOf("N", "S", "V"), EctopicBeats.bundled!!.classes)
        assertEquals(RhythmFeatures.NAMES, EcgModels.sinusBeneath!!.features)
        assertEquals(EcgMorphology.FEATURES, EcgModels.longPr!!.features)
        assertNotNull(RhythmModel.bundled!!.thresholds["sinus_beneath"])
    }
}
