package com.heartline.shared

import com.heartline.shared.ecg.ContactPhase
import com.heartline.shared.ecg.EcgContactCheck
import com.heartline.shared.ecg.EcgRecorder
import com.heartline.shared.sample.SyntheticEcg
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The countdown starts only on a confirmed touch with a real ECG (docs/ECG_ACCURACY_PLAN.md, phase 1). */
class EcgContactTest {
    private val fs = 500
    private val chunk = 10

    /** Feeds [signal] in SDK-sized chunks; [contact] decides the lead-off flag per chunk index. */
    private fun EcgRecorder.feed(signal: FloatArray, saturated: Boolean = false, contact: (Int) -> Boolean = { true }) {
        var i = 0
        var k = 0
        while (i + chunk <= signal.size) {
            accept(signal.copyOfRange(i, i + chunk), leadOff = !contact(k), saturated = saturated)
            i += chunk
            k++
        }
    }

    private fun ecg(seconds: Double, hr: Double = 70.0, amplitude: Float = 0.4f, seed: Int = 1) =
        SyntheticEcg.generate(seconds, hr, seed = seed).let { s -> FloatArray(s.size) { s[it] * amplitude } }

    @Test
    fun neverStartsWithoutAConfirmedEcg() {
        val random = Random(3)
        val scenarios = mapOf(
            "no contact" to { r: EcgRecorder -> r.feed(ecg(20.0)) { false } },
            "20 ms contact flicker" to { r: EcgRecorder -> r.feed(ecg(20.0)) { it % 5 == 0 } },
            "contact, flat signal" to { r: EcgRecorder -> r.feed(FloatArray(20 * fs)) },
            "contact, white noise" to { r: EcgRecorder -> r.feed(FloatArray(20 * fs) { (random.nextDouble() - 0.5).toFloat() }) },
            "contact, slow drift" to { r: EcgRecorder -> r.feed(FloatArray(20 * fs) { (2 * sin(2 * PI * 0.3 * it / fs)).toFloat() }) },
            "contact, mains hum" to { r: EcgRecorder -> r.feed(FloatArray(20 * fs) { (0.5 * sin(2 * PI * 50.0 * it / fs)).toFloat() }) },
            "saturated ECG" to { r: EcgRecorder -> r.feed(ecg(20.0), saturated = true) }
        )
        for ((name, run) in scenarios) {
            val recorder = EcgRecorder(fs)
            run(recorder)
            assertEquals(name, 0f, recorder.progress)
            assertTrue(name, recorder.phase != ContactPhase.RECORDING)
            assertFalse(name, recorder.isAbandoned)
        }
    }

    @Test
    fun realTouchStartsAfterDebounceSettleAndCheck() {
        val recorder = EcgRecorder(fs)
        recorder.feed(ecg(1.0)) { false }
        assertEquals(ContactPhase.WAITING, recorder.phase)
        recorder.feed(ecg(0.4, seed = 2))
        assertEquals("debouncing", ContactPhase.WAITING, recorder.phase)
        recorder.feed(ecg(2.0, seed = 3))
        assertEquals("settling and checking", ContactPhase.ARMING, recorder.phase)
        assertEquals(0f, recorder.progress)
        recorder.feed(ecg(4.0, seed = 4))
        assertEquals(ContactPhase.RECORDING, recorder.phase)
        // The verified seconds are kept.
        assertTrue(recorder.recording().size >= 3 * fs)
        assertEquals(listOf(0), recorder.segmentStarts)
    }

    @Test
    fun slowHeartStillStarts() {
        val recorder = EcgRecorder(fs)
        recorder.feed(ecg(15.0, hr = 32.0))
        assertEquals(ContactPhase.RECORDING, recorder.phase)
    }

    @Test
    fun smallWristAmplitudeStarts() {
        val recorder = EcgRecorder(fs)
        recorder.feed(ecg(8.0, amplitude = 0.15f))
        assertEquals(ContactPhase.RECORDING, recorder.phase)
    }

    @Test
    fun liftsSplitSegmentsAndLongLiftsPause() {
        val recorder = EcgRecorder(fs, targetSeconds = 20)
        recorder.feed(ecg(6.0))
        assertEquals(ContactPhase.RECORDING, recorder.phase)
        val before = recorder.recording().size
        // A 100 ms lift: no pause, but the recording is spliced there.
        recorder.feed(ecg(0.1, seed = 5)) { false }
        assertEquals(ContactPhase.RECORDING, recorder.phase)
        recorder.feed(ecg(1.0, seed = 6))
        assertEquals(listOf(0, before), recorder.segmentStarts)
        // A 1 s lift pauses; contact must be confirmed again before counting resumes.
        recorder.feed(ecg(1.0, seed = 7)) { false }
        assertEquals(ContactPhase.PAUSED, recorder.phase)
        val paused = recorder.recording().size
        recorder.feed(ecg(1.0, seed = 8))
        assertEquals(paused, recorder.recording().size)
        recorder.feed(ecg(5.0, seed = 9))
        assertEquals(ContactPhase.RECORDING, recorder.phase)
        assertEquals(3, recorder.segmentStarts.size)
        assertTrue(recorder.leadOffSeconds >= 1f)
    }

    @Test
    fun completesAndAbandons() {
        val done = EcgRecorder(fs, targetSeconds = 5)
        done.feed(ecg(12.0))
        assertTrue(done.isComplete)
        assertEquals(5 * fs, done.recording().size)

        val lost = EcgRecorder(fs, maxLeadOffSeconds = 3)
        lost.feed(ecg(20.0)) { false }
        assertFalse("waiting for the first touch isn't abandonment", lost.isAbandoned)
        lost.feed(ecg(6.0))
        lost.feed(ecg(4.0)) { false }
        assertTrue(lost.isAbandoned)
    }

    @Test
    fun contactCheckAcceptsEcgAndRejectsLookalikes() {
        assertTrue(EcgContactCheck.looksLikeEcg(ecg(3.0), fs))
        assertFalse(EcgContactCheck.looksLikeEcg(FloatArray(3 * fs) { (0.3 * sin(2 * PI * 1.2 * it / fs)).toFloat() }, fs))
        assertFalse(EcgContactCheck.looksLikeEcg(ecg(3.0, amplitude = 20f), fs))
    }
}
