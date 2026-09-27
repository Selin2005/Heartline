// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.bp

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Everything one session measured, as [BpPipeline] needs it.
 *
 * Quick mode: [green] (and [ir] when the watch gives it) from PPG_ON_DEMAND at [fs], sample
 * times [greenTimesNs] (wall clock), plus the motion sensors. Precise mode adds [precise]: ECG and
 * the PPG that comes with it on one clock, with the arm-raise maneuver inside it.
 */
data class BpSessionInput(
    val green: FloatArray,
    val fs: Int = BpCalibration.PPG_FS,
    val greenTimesNs: LongArray? = null,
    val ir: FloatArray? = null,
    val imu: ImuStreams = ImuStreams.EMPTY,
    val precise: PreciseInput? = null,
    val skinTempC: Double? = null,
    val edaMicroSiemens: Double? = null,
    val heightCm: Double? = null,
    /** The rhythm of the user's latest ECG (ECG AI result, last 30 days), as a prior. */
    val recentEcg: RecentRhythm? = null
)

/**
 * Precise mode: [ecg] and [ppg] at [fs] from the ECG tracker, first sample at [startNs] (wall
 * clock). [raisedNs]: when the user held the arm up in the maneuver (null without the maneuver).
 */
data class PreciseInput(val ecg: FloatArray, val ppg: FloatArray, val fs: Int, val startNs: Long, val raisedNs: LongRange? = null)

/** What the pipeline decided, with the window it used. */
data class BpResult(
    val outcome: BpOutcome,
    val features: PpgFeatureVector?,
    val irFeatures: PpgFeatureVector?,
    val state: StateAssessment?,
    val window: IntRange?,
    val transit: TransitTimes.Result?,
    val hydro: HydrostaticCalibration.Result?,
    val bcgPttMs: Double?
)

/**
 * Picks the part of a recording to estimate from (algorithm 6): the body often settles during
 * the recording (after sitting down, standing up, talking), so the steadiest 20 s window is used,
 * and an irregular rhythm gets up to [IRREGULAR_SECONDS] of beats to average.
 */
object BpWindowSelector {
    const val WINDOW_SECONDS = 20
    const val IRREGULAR_SECONDS = 45
    const val MAX_SECONDS = 60
    const val STEP_SECONDS = 2

    data class Window(val range: IntRange, val features: PpgFeatureVector, val steady: Boolean, val irregular: Boolean)

    fun best(raw: FloatArray, fs: Int): Window? {
        val len = WINDOW_SECONDS * fs
        if (raw.size <= len) return window(raw, fs, raw.indices)
        val whole = PpgFeatures.rhythm(raw, fs)
        val irregular = whole != null && HemodynamicStateClassifier.irregular(whole)
        if (irregular) {
            val n = minOf(raw.size, IRREGULAR_SECONDS * fs)
            window(raw, fs, raw.size - n until raw.size)?.let { return it.copy(irregular = true) }
        }
        val step = STEP_SECONDS * fs
        val candidates = generateSequence(raw.size - len) { (it - step).takeIf { s -> s >= 0 } }
            .mapNotNull { start -> window(raw, fs, start until start + len) }
            .toList()
        if (candidates.isEmpty()) return null
        return candidates.filter { it.steady && it.features.quality >= BpEstimator.MIN_QUALITY }.maxByOrNull { it.features.quality }
            ?: candidates.minBy { abs(it.features.hrSlopeBpmPerS) + abs(it.features.amplitudeTrend) * 2 }
    }

    /** During a live recording: stop once the latest window is steady and clean, or the time is up. */
    fun shouldStop(raw: FloatArray, fs: Int, minSeconds: Int = WINDOW_SECONDS): Boolean {
        val seconds = raw.size / fs
        if (seconds >= MAX_SECONDS) return true
        if (seconds < minSeconds) return false
        val latest = window(raw, fs, raw.size - WINDOW_SECONDS * fs until raw.size) ?: return false
        if (latest.irregular) return seconds >= IRREGULAR_SECONDS
        return latest.steady && latest.features.quality >= BpEstimator.MIN_QUALITY
    }

