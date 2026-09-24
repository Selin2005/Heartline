package com.heartline.shared

import com.heartline.shared.dsp.Biquad
import com.heartline.shared.dsp.filtFilt
import com.heartline.shared.ecg.EcgFilter
import com.heartline.shared.ecg.EcgRecorder
import com.heartline.shared.ecg.RPeakDetector
import com.heartline.shared.sample.SyntheticEcg
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EcgProcessingTest {
    private val fs = SyntheticEcg.SAMPLE_RATE_HZ

    private fun rms(x: FloatArray) = sqrt(x.map { it.toDouble() * it }.average())

    private fun sine(hz: Double, seconds: Double = 4.0) = FloatArray((seconds * fs).toInt()) { sin(2 * PI * hz * it / fs).toFloat() }

    @Test
    fun notchRemovesMainsButKeepsSignalBand() {
        val notched = sine(50.0).filtFilt(Biquad.notch(50.0, fs.toDouble()))
        assertTrue(rms(notched.copyOfRange(fs, 3 * fs)) < 0.05)
        val kept = sine(10.0).filtFilt(Biquad.notch(50.0, fs.toDouble()))
        assertTrue(rms(kept.copyOfRange(fs, 3 * fs)) > 0.65)
    }

    @Test
    fun cleanRemovesBaselineWander() {
        val drift = FloatArray(10 * fs) { (0.8 * sin(2 * PI * 0.1 * it / fs)).toFloat() }
        val cleaned = EcgFilter.clean(drift, fs)
        assertTrue(rms(cleaned.copyOfRange(2 * fs, 8 * fs)) < 0.1)
    }

    @Test
    fun detectsEveryBeatAcrossHeartRates() {
        for (bpm in listOf(45.0, 72.0, 110.0, 150.0)) {
            val signal = EcgFilter.clean(SyntheticEcg.generate(30.0, bpm, irregularity = 0.0, noiseMv = 0.03, seed = bpm.toInt()), fs)
            val peaks = RPeakDetector.detect(signal, fs)
            val expected = 30.0 * bpm / 60.0
            assertTrue("bpm=$bpm peaks=${peaks.size} expected≈$expected", abs(peaks.size - expected) <= 2)
            val hr = RPeakDetector.heartRateBpm(peaks, fs)
            assertNotNull(hr)
            assertTrue("bpm=$bpm hr=$hr", abs(hr!! - bpm) <= 3)
        }
    }

    @Test
    fun recorderSkipsLeadOffAndCompletes() {
        val recorder = EcgRecorder(sampleRateHz = fs, targetSeconds = 2, settleSeconds = 0.5)
        // Before the first touch: not counted as lead-off.
        recorder.accept(FloatArray(fs), leadOff = true)
        assertEquals(0f, recorder.progress)
        assertEquals(0f, recorder.leadOffRatio)
        assertTrue(recorder.leadOff)
        // Touch: the first 0.5 s settles and isn't recorded.
        recorder.accept(FloatArray(fs / 2) { 9f }, leadOff = false)
        assertTrue(recorder.progress == 0f)
        repeat(2) { recorder.accept(FloatArray(fs / 2) { 1f }, leadOff = false) }
        // Contact lost for 1 s after touching: counted.
        recorder.accept(FloatArray(fs), leadOff = true)
        repeat(3) { recorder.accept(FloatArray(fs / 2) { 1f }, leadOff = false) }
        assertTrue(recorder.isComplete)
        assertEquals(2 * fs, recorder.recording().size)
        assertTrue(recorder.recording().all { it == 1f })
        assertEquals(1f / 3f, recorder.leadOffRatio, 0.01f)
        assertFalse(recorder.isAbandoned)
    }

    @Test
    fun recorderAbandonsAfterLongLeadOff() {
        val recorder = EcgRecorder(sampleRateHz = fs, maxLeadOffSeconds = 3)
        repeat(5) { recorder.accept(FloatArray(fs), leadOff = true) }
        assertFalse("waiting for the first touch isn't abandonment", recorder.isAbandoned)
        recorder.accept(FloatArray(fs), leadOff = false)
        repeat(3) { recorder.accept(FloatArray(fs), leadOff = true) }
        assertTrue(recorder.isAbandoned)
    }
}
