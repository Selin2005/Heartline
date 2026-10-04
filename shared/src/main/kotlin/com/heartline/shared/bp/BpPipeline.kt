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

    /** [polarity]: read the wave this way up (the calibration's), instead of detecting it. */
    fun best(raw: FloatArray, fs: Int, polarity: Boolean? = null): Window? {
        val len = WINDOW_SECONDS * fs
        if (raw.size <= len) return window(raw, fs, raw.indices, polarity)
        val whole = PpgFeatures.rhythm(raw, fs, polarity)
        val irregular = whole != null && HemodynamicStateClassifier.irregular(whole)
        if (irregular) {
            val n = minOf(raw.size, IRREGULAR_SECONDS * fs)
            window(raw, fs, raw.size - n until raw.size, polarity)?.let { return it.copy(irregular = true) }
        }
        val step = STEP_SECONDS * fs
        val candidates = generateSequence(raw.size - len) { (it - step).takeIf { s -> s >= 0 } }
            .mapNotNull { start -> window(raw, fs, start until start + len, polarity) }
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

    private fun window(raw: FloatArray, fs: Int, range: IntRange, polarity: Boolean? = null): Window? {
        val f = PpgFeatures.extract(raw.copyOfRange(range.first, range.last + 1), fs, polarity) ?: return null
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
    /** Forearm angle (°) or watch orientation (° from every calibration round) beyond which the posture differs. */
    const val POSTURE_PITCH_DEG = 30.0
    const val POSTURE_ANGLE_DEG = 45.0

    /** Extra uncertainty of a reading taken in a posture unlike the calibration's, mmHg systolic. */
    private const val POSTURE_SD = 8.0

    /** Assumed pulse pressure share when turning a mean pressure into systolic/diastolic. */
    private const val PP_SYS_SHARE = 2.0 / 3.0

    fun run(
        stored: BpCalibration?,
        input: BpSessionInput,
        nowMs: Long,
        history: List<PpgFeatureVector> = emptyList(),
        log: BpSessionRecorder? = null,
        tuning: BpTuning = BpTuning.DEFAULT
    ): BpResult {
        fun done(outcome: BpOutcome, f: PpgFeatureVector? = null) = BpResult(outcome, f, null, null, null, null, null, null).also {
            log?.value("outcome.code", outcomeCode(outcome).toDouble())
        }
        if (stored == null || !stored.isValid(nowMs)) {
            log?.note("needsCalibration", stored?.let { needsCalibrationReason(it, nowMs) } ?: "no calibration")
            return done(BpOutcome.NeedsCalibration)
        }
        // Rounds read the other way up are read again with the calibration's polarity (algorithm 6.2).
        val calibration = stored.aligned()
        log?.note(
            "calibration.cuff",
            calibration.timedPoints().joinToString(" ") { (p, _) -> "${p.cuffSystolic}/${p.cuffDiastolic}${if (p.standing) "s" else ""}" }
        )

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
        // The measurement is read the same way up as the calibration it is compared with.
        val greenPolarity = calibration.polarity(BpChannel.PWA_GREEN, fs)
        val irPolarity = calibration.polarity(BpChannel.PWA_IR, fs)
        log?.value("polarity.calibration.green", greenPolarity?.let { if (it) 1.0 else 0.0 })
        log?.value("polarity.calibration.ir", irPolarity?.let { if (it) 1.0 else 0.0 })
        log?.value("polarity.detected.green", PpgFeatures.extract(raw, fs)?.let { if (it.inverted) 1.0 else 0.0 })
        val window = BpWindowSelector.best(raw, fs, greenPolarity)
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

        // The hand's height against the calibration's, from the forearm angle. Algorithm 6.4: its
        // ρgh is no longer taken off (tuning.hydrostaticFactor = 0). On 9 real cuff checks (Galaxy
        // Watch6) taking it off made the systolic error SD 9.0 mmHg against 4.0 without, and lying
        // in bed (forearm −58°, the wrist nowhere near 47 cm below the heart) it took 37 mmHg off.
        // The model is calibrated to a cuff on the upper arm at heart level, which the hand's
        // height doesn't change. A posture far from the calibration's is flagged instead.
        val armCm = (input.heightCm?.takeIf { it > 100 } ?: 170.0) * HydrostaticCalibration.ARM_SHARE_OF_HEIGHT
        val pitch = gravity?.let { ImuStreams.pitchDeg(it, calibration.armSign) }
        val refPitch = calibration.referencePitchDeg()
        val handCm = if (pitch != null && refPitch != null) armCm * (sin(Math.toRadians(pitch)) - sin(Math.toRadians(refPitch))) else 0.0
        val hydrostatic = -HydrostaticCalibration.MMHG_PER_CM * handCm
        val armAngle = gravity?.let { calibration.armAngleDeg(it) }
        val postureDiffers = (pitch != null && refPitch != null && abs(pitch - refPitch) > POSTURE_PITCH_DEG) ||
            (armAngle != null && armAngle > POSTURE_ANGLE_DEG)
        log?.value("arm.pitchDeg", pitch)
        log?.value("arm.referencePitchDeg", refPitch)
        log?.value("arm.handHeightCm", handCm)
        log?.value("arm.angleFromCalibrationDeg", armAngle)
        log?.value("arm.appliedMmHg", hydrostatic * tuning.hydrostaticFactor)
        log?.value("posture.differs", if (postureDiffers) 1.0 else 0.0)

        val channels = mutableListOf<ChannelEstimate>()
        val applied = hydrostatic * tuning.hydrostaticFactor
        val green = BpEstimator.estimate(calibration, features, nowMs, history, context, BpChannel.PWA_GREEN, state, applied, fs, tuning)
        (green as? BpOutcome.Ok)?.let { channels += it.estimate.channels }
        if (green == BpOutcome.NeedsCalibration) log?.note("needsCalibration", needsCalibrationReason(calibration, nowMs))

        val irFeatures = input.ir?.takeIf { precise == null }?.let { ir ->
            PpgFeatures.extract(
                ir.copyOfRange(range.first, minOf(range.last + 1, ir.size)).let {
                    com.heartline.shared.dsp.PpgRepair.repair(it)
                        ?: it
                },
                fs,
                irPolarity
            )
        }
        log?.features("ir", irFeatures)
        irFeatures?.let { f ->
            val ir = BpEstimator.estimate(calibration, f, nowMs, emptyList(), context, BpChannel.PWA_IR, state, applied, fs, tuning)
            (ir as? BpOutcome.Ok)?.let { channels += it.estimate.channels }
        }

        // Wrist ballistocardiogram against the PPG feet.
        val bcgPtt = input.imu.accel?.let { accel ->
            val t = times ?: return@let null
            val pulses = PpgFeatures.pulses(raw.copyOfRange(range.first, range.last + 1), fs, greenPolarity) ?: return@let null
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
        bcgPtt?.let {
            TransitEstimator.estimate(
                calibration,
                BpChannel.BCG_PTT,
                it,
                nowMs,
                null,
                hydrostatic * tuning.transitHydrostaticFactor
            )?.let { c -> channels += c }
        }

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
        log?.value("fusion.chi2", fused.chi2)
        val base = (green as? BpOutcome.Ok)?.estimate
        val systolic = fused.systolic.roundToInt().coerceIn(BpEstimator.SYSTOLIC_LIMITS)
        val diastolic = fused.diastolic.roundToInt().coerceIn(BpEstimator.DIASTOLIC_LIMITS).coerceAtMost(systolic - 15)
        val refSys = calibration.timedPoints().map { it.first.cuffSystolic }.average()
        // A posture unlike the calibration's (lying down, the hand raised or hanging): the wave may
        // differ for reasons the model hasn't seen. Shown with a wider ± and flagged.
        val postureSd = if (postureDiffers) POSTURE_SD else 0.0
        val estimate = BpEstimate(
            systolic,
            diastolic,
            (transit?.heartRateBpm ?: features.heartRateBpm).roundToInt(),
            sqrt(fused.sdSys * fused.sdSys + postureSd * postureSd).roundToInt(),
            sqrt(fused.sdDia * fused.sdDia + (postureSd * 0.7) * (postureSd * 0.7)).roundToInt(),
            // Channels that disagree more than their noise allows: shown, flagged for a cuff check.
            beyondCalibration = (base?.beyondCalibration ?: true) || fused.conflict || postureDiffers,
            postureDiffers = postureDiffers,
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

    /** Why [calibration] can't be used now, round by round, for the log. */
    fun needsCalibrationReason(calibration: BpCalibration, nowMs: Long): String {
        if (nowMs >= calibration.validUntilMs) return "expired"
        val polarity = calibration.polarity(BpChannel.PWA_GREEN)
        return calibration.points.joinToString("; ", prefix = "rounds: ") { p ->
            when {
                p.standing -> "standing"
                p.featureFs != BpCalibration.PPG_FS -> "other source (${p.featureFs} Hz)"
                p.features.version < PpgFeatureVector.MIN_MODEL_VERSION -> "old features"
                p.features.inverted != polarity -> if (p.ppg == null) "upside down, no raw wave" else "upside down (read again)"
                else -> "usable"
            }
        }
    }

    /**
     * What a calibration round measured on every channel (the watch sends it with the round; the
     * phone pairs it with the cuff reading via [CalibrationPoint.of]). Null without a usable pulse.
     */
    fun capture(input: BpSessionInput, greenPolarity: Boolean? = null, irPolarity: Boolean? = null): ChannelCapture? {
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
        val window = BpWindowSelector.best(raw, fs, greenPolarity) ?: return null
        val range = window.range
        val ir = input.ir?.takeIf { precise == null }?.copyOfRange(range.first, minOf(range.last + 1, input.ir.size))?.let {
            com.heartline.shared.dsp.PpgRepair.repair(it)
                ?: it
        }
        val bcg = input.imu.accel?.let { accel ->
            val t = times ?: return@let null
            val pulses = PpgFeatures.pulses(raw.copyOfRange(range.first, range.last + 1), fs, greenPolarity) ?: return@let null
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
            irFeatures = ir?.let { PpgFeatures.extract(it, fs, irPolarity) },
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
