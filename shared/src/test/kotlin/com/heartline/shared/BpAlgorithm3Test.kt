package com.heartline.shared

import com.heartline.shared.bp.BpCalibration
import com.heartline.shared.bp.BpConfirmation
import com.heartline.shared.bp.BpConformal
import com.heartline.shared.bp.BpDataset
import com.heartline.shared.bp.BpDatasetEntry
import com.heartline.shared.bp.BpDrift
import com.heartline.shared.bp.BpEstimate
import com.heartline.shared.bp.BpEvaluation
import com.heartline.shared.bp.BpSafety
import com.heartline.shared.bp.CalibrationPoint
import com.heartline.shared.bp.HybridBpModel
import com.heartline.shared.bp.PpgFeatures
import com.heartline.shared.bp.PulseArrival
import com.heartline.shared.ecg.RPeakDetector
import com.heartline.shared.sample.SyntheticEcg
import com.heartline.shared.sample.SyntheticPpg
import com.heartline.shared.sync.Protocol
import kotlin.math.exp
import kotlin.random.Random
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BpAlgorithm3Test {
    private val fs = SyntheticPpg.SAMPLE_RATE_HZ
    private val day = BpCalibration.DAY_MS

    @Test
    fun safetyLevels() {
        assertEquals(BpSafety.VERY_HIGH, BpSafety.of(182, 100))
        assertEquals(BpSafety.VERY_HIGH, BpSafety.of(150, 121))
        assertEquals(BpSafety.LOW, BpSafety.of(86, 58))
        assertEquals(BpSafety.NONE, BpSafety.of(122, 78))
    }

    @Test
    fun aSecondSimilarReadingConfirmsTheFirst() {
        val first = BpEstimate(168, 98, 80, 12, 8, beyondCalibration = true, deltaSystolic = 40.0)
        val second = first.copy(systolic = 160, deltaSystolic = 33.0)
        assertTrue(BpConfirmation.confirms(first, 0, second, 5 * 60_000))
        assertFalse("too late", BpConfirmation.confirms(first, 0, second, 11 * 60_000))
        assertFalse("other way", BpConfirmation.confirms(first, 0, second.copy(systolic = 110, deltaSystolic = -12.0), 60_000))
        assertFalse(
            "ordinary",
            BpConfirmation.confirms(first, 0, second.copy(systolic = 124, beyondCalibration = false, deltaSystolic = 2.0), 60_000)
        )
    }

    @Test
    fun driftFromCuffResiduals() {
        // Watch keeps reading ~12 lower than the cuff: pressure has gone up since calibration.
        assertEquals(BpDrift.Direction.HIGHER, BpDrift.fromResiduals(listOf(-2.0, -12.0, -11.0, -13.0)))
        assertEquals(BpDrift.Direction.LOWER, BpDrift.fromResiduals(listOf(11.0, 12.0, 14.0)))
        assertNull(BpDrift.fromResiduals(listOf(3.0, -4.0, 5.0, -2.0, 4.0)))
    }

    @Test
    fun driftFromARunOfReadingsBeyondCalibration() {
        assertEquals(BpDrift.Direction.HIGHER, BpDrift.fromReadings(listOf(false to 1.0, true to 25.0, true to 30.0, true to 22.0)))
        assertNull(BpDrift.fromReadings(listOf(true to 25.0, true to -30.0, true to 22.0)))
        assertNull(BpDrift.fromReadings(listOf(true to 25.0, true to 30.0)))
    }

    @Test
    fun conformalHalfWidth() {
        assertNull(BpConformal.halfWidth(listOf(1.0, 2.0, 3.0)))
        // n = 9: ⌈10 × 0.8⌉ = 8th smallest.
        assertEquals(8.0, BpConformal.halfWidth(listOf(9.0, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0))!!, 1e-9)
    }

    @Test
    fun pulseArrivalTimeIsRecovered() {
        val efs = SyntheticEcg.SAMPLE_RATE_HZ
        val ecg = SyntheticEcg.generate(20.0, 70.0, seed = 3)
        val peaks = RPeakDetector.detect(ecg, efs)
        // Wrist PPG on the same clock: each pulse's steepest upstroke 230 ms after its R peak, upside down like raw watch PPG.
        val ppg = FloatArray(ecg.size)
        for (r in peaks) {
            val up = r + (0.23 * efs).toInt()
            for (i in ppg.indices) {
                val t = (i - up).toDouble() / efs
                if (t < -0.3 || t > 0.8) continue
                ppg[i] += (1.0 / (1.0 + exp(-t / 0.02)) * exp(-maxOf(0.0, t) / 0.25)).toFloat()
            }
        }
        val raw = FloatArray(ppg.size) { 200_000f - 3_000f * ppg[it] }
        val pat = PulseArrival.compute(ecg, raw, efs)
        assertNotNull(pat)
        assertEquals(230.0, pat!!.medianMs, 15.0)
        assertTrue(pat.beats >= 15)
        assertNull(PulseArrival.compute(ecg, FloatArray(ecg.size), efs))
    }

    /** A user whose pressure follows stiffness: 110 at 0.3 → 150 at 0.8, plus cuff noise. */
    private fun dataset(): BpDataset {
        val random = Random(5)
        fun cuff(stiffness: Double) = (110 + 80 * (stiffness - 0.3)).toInt()
        val calibration = BpCalibration(
            "c",
            0,
            (1..3).map {
                val raw = SyntheticPpg.generate(20.0, 68.0, 0.45, seed = it)
                CalibrationPoint(PpgFeatures.extract(raw, fs)!!, cuff(0.45), 78, 68, raw.toList())
            }
        )
        val entries = (0 until 12).map { i ->
            val stiffness = 0.3 + 0.5 * random.nextDouble()
            val hr = 62.0 + 20 * stiffness
            val raw = SyntheticPpg.generate(20.0, hr, stiffness, seed = 100 + i)
            BpDatasetEntry((i + 1) * day, raw.toList(), cuff(stiffness) + random.nextInt(-3, 4), (70 + 30 * stiffness).toInt())
        }
        return BpDataset(calibration = calibration, entries = entries)
    }

    @Test
    fun evaluationReplaysTheAlgorithmAndCuffChecksHelp() {
        val data = dataset()
        val fixed = BpEvaluation.evaluate(data, incremental = false)
        val incremental = BpEvaluation.evaluate(data, incremental = true)
        assertEquals(12, fixed.count + fixed.refused)
        assertEquals(0, incremental.refused)
        assertTrue("fixed=$fixed incremental=$incremental", incremental.maeSys < fixed.maeSys)
        // Pulled towards the calibration without cuff checks, less so with them.
        assertTrue("fixed=$fixed incremental=$incremental", incremental.biasSlopeSys > fixed.biasSlopeSys)
        // The export format round-trips.
        val json = Protocol.json.encodeToString(data)
        assertEquals(data, Protocol.json.decodeFromString<BpDataset>(json))
    }

    @Test
    fun hybridModelIsUsedOnlyWhenItBeatsTheClassicalEstimate() {
        val random = Random(9)
        // The classical estimate misses a stiffness effect this user has: the residual follows the pulse shape.
        val learnable = (0 until 12).map { i ->
            val stiffness = 0.2 + 0.7 * random.nextDouble()
            val f = PpgFeatures.extract(SyntheticPpg.generate(20.0, 68.0, stiffness, seed = 200 + i), fs)!!
            HybridBpModel.Sample(f, null, 125, 80, (125 + 40 * (stiffness - 0.55)).toInt(), 80)
        }
        val model = HybridBpModel().train(learnable)
        assertNotNull(model)
        assertTrue("${model!!.looMaeHybrid} vs ${model.looMaeClassical}", model.looMaeHybrid < model.looMaeClassical * 0.9)
        assertEquals(learnable.size, model.looErrors.size)

        // Pure noise residuals: nothing to learn, the classical estimate stands.
        val noise = learnable.map { it.copy(cuffSys = 125 + random.nextInt(-8, 9)) }
        assertNull(HybridBpModel().train(noise))
        assertNull(HybridBpModel().train(learnable.take(3)))
    }
}
