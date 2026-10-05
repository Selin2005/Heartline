// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.bp

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/** The independent ways the watch estimates pressure (algorithm 6). */
enum class BpChannel {
    /** Pulse-wave analysis of the green PPG (algorithms 3–5). */
    PWA_GREEN,

    /** Pulse-wave analysis of the infrared PPG: deeper tissue, less affected by skin vasoconstriction. */
    PWA_IR,

    /** Wrist ballistocardiogram (accelerometer) I wave → PPG foot: transit time without the pre-ejection period. */
    BCG_PTT,

    /** Precise mode: ECG R peak → PPG upstroke (includes the pre-ejection period). */
    PAT,

    /** Precise mode: PAT minus the pre-ejection period (R → BCG I wave). */
    ECG_PTT,

    /** Arm-raise maneuver: mean pressure from the PPG amplitude's hydrostatic curve (Shaltis 2008). */
    HYDRO_MAP
}

/** One channel's own estimate, mmHg, with its standard deviations; [weight] is its share in the fused number. */
data class ChannelEstimate(
    val channel: BpChannel,
    val systolic: Double,
    val diastolic: Double,
    val sdSys: Double,
    val sdDia: Double,
    val weight: Double = 0.0,
    /** Where the ± comes from (logged): base, residual, drift, extrapolation, doubt. */
    val parts: Map<String, Double> = emptyMap(),
    /**
     * The part of [sdSys] / [sdDia] that is uncertainty about the size of a change (how sensitive
     * this user is), not noise: it grows with the change the channel sees. It widens the ± but
     * never decides the weight, or a channel that sees a change would always lose to one that
     * sees none (algorithm 6.3).
     */
    val scaleSys: Double = 0.0,
    val scaleDia: Double = 0.0
) {
    /** The channel's measurement noise: its ± without the scale part. */
    val noiseSys: Double get() = sqrt((sdSys * sdSys - scaleSys * scaleSys).coerceAtLeast(1.0))
    val noiseDia: Double get() = sqrt((sdDia * sdDia - scaleDia * scaleDia).coerceAtLeast(1.0))
}

/**
 * Combines the channels into one number (algorithm 6): inverse-variance weighting, each channel's
 * ± first widened by how far it can be trusted in the body's current state. When the channels
 * disagree more than their ± allow, the fused ± grows accordingly (Birge ratio), so a conflict is
 * visible instead of averaged away.
 */
object BpFusion {
    /** Multiplier on each channel's ± per state. Tunable from real session logs. */
    val stateFactor: Map<HemodynamicState, Map<BpChannel, Double>> = mapOf(
        HemodynamicState.STEADY to BpChannel.entries.associateWith { 1.0 },
        HemodynamicState.TRANSIENT to mapOf(
            BpChannel.PWA_GREEN to 1.5,
            BpChannel.PWA_IR to 1.5,
            BpChannel.BCG_PTT to 1.3,
            BpChannel.PAT to 2.0,
            BpChannel.ECG_PTT to 1.3,
            BpChannel.HYDRO_MAP to 1.3
        ),
        HemodynamicState.COMPENSATORY to mapOf(
            BpChannel.PWA_GREEN to 2.5,
            BpChannel.PWA_IR to 1.8,
            BpChannel.BCG_PTT to 1.2,
            BpChannel.PAT to 3.0,
            BpChannel.ECG_PTT to 1.2,
            BpChannel.HYDRO_MAP to 1.0
        ),
        HemodynamicState.IRREGULAR to mapOf(
            BpChannel.PWA_GREEN to 1.8,
            BpChannel.PWA_IR to 1.8,
            BpChannel.BCG_PTT to 1.6,
            BpChannel.PAT to 1.5,
            BpChannel.ECG_PTT to 1.4,
            BpChannel.HYDRO_MAP to 1.5
        )
    )

    fun fuse(channels: List<ChannelEstimate>, state: HemodynamicState): Fused? {
        if (channels.isEmpty()) return null
        val factors = stateFactor.getValue(state)
        val widened = channels.map { c ->
            val f = factors[c.channel] ?: 1.0
            c.copy(sdSys = c.sdSys * f, sdDia = c.sdDia * f, scaleSys = c.scaleSys * f, scaleDia = c.scaleDia * f)
        }
        val (sys, sdSys, wSys) = combine(widened.map { it.systolic }, widened.map { it.noiseSys }, widened.map { it.scaleSys }, COMMON_SD)
        val (dia, sdDia, _) = combine(
            widened.map { it.diastolic },
            widened.map { it.noiseDia },
            widened.map { it.scaleDia },
            COMMON_SD * 0.7
        )
        return Fused(
            sys,
            dia,
            sdSys,
            sdDia,
            widened.mapIndexed { i, c ->
                c.copy(weight = wSys[i])
            },
            chi2(widened.map { it.systolic }, widened.map { it.noiseSys })
        )
    }

