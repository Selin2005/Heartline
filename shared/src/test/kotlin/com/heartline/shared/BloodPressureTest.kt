// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared

import com.heartline.shared.bp.BpCalibration
import com.heartline.shared.bp.BpCategory
import com.heartline.shared.bp.BpEstimator
import com.heartline.shared.bp.BpOutcome
import com.heartline.shared.bp.CalibrationPoint
import com.heartline.shared.bp.OutOfRangeReason
import com.heartline.shared.bp.PpgFeatureVector
import com.heartline.shared.bp.PpgFeatures
import com.heartline.shared.model.RecordMeta
import com.heartline.shared.sample.SyntheticPpg
import com.heartline.shared.sync.CaptureRequest
import com.heartline.shared.sync.CaptureResult
import com.heartline.shared.sync.InMemoryTransport
import com.heartline.shared.sync.Outbox
import com.heartline.shared.sync.OutboxItem
import com.heartline.shared.sync.PendingMessage
import com.heartline.shared.sync.PhoneSyncEngine
import com.heartline.shared.sync.Protocol
import com.heartline.shared.sync.RecordSink
import com.heartline.shared.sync.WatchSyncEngine
import kotlin.math.abs
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BloodPressureTest {
    private val DAY = BpCalibration.DAY_MS
    private val fs = SyntheticPpg.SAMPLE_RATE_HZ

    private fun features(hr: Double, stiffness: Double, seed: Int = 1) =
        PpgFeatures.extract(SyntheticPpg.generate(20.0, hr, stiffness, seed = seed), fs)!!

    private fun calibration(at: Long = 0) = BpCalibration(
        "c1",
        at,
        listOf(
            CalibrationPoint(features(68.0, 0.45, 1), 121, 79, 68),
            CalibrationPoint(features(70.0, 0.5, 2), 124, 81, 70),
            CalibrationPoint(features(66.0, 0.5, 3), 119, 78, 66)
        )
    )

    @Test
    fun featuresTrackHeartRateAndStiffness() {
        val soft = features(60.0, 0.1)
        val stiff = features(60.0, 0.9)
        assertEquals(60.0, soft.heartRateBpm, 2.0)
        assertTrue(stiff.riseFraction < soft.riseFraction)
        assertTrue(stiff.upstrokeMs < soft.upstrokeMs)
        assertTrue(stiff.areaRatio > soft.areaRatio)
        assertTrue(soft.quality > 0.7 && stiff.quality > 0.7)
        assertEquals(PpgFeatureVector.VERSION, soft.version)
    }

    @Test
    fun upsideDownPpgIsDetectedAndGivesTheSameFeatures() {
        val signal = SyntheticPpg.generate(20.0, 66.0, 0.5, seed = 3)
        val upright = PpgFeatures.extract(signal, fs)!!
        // Raw watch PPG: large offset, and light intensity falls as blood volume rises.
        val raw = FloatArray(signal.size) { 250_000f - 4_000f * signal[it] }
        val flipped = PpgFeatures.extract(raw, fs)!!
        assertTrue(flipped.inverted)
        assertTrue(!upright.inverted)
        assertEquals(upright.upstrokeMs, flipped.upstrokeMs, 20.0)
        assertEquals(upright.heartRateBpm, flipped.heartRateBpm, 1.0)
    }

    @Test
    fun rejectsShortOrFlatSignals() {
        assertNull(PpgFeatures.extract(FloatArray(300), fs))
        assertNull(PpgFeatures.extract(FloatArray(2000), fs))
    }

    @Test
    fun sameStateReproducesCalibrationMean() {
        val cal = calibration()
        val out = BpEstimator.estimate(cal, features(68.0, 0.48, 7), 1_000) as BpOutcome.Ok
        assertTrue("${out.estimate}", abs(out.estimate.systolic - 121) <= 5)
        assertTrue("${out.estimate}", abs(out.estimate.diastolic - 79) <= 4)
        assertTrue(out.estimate.uncertaintySys in 5..10)
    }

    @Test
    fun stifferFasterPulseEstimatesHigherPressure() {
        val cal = calibration()
        val base = (BpEstimator.estimate(cal, features(68.0, 0.48, 7), 1_000) as BpOutcome.Ok).estimate
        val stiff = (BpEstimator.estimate(cal, features(82.0, 0.8, 8), 1_000) as BpOutcome.Ok).estimate
        // Algorithm 5: the rate no longer drives the estimate (bounded, see BpAlgorithm5Test), so
        // without cuff checks the shape alone moves it only a little, but in the right direction.
        assertTrue("base=$base stiff=$stiff", stiff.systolic > base.systolic)
        assertTrue("base=$base stiff=$stiff", stiff.diastolic > base.diastolic)
        assertTrue("base=$base stiff=$stiff", stiff.uncertaintySys >= base.uncertaintySys)
    }

    @Test
    fun differentStatesDoNotAllGiveTheSameReading() {
        val cal = calibration()
        val readings = listOf(62.0 to 0.3, 68.0 to 0.48, 74.0 to 0.6, 80.0 to 0.7, 58.0 to 0.35).mapIndexed { i, (hr, st) ->
            (BpEstimator.estimate(cal, features(hr, st, 20 + i), 1_000) as BpOutcome.Ok).estimate.systolic
        }
        assertTrue("readings $readings", readings.distinct().size >= 4)
    }

    @Test
    fun pulseWaveFarFromCalibrationIsShownAndFlaggedNotRefused() {
        // A genuinely different state (fast, stiff pulse) must give a number, never "outside calibration".
        val out = BpEstimator.estimate(calibration(), features(135.0, 0.95, 9), 1_000)
        val e = (out as? BpOutcome.Ok)?.estimate ?: error("refused: $out")
        assertTrue("$e", e.beyondCalibration)
        // Mostly a faster pulse: flagged with a wide ±, never a confident high number (algorithms 5–6).
        assertTrue("$e", e.systolic > 121)
        assertTrue("$e", e.heartRateDominated)
        assertTrue("$e", e.uncertaintySys > 8)
        assertTrue(e.deltaSystolic > 0)
    }

    @Test
    fun highPressureIsNeverHiddenBehindAnError() {
        val e = (BpEstimator.estimate(calibration(), features(95.0, 0.9, 30), 1_000) as BpOutcome.Ok).estimate
        assertTrue("$e", e.systolic > 121 && e.beyondCalibration)
    }

    @Test
    fun aNoisyShapeFeatureAloneNeitherBlocksNorSkewsTheReading() {
        val f = features(68.0, 0.48, 7)
        val skewed = f.copy(apgDa = f.apgDa + 8.0, areaRatio = f.areaRatio + 5.0)
        val clean = (BpEstimator.estimate(calibration(), f, 1_000) as BpOutcome.Ok).estimate
        val out = (BpEstimator.estimate(calibration(), skewed, 1_000) as BpOutcome.Ok).estimate
        assertEquals(clean.systolic, out.systolic)
        assertTrue(!out.beyondCalibration)
    }

    @Test
    fun shapesNoRealPulseHasAreRefusedAsSignalProblems() {
        val f = features(68.0, 0.48, 7)
        val impossible = f.copy(upstrokeMs = f.beatMs * 0.8)
        assertEquals(BpOutcome.OutOfRange(OutOfRangeReason.SIGNAL_INCONSISTENT), BpEstimator.estimate(calibration(), impossible, 1_000))
        // A marginal recording whose core shape is also far off is more likely movement.
        val shaky = features(68.0, 0.48, 7).copy(quality = 0.6, upstrokeMs = f.upstrokeMs + 120)
        assertEquals(BpOutcome.OutOfRange(OutOfRangeReason.SIGNAL_INCONSISTENT), BpEstimator.estimate(calibration(), shaky, 1_000))
        // The same change on a clean recording is physiology: shown.
        assertTrue(BpEstimator.estimate(calibration(), shaky.copy(quality = 0.9), 1_000) is BpOutcome.Ok)
    }

    @Test
    fun algorithm3FeaturesFollowStiffness() {
        val soft = features(60.0, 0.1)
        val stiff = features(60.0, 0.9)
        assertTrue("soft=${soft.reflectionDelayMs} stiff=${stiff.reflectionDelayMs}", stiff.reflectionDelayMs < soft.reflectionDelayMs - 40)
        assertTrue(soft.reflectionDelayMs in 150.0..400.0)
        assertEquals(PpgFeatureVector.SHAPE_POINTS, soft.shape.size)
        assertTrue(soft.skewness > 0.0)
    }

    @Test
    fun algorithm2CalibrationStaysValidAndIsUpgradedFromItsRawPpg() {
        val signals = (1..3).map { SyntheticPpg.generate(20.0, 68.0, 0.5, seed = it) }
        val v2 = BpCalibration(
            "c",
            0,
            signals.map { raw ->
                val f = PpgFeatures.extract(raw, fs)!!
                CalibrationPoint(f.copy(version = 2, reflectionDelayMs = 0.0, shape = emptyList()), 122, 80, 68, raw.toList())
            }
        )
        assertTrue(v2.isValid(1_000))
        assertTrue(BpEstimator.estimate(v2, features(68.0, 0.5, 9), 1_000) is BpOutcome.Ok)
        val up = v2.upgraded()
        assertTrue(up.points.all { it.features.version == PpgFeatureVector.VERSION && it.features.reflectionDelayMs > 0 })
    }

    @Test
    fun cuffChecksExtendTheCalibrationRange() {
        val base = calibration()
        val stiffState = features(80.0, 0.8, 40)
        val before = (BpEstimator.estimate(base, stiffState, 5 * DAY) as BpOutcome.Ok).estimate
        // Two later cuff checks in that stiff state read 150.
        val extended = base
            .withExtraPoint(CalibrationPoint(features(80.0, 0.8, 41), 150, 94, 80, atMs = 2 * DAY))
            .withExtraPoint(CalibrationPoint(features(80.0, 0.82, 42), 152, 95, 80, atMs = 4 * DAY))
        val after = (BpEstimator.estimate(extended, stiffState, 5 * DAY) as BpOutcome.Ok).estimate
        assertTrue("before=$before after=$after", after.systolic > before.systolic + 5)
        assertTrue("after=$after", abs(after.systolic - 151) <= 10)
        assertEquals(119..152, extended.systolicSpan)
    }

    @Test
    fun aRecentCuffCheckMovesTheBaseline() {
        val base = calibration()
        val same = features(68.0, 0.48, 7)
        val drifted = base.withExtraPoint(CalibrationPoint(features(68.0, 0.48, 43), 138, 88, 68, atMs = 10 * DAY))
        val a = (BpEstimator.estimate(base, same, 10 * DAY) as BpOutcome.Ok).estimate
        val b = (BpEstimator.estimate(drifted, same, 10 * DAY) as BpOutcome.Ok).estimate
        assertTrue("a=$a b=$b", b.systolic >= a.systolic + 4)
    }

    @Test
    fun extraPointsAreCapped() {
        var cal = calibration()
        repeat(20) { cal = cal.withExtraPoint(CalibrationPoint(features(68.0, 0.5, 50 + it), 120, 80, 68, atMs = it.toLong())) }
        assertEquals(BpCalibration.MAX_EXTRA_POINTS, cal.extraPoints.size)
        assertEquals(19L, cal.extraPoints.last().atMs)
    }

    @Test
    fun personalSpreadOnlyWidensTheDefault() {
        val steady = List(6) { features(68.0, 0.5, 60 + it) }
        assertTrue(BpEstimator.personalScale(steady).indices.all { BpEstimator.personalScale(steady)[it] >= BpEstimator.featureScale[it] })
        val varied = List(8) { features(55.0 + it * 8, 0.5, 70 + it) }
        assertTrue(BpEstimator.personalScale(varied)[0] > BpEstimator.featureScale[0])
    }

    @Test
    fun oldAlgorithmCalibrationMustBeRedone() {
        val old = calibration().let { c -> c.copy(points = c.points.map { it.copy(features = it.features.copy(version = 1)) }) }
        assertEquals(BpOutcome.NeedsCalibration, BpEstimator.estimate(old, features(68.0, 0.48), 1_000))
    }

    @Test
    fun calibrationSpanningAPressureRangeIsLearned() {
        // This user's pressure rises strongly with stiffness; the fit should follow them.
        val cal = BpCalibration(
            "c2",
            0,
            listOf(
                CalibrationPoint(features(66.0, 0.3, 11), 108, 70, 66),
                CalibrationPoint(features(66.0, 0.5, 12), 124, 80, 66),
                CalibrationPoint(features(66.0, 0.7, 13), 140, 90, 66)
            )
        )
        // New recordings in the calibration's low and high states land near those cuff readings,
        // not on the calibration mean.
        val low = (BpEstimator.estimate(cal, features(66.0, 0.3, 21), 1_000) as BpOutcome.Ok).estimate
        val high = (BpEstimator.estimate(cal, features(66.0, 0.7, 22), 1_000) as BpOutcome.Ok).estimate
        assertTrue("low=$low", low.systolic in 100..118)
        assertTrue("high=$high", high.systolic in 132..152)
    }

    @Test
    fun calibrationExpiresAfter28Days() {
        val cal = calibration(at = 0)
        assertEquals(28, cal.daysLeft(0))
        assertEquals(BpOutcome.NeedsCalibration, BpEstimator.estimate(cal, features(68.0, 0.5), BpCalibration.VALIDITY_MS + 1))
        assertEquals(BpOutcome.NeedsCalibration, BpEstimator.estimate(null, features(68.0, 0.5), 0))
        assertNotNull(cal)
    }

    @Test
    fun noisySignalIsRejected() {
        val noisy = PpgFeatures.extract(SyntheticPpg.generate(20.0, 70.0, 0.5, noise = 1.5, seed = 4), fs)
        assertEquals(BpOutcome.PoorSignal, BpEstimator.estimate(calibration(), noisy, 1_000))
    }

    @Test
    fun accuracyStatsAreBlandAltman() {
        val acc = com.heartline.shared.bp.BpAccuracy.of(
            listOf(
                com.heartline.shared.bp.BpPair(124, 80, 120, 78),
                com.heartline.shared.bp.BpPair(118, 76, 122, 79),
                com.heartline.shared.bp.BpPair(135, 85, 121, 80)
            )
        )!!
        assertEquals(3, acc.count)
        assertEquals(14.0 / 3, acc.meanDiffSys, 1e-9)
        assertEquals(67, acc.within10Percent)
        assertNull(com.heartline.shared.bp.BpAccuracy.of(emptyList()))
    }

    @Test
    fun ahaCategories() {
        assertEquals(BpCategory.NORMAL, BpCategory.of(115, 75))
        assertEquals(BpCategory.ELEVATED, BpCategory.of(125, 78))
        assertEquals(BpCategory.HIGH_STAGE_1, BpCategory.of(118, 84))
        assertEquals(BpCategory.HIGH_STAGE_2, BpCategory.of(145, 85))
        assertEquals(BpCategory.CRISIS, BpCategory.of(185, 100))
    }
}

