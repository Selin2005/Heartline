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
    val parts: Map<String, Double> = emptyMap()
)

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
        val widened = consistent(channels).map { c ->
            val f = factors[c.channel] ?: 1.0
            c.copy(sdSys = c.sdSys * f, sdDia = c.sdDia * f)
        }
        val (sys, sdSys, wSys) = combine(widened.map { it.systolic }, widened.map { it.sdSys })
        val (dia, sdDia, _) = combine(widened.map { it.diastolic }, widened.map { it.sdDia })
        return Fused(sys, dia, sdSys, sdDia, widened.mapIndexed { i, c -> c.copy(weight = wSys[i]) })
    }

    /**
     * The channels without any that contradicts the others: one farther from the median of all
     * channels than [MAX_CONFLICT_MMHG] or 3 of its own sd is a failed measurement (a mis-detected
     * transit time, say), and averaging it in would move the number by tens of mmHg. With fewer
     * than 3 channels there is no majority, so nothing is left out.
     */
    fun consistent(channels: List<ChannelEstimate>): List<ChannelEstimate> {
        if (channels.size < 3) return channels
        val sorted = channels.map { it.systolic }.sorted()
        val median = if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
        return channels.filter { abs(it.systolic - median) <= maxOf(MAX_CONFLICT_MMHG, 3 * it.sdSys) }.ifEmpty { channels }
    }

    const val MAX_CONFLICT_MMHG = 20.0

    data class Fused(val systolic: Double, val diastolic: Double, val sdSys: Double, val sdDia: Double, val channels: List<ChannelEstimate>)

    /** Inverse-variance mean, its sd (widened by the Birge ratio when > 1) and the normalised weights. */
    internal fun combine(x: List<Double>, sd: List<Double>): Triple<Double, Double, List<Double>> {
        val w = sd.map { 1.0 / it.coerceAtLeast(1.0).pow(2) }
        val total = w.sum()
        val mean = x.indices.sumOf { w[it] * x[it] } / total
        var fusedSd = sqrt(1.0 / total)
        if (x.size > 1) {
            val chi2 = x.indices.sumOf { w[it] * (x[it] - mean).pow(2) } / (x.size - 1)
            if (chi2 > 1) fusedSd *= sqrt(chi2)
        }
        return Triple(mean, fusedSd, w.map { it / total })
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
        val sdSys = sqrt(prior.baseSd.pow(2) + residual.pow(2) + (sdBSys * dx).pow(2) + (DRIFT_SD_PER_DAY * days).pow(2))
        val sdDia = sqrt((prior.baseSd * 0.7).pow(2) + (sdBDia * dx).pow(2) + (DRIFT_SD_PER_DAY * days).pow(2))
        val local = hydrostaticMmHg * ARM_FRACTION
        return ChannelEstimate(channel, sys0 + bSys * dx - local, dia0 + bDia * dx - local, sdSys, sdDia)
    }
}
