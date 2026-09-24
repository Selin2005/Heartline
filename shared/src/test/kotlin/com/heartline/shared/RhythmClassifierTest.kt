package com.heartline.shared

import com.heartline.shared.ecg.EcgAnalyzer
import com.heartline.shared.model.EcgResult
import com.heartline.shared.sample.SyntheticEcg
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

class RhythmClassifierTest {
    private val fs = SyntheticEcg.SAMPLE_RATE_HZ

    private fun classify(bpm: Double, irregular: Double = 0.02, noise: Double = 0.015, seed: Int = 1, leadOff: Float = 0f) =
        EcgAnalyzer.analyze(SyntheticEcg.generate(30.0, bpm, irregular, noise, seed), fs, leadOff).result

    @Test
    fun regularNormalRateIsSinus() {
        for (seed in 1..5) assertEquals(EcgResult.SINUS_RHYTHM, classify(72.0, seed = seed))
        assertEquals(EcgResult.SINUS_RHYTHM, classify(58.0))
        assertEquals(EcgResult.SINUS_RHYTHM, classify(95.0))
    }

    @Test
    fun irregularWithoutPWavesIsAfib() {
        for (seed in 1..5) assertEquals("seed $seed", EcgResult.AFIB_SIGNS, classify(90.0, irregular = 0.3, seed = seed))
    }

    @Test
    fun heartRateBounds() {
        assertEquals(EcgResult.HIGH_HEART_RATE, classify(135.0))
        assertEquals(EcgResult.LOW_HEART_RATE, classify(44.0))
        assertEquals(EcgResult.INCONCLUSIVE, classify(110.0))
    }

    @Test
    fun noisyOrDetachedRecordingsArePoor() {
        assertEquals(EcgResult.POOR_RECORDING, classify(72.0, leadOff = 0.5f))
        val random = Random(3)
        val noise = FloatArray(15_000) { (random.nextGaussian() * 0.4).toFloat() }
        assertEquals(EcgResult.POOR_RECORDING, EcgAnalyzer.analyze(noise, fs, 0f).result)
    }

    private fun Random.nextGaussian(): Double {
        val u = nextDouble().coerceAtLeast(1e-12)
        val v = nextDouble()
        return kotlin.math.sqrt(-2 * kotlin.math.ln(u)) * kotlin.math.cos(2 * Math.PI * v)
    }
}