class CalibrationSyncTest {
    @Test
    fun captureRequestAndResultRoundTrip() = runBlocking {
        val (watchSide, phoneSide) = InMemoryTransport.pair()
        val requests = mutableListOf<CaptureRequest>()
        val results = mutableListOf<CaptureResult>()
        var calibration: BpCalibration? = null
        val queue = mutableListOf<PendingMessage>()
        val outbox = object : Outbox {
            override suspend fun pending() = emptyList<OutboxItem>()

            override suspend fun pendingMessages() = queue.toList()

            override suspend fun markDelivered(id: String) {
                queue.removeAll { it.id == id }
            }
        }
        val sink = object : RecordSink {
            override suspend fun contains(id: String) = false

            override suspend fun save(meta: RecordMeta, wave: FloatArray?) = Unit

            override suspend fun delete(id: String) = Unit
        }
        val watch = WatchSyncEngine(watchSide, outbox, onCalibration = { calibration = it }, onCaptureRequest = { requests += it })
        val phone = PhoneSyncEngine(phoneSide, sink, onCaptureResult = { results += it })
        val jobs = listOf(
            phoneSide.incoming.onEach { phone.handle(it) }.launchIn(this),
            watchSide.incoming.onEach { watch.handle(it) }.launchIn(this)
        )
        yield()

        phone.requestCapture(CaptureRequest("cap", 2))
        repeat(10) { yield() }
        assertEquals(2, requests.single().round)

        val features = PpgFeatures.extract(SyntheticPpg.generate(20.0), 100)!!
        val result = CaptureResult("r1", "cap", 2, features)
        queue += PendingMessage("r1", Protocol.BP_CALIBRATION_CAPTURE, Protocol.json.encodeToString(result).encodeToByteArray())
        watch.flush()
        repeat(10) { yield() }
        assertEquals(result, results.single())
        assertTrue(queue.isEmpty())

        val cal = BpCalibration("c", 0, List(3) { CalibrationPoint(features, 120, 80, 70) })
        phone.sendCalibration(cal)
        repeat(10) { yield() }
        assertEquals(cal, calibration)
        phone.sendCalibration(null)
        repeat(10) { yield() }
        assertNull(calibration)
        jobs.forEach { it.cancel() }
    }
}