    private fun window(raw: FloatArray, fs: Int, range: IntRange): Window? {
        val f = PpgFeatures.extract(raw.copyOfRange(range.first, range.last + 1), fs) ?: return null
        val irregular = HemodynamicStateClassifier.irregular(f)
        val steady = abs(f.hrSlopeBpmPerS) <= HemodynamicStateClassifier.TRANSIENT_HR_SLOPE &&
            abs(f.amplitudeTrend) <= HemodynamicStateClassifier.TRANSIENT_AMPLITUDE &&
            !irregular
        return Window(range, f, steady, irregular)
    }
}

/**
 * Algorithm 6: every sensor the session had, one estimate per channel, one fused number.
 * See docs/algorithms/BP_ALGORITHM.md. Every intermediate goes to [log] when given.
 */
object BpPipeline {
    /** Assumed pulse pressure share when turning a mean pressure into systolic/diastolic. */
    private const val PP_SYS_SHARE = 2.0 / 3.0

    fun run(
        calibration: BpCalibration?,
        input: BpSessionInput,
        nowMs: Long,
        history: List<PpgFeatureVector> = emptyList(),
        log: BpSessionRecorder? = null
    ): BpResult {
        fun done(outcome: BpOutcome, f: PpgFeatureVector? = null) = BpResult(outcome, f, null, null, null, null, null, null).also {
            log?.value("outcome.code", outcomeCode(outcome).toDouble())
        }
        if (calibration == null || !calibration.isValid(nowMs)) return done(BpOutcome.NeedsCalibration)

        // Quick mode estimates from the steadiest window of the green PPG; precise mode from the
        // heart-level part of its ECG-synchronised PPG.
        // The ECG tracker's PPG has gaps and gain jumps on real watches: repaired before anything else.
        val precise = input.precise?.let { p ->
            p.copy(
                ppg =
                com.heartline.shared.dsp.PpgRepair.repair(p.ppg) ?: return done(BpOutcome.PoorSignal)
            )
        }
        val (raw, fs, times) = if (precise != null) {
            val level = levelSegment(precise)
            Triple(
                precise.ppg.copyOfRange(level.first, level.last + 1),
                precise.fs,
                LongArray(level.last - level.first + 1) {
                    precise.startNs +
                        ((level.first + it) * 1e9 / precise.fs).toLong()
                }
            )
        } else {
            Triple(com.heartline.shared.dsp.PpgRepair.repair(input.green) ?: input.green, input.fs, input.greenTimesNs)
        }
        val window = BpWindowSelector.best(raw, fs)
        log?.event("window", window?.let { "${it.range.first}..${it.range.last} steady=${it.steady} irregular=${it.irregular}" } ?: "none")
        val range = window?.range ?: raw.indices
        val features = window?.features
        log?.features("green", features)
        if (features == null) return done(BpOutcome.PoorSignal)

        val gravity = times?.let { t -> input.imu.meanGravity(t[range.first], t[range.last]) } ?: input.imu.meanGravity()
        val context = MeasurementContext(gravity, input.skinTempC, input.edaMicroSiemens, input.recentEcg)
        log?.note("rhythm.lastEcg", input.recentEcg?.name ?: "none")
        val state = HemodynamicStateClassifier.assess(features, calibration, context)
        log?.note("state", state.state.name)
        state.reason?.let { log?.note("state.reason", it.name) }

        // Arm height against the calibration's: a lower hand has a higher wrist pressure by ρgh.
        val armCm = (input.heightCm?.takeIf { it > 100 } ?: 170.0) * HydrostaticCalibration.ARM_SHARE_OF_HEIGHT
        val pitch = gravity?.let { ImuStreams.pitchDeg(it, calibration.armSign) }
        val refPitch = calibration.referencePitchDeg()
        val hydrostatic = if (pitch != null && refPitch != null) {
            -HydrostaticCalibration.MMHG_PER_CM * armCm * (sin(Math.toRadians(pitch)) - sin(Math.toRadians(refPitch)))
        } else {
            0.0
        }
        log?.value("arm.pitchDeg", pitch)
        log?.value("arm.hydrostaticMmHg", hydrostatic)

        val channels = mutableListOf<ChannelEstimate>()
        val green = BpEstimator.estimate(calibration, features, nowMs, history, context, BpChannel.PWA_GREEN, state, hydrostatic, fs)
        (green as? BpOutcome.Ok)?.let { channels += it.estimate.channels }

        val irFeatures = input.ir?.takeIf { precise == null }?.let { ir ->
            PpgFeatures.extract(
                ir.copyOfRange(range.first, minOf(range.last + 1, ir.size)).let {
                    com.heartline.shared.dsp.PpgRepair.repair(it)
                        ?: it
                },
                fs
            )
        }
        log?.features("ir", irFeatures)
        irFeatures?.let { f ->
            (BpEstimator.estimate(calibration, f, nowMs, emptyList(), context, BpChannel.PWA_IR, state, hydrostatic, fs) as? BpOutcome.Ok)
                ?.let { channels += it.estimate.channels }
        }

        // Wrist ballistocardiogram against the PPG feet.
        val bcgPtt = input.imu.accel?.let { accel ->
            val t = times ?: return@let null
            val pulses = PpgFeatures.pulses(raw.copyOfRange(range.first, range.last + 1), fs) ?: return@let null
            val feet = LongArray(pulses.size) { i -> onsetNs(t, range.first, pulses[i].onset) }
            val r = WristBcg.beforePpgFeet(accel, feet)
            r?.let {
                log?.value("bcg.iLagMs", it.iLagMs)
                log?.value("bcg.jLagMs", it.jLagMs)
                log?.value("bcg.quality", it.quality)
                log?.value("bcg.axis", it.axis.toDouble())
                log?.value("bcg.amplitude", it.amplitude)
            }
            WristBcg.transitMs(r)
        }
        log?.value("bcg.pttMs", bcgPtt)

        // Precise mode: arrival and transit times, and the maneuver.
        var transit: TransitTimes.Result? = null
        var hydro: HydrostaticCalibration.Result? = null
        if (precise != null) {
            val level = levelSegment(precise)
            val ecgLevel = precise.ecg.copyOfRange(level.first, level.last + 1)
            val ppgLevel = precise.ppg.copyOfRange(level.first, level.last + 1)
            val levelStart = precise.startNs + (level.first * 1e9 / precise.fs).toLong()
            transit = TransitTimes.compute(ecgLevel, ppgLevel, precise.fs, levelStart, input.imu.accel, state.state)
            transit?.let {
                log?.value("precise.patMs", it.patMs)
                log?.value("precise.pepMs", it.pepMs)
                log?.value("precise.pepMeasured", if (it.pepMeasured) 1.0 else 0.0)
                log?.value("precise.pttMs", it.pttMs)
                log?.value("precise.heartRateBpm", it.heartRateBpm)
            }
            if (precise.raisedNs != null && input.imu.accel != null) {
                val times500 = LongArray(precise.ppg.size) { precise.startNs + (it * 1e9 / precise.fs).toLong() }
                val patBeats = PulseArrival.perBeat(precise.ecg, precise.ppg, precise.fs).orEmpty()
                    .map { (r, ms) -> precise.startNs + (r * 1e9 / precise.fs).toLong() to ms }
                hydro =
                    HydrostaticCalibration.analyse(
                        precise.ppg,
                        times500,
                        precise.fs,
                        input.imu.accel,
                        input.heightCm,
                        precise.raisedNs,
                        patBeats
                    )
                hydro?.let { h ->
                    log?.value("hydro.sign", h.sign)
                    log?.value("hydro.minOffset", h.minOffset)
                    log?.value("hydro.maxOffset", h.maxOffset)
                    log?.value("hydro.beats", h.beats.toDouble())
                    log?.value("hydro.patSlope", h.patSlope?.mmHgPerMs)
                    log?.value("hydro.patSlopeSd", h.patSlope?.sd)
                    log?.value("hydro.map", h.map)
                    log?.value("hydro.mapSd", h.mapSd)
                    log?.value("hydro.mapLowerBound", h.mapLowerBound)
                }
            }
            transit?.let { t ->
                TransitEstimator.estimate(calibration, BpChannel.PAT, t.patMs, nowMs, hydro?.patSlope)?.let { channels += it }
                TransitEstimator.estimate(calibration, BpChannel.ECG_PTT, t.pttMs, nowMs, hydro?.patSlope)?.let { channels += it }
            }
            hydro?.map?.let { map ->
                val pp = calibration.timedPoints().map { (p, _) -> (p.cuffSystolic - p.cuffDiastolic).toDouble() }.average()
                val sd = hydro.mapSd ?: 8.0
                channels +=
                    ChannelEstimate(BpChannel.HYDRO_MAP, map + PP_SYS_SHARE * pp, map - (1 - PP_SYS_SHARE) * pp, sqrt(sd * sd + 16.0), sd)
            }
        }
        bcgPtt?.let { TransitEstimator.estimate(calibration, BpChannel.BCG_PTT, it, nowMs, null, hydrostatic)?.let { c -> channels += c } }

        channels.forEach { c ->
            log?.value("channel.${c.channel}.systolic", c.systolic)
            log?.value("channel.${c.channel}.diastolic", c.diastolic)
            log?.value("channel.${c.channel}.sdSys", c.sdSys)
            c.parts.forEach { (k, v) -> log?.value("channel.${c.channel}.sd.$k", v) }
        }
        val fused = BpFusion.fuse(channels, state.state)
        if (fused == null) {
            val out = if (green is BpOutcome.Ok) BpOutcome.PoorSignal else green
            return BpResult(out, features, irFeatures, state, range, transit, hydro, bcgPtt).also {
                log?.value("outcome.code", outcomeCode(out).toDouble())
            }
        }
        fused.channels.forEach { log?.value("fusion.weight.${it.channel}", it.weight) }
        val base = (green as? BpOutcome.Ok)?.estimate
        val systolic = fused.systolic.roundToInt().coerceIn(BpEstimator.SYSTOLIC_LIMITS)
        val diastolic = fused.diastolic.roundToInt().coerceIn(BpEstimator.DIASTOLIC_LIMITS).coerceAtMost(systolic - 15)
        val refSys = calibration.timedPoints().map { it.first.cuffSystolic }.average()
        val estimate = BpEstimate(
            systolic,
            diastolic,
            (transit?.heartRateBpm ?: features.heartRateBpm).roundToInt(),
            fused.sdSys.roundToInt(),
            fused.sdDia.roundToInt(),
            beyondCalibration = base?.beyondCalibration ?: true,
            deltaSystolic = fused.systolic - refSys,
            heartRateDominated = base?.heartRateDominated ?: false,
            ectopicBeats = features.ectopicCount,
            notValidated = calibration.profile.pregnancy,
            state = state,
            channels = fused.channels
        )
        log?.value("fusion.systolic", fused.systolic)
        log?.value("fusion.diastolic", fused.diastolic)
        log?.value("fusion.sdSys", fused.sdSys)
        log?.value("fusion.sdDia", fused.sdDia)
        log?.value("outcome.code", 0.0)
        return BpResult(BpOutcome.Ok(estimate), features, irFeatures, state, range, transit, hydro, bcgPtt)
    }

