// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared

import com.heartline.shared.bp.BpCalibration
import com.heartline.shared.bp.BpChannel
import com.heartline.shared.bp.BpFusion
import com.heartline.shared.bp.BpOutcome
import com.heartline.shared.bp.BpPipeline
import com.heartline.shared.bp.BpSessionInput
import com.heartline.shared.bp.BpSessionRecorder
import com.heartline.shared.bp.BpSessionReplay
import com.heartline.shared.bp.BpSessionStreams
import com.heartline.shared.bp.CalibrationPoint
import com.heartline.shared.bp.ChannelEstimate
import com.heartline.shared.bp.HemodynamicState
import com.heartline.shared.bp.PpgWave
import com.heartline.shared.bp.PpgWaveChoice
import com.heartline.shared.sample.SyntheticPpg
import com.heartline.shared.sample.SyntheticSession
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Algorithm 6.6 on PPG as a real Galaxy Watch7 (SM-L310) gave it: every green point with status
 * −1 and a value around 0, the infrared with a clean pulse.
 */
class PpgWaveTest {
    private fun noise(n: Int, seed: Int = 3) = Random(seed.toLong()).let { r -> FloatArray(n) { (r.nextGaussian() * 25).toFloat() } }

    /** A synthetic session as the Watch7 records it: the green replaced by noise. */
    private fun noGreen(spec: SyntheticSession.Spec): BpSessionInput {
        val input = SyntheticSession.generate(spec).input
        return input.copy(green = noise(input.green.size, spec.seed))
    }

    @Test
    fun theInfraredBecomesTheWaveOnlyWhenTheGreenIsMissing() {
        val ir = SyntheticPpg.generate(30.0, 72.0, seed = 2)
        val green = SyntheticPpg.generate(30.0, 72.0, seed = 5)
        assertEquals(PpgWave.IR, PpgWaveChoice.choose(noise(ir.size), ir, 100, 1.0))
        // A watch that says the green is missing but still gives a pulse keeps the green.
        assertEquals(PpgWave.GREEN, PpgWaveChoice.choose(green, ir, 100, 1.0))
        // A poor green the watch didn't flag stays a poor green (and a poor-signal result).
        assertEquals(PpgWave.GREEN, PpgWaveChoice.choose(noise(ir.size), ir, 100, 0.5))
        assertEquals(PpgWave.GREEN, PpgWaveChoice.choose(noise(ir.size), null, 100, 1.0))
    }

    @Test
    fun theReplayOfASessionChoosesTheSameWave() {
        val ir = SyntheticPpg.generate(30.0, 72.0, seed = 2)
        val green = noise(ir.size)
        val log = BpSessionRecorder("s", "measure", 0L) { 0L }.apply {
            val ppg = stream(BpSessionStreams.PPG, *BpSessionStreams.PPG_COLUMNS)
            for (i in ir.indices) ppg.append(i * 10_000_000L, green[i], 780_000f - ir[i] * 8_000f, 540_000f, -1f, 0f, 0f)
        }.build()
        val input = BpSessionReplay.input(log)
        assertEquals(PpgWave.IR, input.wave)
        assertEquals(null, input.ir)
        assertEquals(780_000f - ir[10] * 8_000f, input.green[10], 0.5f)
    }

    @Test
    fun aCalibrationOnTheInfraredMeasuresOnTheInfraredOnly() {
        val base = SyntheticSession.Spec(systolic = 128.0, diastolic = 82.0, refSystolic = 128.0)
        val rounds = (1..3).map { i -> PpgWaveChoice.apply(noGreen(base.copy(seed = i)), 1.0) }
        assertTrue(rounds.all { it.wave == PpgWave.IR })
        val points = rounds.map { CalibrationPoint.of(BpPipeline.capture(it)!!, 128, 82, 70) }
        assertTrue(points.all { it.wave == PpgWave.IR })
        val calibration = BpCalibration("ir", 0, points)
        assertEquals(PpgWave.IR, calibration.wave())

        val reading = PpgWaveChoice.apply(noGreen(base.copy(seed = 20)), 1.0)
        val ok = BpPipeline.run(calibration, reading, 1_000).outcome as? BpOutcome.Ok ?: error("no number")
        assertTrue("${ok.estimate}", kotlin.math.abs(ok.estimate.systolic - 128) <= 8)

        // A green session against the infrared calibration, and the other way round: never compared.
        val green = SyntheticSession.generate(base.copy(seed = 21)).input
        assertEquals(BpOutcome.NeedsCalibration, BpPipeline.run(calibration, green, 1_000).outcome)
        val greenCalibration = BpCalibration(
            "g",
            0,
            (1..3).map { i -> CalibrationPoint.of(BpPipeline.capture(SyntheticSession.generate(base.copy(seed = i)).input)!!, 128, 82, 70) }
        )
        assertEquals(PpgWave.GREEN, greenCalibration.wave())
        assertEquals(BpOutcome.NeedsCalibration, BpPipeline.run(greenCalibration, reading, 1_000).outcome)
    }

    @Test
    fun theCalibrationsDiastolicMisfitIsNotAveragedAwayAcrossChannels() {
        // Three channels anchored to the same cuff readings, whose diastolic strays by 7 mmHg from
        // what the model follows: a real user's diastolic missed by SD 7.7 while fusing to ±5.
        fun channel(c: BpChannel, dia: Double) = ChannelEstimate(c, 141.0, dia, 6.2, 8.5, parts = mapOf("residualDia" to 7.0))
        val fused = BpFusion.fuse(
            listOf(channel(BpChannel.PWA_GREEN, 85.6), channel(BpChannel.PWA_IR, 85.2), channel(BpChannel.BCG_PTT, 80.8)),
            HemodynamicState.STEADY
        )!!
        assertTrue("${fused.sdDia}", fused.sdDia >= kotlin.math.sqrt(3.5 * 3.5 + 7.0 * 7.0) - 1e-9)
        // Without a misfit the floor stays where it was.
        val tight = BpFusion.fuse(listOf(ChannelEstimate(BpChannel.PWA_GREEN, 141.0, 85.0, 5.0, 4.0)), HemodynamicState.STEADY)!!
        assertTrue("${tight.sdDia}", tight.sdDia < 5.0)
    }
}