    /**
     * Every channel is anchored to the same cuff calibration, so the reference's error and the
     * pressure's own beat-to-beat variation are shared: combining channels can't take the ±
     * below this (algorithm 6.3; a real reading showed ±3 and missed the cuff by 4).
     */
    const val COMMON_SD = 5.0

    /**
     * [chi2]: how much more the channels' systolic values disagree than their noise allows
     * (1 = as expected). Above [CONFLICT_CHI2] the reading is a conflict: shown with the wider ±
     * and flagged for a cuff check, never averaged into a confident number.
     */
    data class Fused(
        val systolic: Double,
        val diastolic: Double,
        val sdSys: Double,
        val sdDia: Double,
        val channels: List<ChannelEstimate>,
        val chi2: Double = 0.0
    ) {
        val conflict: Boolean get() = chi2 > CONFLICT_CHI2
    }

    const val CONFLICT_CHI2 = 4.0

    /** Reduced chi-square of the values around their noise-weighted mean (0 for a single channel). */
    internal fun chi2(x: List<Double>, noise: List<Double>): Double {
        if (x.size < 2) return 0.0
        val w = noise.map { 1.0 / it.coerceAtLeast(1.0).pow(2) }
        val mean = x.indices.sumOf { w[it] * x[it] } / w.sum()
        return x.indices.sumOf { w[it] * (x[it] - mean).pow(2) } / (x.size - 1)
    }

    /**
     * Mean weighted by each channel's noise; its ± is the combined noise (widened by the Birge
     * ratio when the channels disagree more than their noise allows), plus the weighted scale
     * uncertainty, never below [floor]. Returns the mean, the ± and the normalised weights.
     */
    internal fun combine(
        x: List<Double>,
        noise: List<Double>,
        scale: List<Double> = List(x.size) { 0.0 },
        floor: Double = 0.0
    ): Triple<Double, Double, List<Double>> {
        val w = noise.map { 1.0 / it.coerceAtLeast(1.0).pow(2) }
        val total = w.sum()
        val mean = x.indices.sumOf { w[it] * x[it] } / total
        var noiseSd = sqrt(1.0 / total)
        if (x.size > 1) {
            val chi2 = x.indices.sumOf { w[it] * (x[it] - mean).pow(2) } / (x.size - 1)
            if (chi2 > 1) noiseSd *= sqrt(chi2)
        }
        val weights = w.map { it / total }
        val scaleSd = x.indices.sumOf { weights[it] * scale[it] }
        return Triple(mean, maxOf(sqrt(noiseSd * noiseSd + scaleSd * scaleSd), floor), weights)
    }
}

/**
 * Calibrated estimate from one transit time (BCG PTT, PAT or ECG PTT): pressure changes
 * linearly with the transit time around the calibration, with the slope fitted like the pulse-wave
 * model (Bayesian, population prior, recent cuff points weigh more). The arm-raise maneuver can add
 * an in-session slope measurement ([SlopeObservation]), so the slope comes from the state the body
 * is in now rather than from the calibration day.
 */
object TransitEstimator {
    /** Prior sensitivity, mmHg per ms of transit time (shorter transit, higher pressure). */
    data class Prior(val sys: Double, val dia: Double, val baseSd: Double)

    val priors = mapOf(
        BpChannel.BCG_PTT to Prior(-0.8, -0.5, 6.0),
        BpChannel.PAT to Prior(-0.5, -0.3, 7.0),
        BpChannel.ECG_PTT to Prior(-0.8, -0.5, 5.0)
    )

    /** Share of the heart → wrist path in the arm: the part the arm's height changes. */
    const val ARM_FRACTION = 0.5

    private const val CUFF_SD = 4.0

    /** The smallest round-to-round transit noise assumed, ms (the accelerometer's 10 ms samples, interpolated). */
    const val MIN_TRANSIT_NOISE_MS = 5.0

    private fun Double.nonZero() = if (abs(this) < 1e-3) (if (this < 0) -1e-3 else 1e-3) else this

    /** A calibration round's transit time this far from the rounds' median is left out, ms. */
    const val MAX_ROUND_SPREAD_MS = 60.0

    /** Prior sd of the slope as a share of it (transit–pressure sensitivity varies about ±50 % between people). */
    private const val PRIOR_REL = 0.5
    private const val HALF_LIFE_DAYS = 14.0
    private const val MIN_POINT_WEIGHT = 0.25
    private const val DRIFT_SD_PER_DAY = 0.15

    /** A slope measured in the session: mmHg per ms, with its sd (from [HydrostaticCalibration]). */
    data class SlopeObservation(val mmHgPerMs: Double, val sd: Double)