    /**
     * What a calibration round measured on every channel (the watch sends it with the round; the
     * phone pairs it with the cuff reading via [CalibrationPoint.of]). Null without a usable pulse.
     */
    fun capture(input: BpSessionInput): ChannelCapture? {
        val precise = input.precise?.let { p -> p.copy(ppg = com.heartline.shared.dsp.PpgRepair.repair(p.ppg) ?: return null) }
        val (raw, fs, times) = if (precise != null) {
            val level = levelSegment(precise)
            Triple(
                precise.ppg.copyOfRange(level.first, level.last + 1),
                precise.fs,
                LongArray(level.last - level.first + 1) {
                    precise.startNs +
                        ((level.first + it) * 1e9 / precise.fs).toLong()
                }
            )
        } else {
            Triple(com.heartline.shared.dsp.PpgRepair.repair(input.green) ?: input.green, input.fs, input.greenTimesNs)
        }
        val window = BpWindowSelector.best(raw, fs) ?: return null
        val range = window.range
        val ir = input.ir?.takeIf { precise == null }?.copyOfRange(range.first, minOf(range.last + 1, input.ir.size))?.let {
            com.heartline.shared.dsp.PpgRepair.repair(it)
                ?: it
        }
        val bcg = input.imu.accel?.let { accel ->
            val t = times ?: return@let null
            val pulses = PpgFeatures.pulses(raw.copyOfRange(range.first, range.last + 1), fs) ?: return@let null
            WristBcg.transitMs(WristBcg.beforePpgFeet(accel, LongArray(pulses.size) { i -> onsetNs(t, range.first, pulses[i].onset) }))
        }
        val transit = precise?.let { p ->
            val level = levelSegment(p)
            TransitTimes.compute(
                p.ecg.copyOfRange(level.first, level.last + 1),
                p.ppg.copyOfRange(level.first, level.last + 1),
                p.fs,
                p.startNs + (level.first * 1e9 / p.fs).toLong(),
                input.imu.accel,
                HemodynamicState.STEADY
            )
        }
        val gravity = times?.let { t -> input.imu.meanGravity(t[range.first], t[range.last]) } ?: input.imu.meanGravity()
        return ChannelCapture(
            features = window.features,
            ppg = raw.copyOfRange(range.first, range.last + 1).toList(),
            fs = fs,
            irFeatures = ir?.let { PpgFeatures.extract(it, fs) },
            ppgIr = ir?.toList(),
            bcgPttMs = bcg,
            patMs = transit?.patMs,
            pepMs = transit?.pepMs,
            pttMs = transit?.pttMs,
            gravity = gravity,
            skinTempC = input.skinTempC,
            edaMicroSiemens = input.edaMicroSiemens
        )
    }