    fun estimate(
        calibration: BpCalibration,
        channel: BpChannel,
        transitMs: Double,
        nowMs: Long,
        slope: SlopeObservation? = null,
        hydrostaticMmHg: Double = 0.0
    ): ChannelEstimate? {
        val prior = priors[channel] ?: return null
        // Seated rounds only: standing, the hand hangs far below the heart and the transit time
        // to the wrist changes with that, not with the pressure the cuff measures. Rounds far from
        // the others' median are a mis-detection, not a pressure change.
        val seated = calibration.timedPoints().mapNotNull { (p, at) ->
            p.transit(channel)?.takeIf { !p.standing }?.let { Triple(it, p, at) }
        }
        val median = seated.map { it.first }.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }
        val pts = seated.filter { abs(it.first - median) <= MAX_ROUND_SPREAD_MS }
        if (pts.size < 2) return null
        val w = pts.map { (_, _, at) ->
            val age = (nowMs - at).coerceAtLeast(0) / BpCalibration.DAY_MS.toDouble()
            maxOf(MIN_POINT_WEIGHT, 0.5.pow(age / HALF_LIFE_DAYS))
        }
        val wSum = w.sum()
        fun mean(v: List<Double>) = v.indices.sumOf { w[it] * v[it] } / wSum
        val x0 = mean(pts.map { it.first })
        val sys0 = mean(pts.map { it.second.cuffSystolic.toDouble() })
        val dia0 = mean(pts.map { it.second.cuffDiastolic.toDouble() })

        fun slopeFor(y: (CalibrationPoint) -> Double, y0: Double, w0: Double, obs: SlopeObservation?): Pair<Double, Double> {
            val tau = abs(w0) * PRIOR_REL
            var num = w0 / (tau * tau)
            var den = 1.0 / (tau * tau)
            pts.forEachIndexed { i, (x, p, _) ->
                val dx = x - x0
                num += w[i] * dx * (y(p) - y0) / (CUFF_SD * CUFF_SD)
                den += w[i] * dx * dx / (CUFF_SD * CUFF_SD)
            }
            if (obs != null) {
                num += obs.mmHgPerMs / (obs.sd * obs.sd)
                den += 1.0 / (obs.sd * obs.sd)
            }
            return num / den to sqrt(1.0 / den)
        }
        val (bSys, sdBSys) = slopeFor({ it.cuffSystolic.toDouble() }, sys0, prior.sys, slope)
        // Diastolic follows with the prior's ratio when an in-session slope is given for systolic.
        val (bDia, sdBDia) = slopeFor(
            { it.cuffDiastolic.toDouble() },
            dia0,
            prior.dia,
            slope?.let {
                SlopeObservation(
                    it.mmHgPerMs * prior.dia / prior.sys,
                    it.sd
                )
            }
        )
        val residual =
            sqrt(pts.indices.sumOf { i -> w[i] * (pts[i].second.cuffSystolic - sys0 - bSys * (pts[i].first - x0)).pow(2) } / wSum)
        val dx = transitMs - x0
        val days = (nowMs - pts.maxOf { it.third }).coerceAtLeast(0) / BpCalibration.DAY_MS.toDouble()
        // How much this watch's transit time wanders between rounds at about the same cuff
        // pressure: its measurement noise, in mmHg through the slope. On a real Galaxy Watch6 the
        // rounds scattered by about 30 ms, which makes the channel too noisy to lead; a clean
        // channel with tight rounds is weighed like the pulse-wave channels.
        val spread = sqrt(
            pts.indices.sumOf { i -> w[i] * (pts[i].first - x0 - (pts[i].second.cuffSystolic - sys0) / bSys.nonZero()).pow(2) } / wSum
        )
            .coerceAtLeast(MIN_TRANSIT_NOISE_MS)
        // bSys × spread is the rounds' misfit itself (bSys·(Δx − ΔS/bSys) = −(ΔS − bSys·Δx)), never
        // below the 5 ms floor: the residual is not added again (it was counted twice until 6.5).
        val noiseSys = sqrt(prior.baseSd.pow(2) + (bSys * spread).pow(2) + (DRIFT_SD_PER_DAY * days).pow(2))
        val noiseDia = sqrt((prior.baseSd * 0.7).pow(2) + (bDia * spread).pow(2) + (DRIFT_SD_PER_DAY * days).pow(2))
        // How sure the slope is: uncertainty about the size of a change, not about its direction.
        val scaleSys = abs(sdBSys * dx)
        val scaleDia = abs(sdBDia * dx)
        val local = hydrostaticMmHg * ARM_FRACTION
        return ChannelEstimate(
            channel,
            sys0 + bSys * dx - local,
            dia0 + bDia * dx - local,
            sqrt(noiseSys * noiseSys + scaleSys * scaleSys),
            sqrt(noiseDia * noiseDia + scaleDia * scaleDia),
            parts = mapOf(
                "base" to prior.baseSd,
                "residual" to residual,
                "transitSpreadMs" to spread,
                "slope" to bSys,
                "scale" to scaleSys
            ),
            scaleSys = scaleSys,
            scaleDia = scaleDia
        )
    }
}