    /**
     * The heart-level part of a precise recording: everything outside the raised-arm window and a
     * 3 s settling margin around it; the longest stretch is used.
     */
    fun levelSegment(p: PreciseInput): IntRange {
        val raised = p.raisedNs ?: return p.ppg.indices
        val margin = 3 * p.fs
        val from = (((raised.first - p.startNs) / 1e9) * p.fs).toInt() - margin
        val to = (((raised.last - p.startNs) / 1e9) * p.fs).toInt() + margin
        val before = 0 until from.coerceIn(0, p.ppg.size)
        val after = to.coerceIn(0, p.ppg.size) until p.ppg.size
        return if (before.count() >= after.count()) before else after
    }

    /** Wall-clock time of a fractional sample index (the tangent foot lies between samples). */
    private fun onsetNs(t: LongArray, offset: Int, onset: Double): Long {
        val i = (offset + onset).toInt().coerceIn(0, t.size - 2)
        val f = (offset + onset - i).coerceIn(0.0, 1.0)
        return t[i] + ((t[i + 1] - t[i]) * f).toLong()
    }

    /** 0 ok, 1 needs calibration, 2 poor signal, 3 out of range (logged). */
    fun outcomeCode(o: BpOutcome) = when (o) {
        is BpOutcome.Ok -> 0
        BpOutcome.NeedsCalibration -> 1
        BpOutcome.PoorSignal -> 2
        is BpOutcome.OutOfRange -> 3
    }
}

/** One calibration round's measurements on every channel (see [BpPipeline.capture]). */
@kotlinx.serialization.Serializable
data class ChannelCapture(
    val features: PpgFeatureVector,
    val ppg: List<Float>,
    val fs: Int = BpCalibration.PPG_FS,
    val irFeatures: PpgFeatureVector? = null,
    val ppgIr: List<Float>? = null,
    val bcgPttMs: Double? = null,
    val patMs: Double? = null,
    val pepMs: Double? = null,
    val pttMs: Double? = null,
    val gravity: List<Double>? = null,
    val skinTempC: Double? = null,
    val edaMicroSiemens: Double? = null
)
