package com.heartline.shared.bp

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sign

/**
 * Readings that call for a cuff check (and, with symptoms, for help). Wellness wording only:
 * thresholds follow the AHA (≥180 and/or ≥120 "severe", and < 90/60 as low pressure).
 */
enum class BpSafety {
    NONE,
    VERY_HIGH,
    LOW
    ;

    companion object {
        fun of(systolic: Int, diastolic: Int): BpSafety = when {
            systolic >= 180 || diastolic >= 120 -> VERY_HIGH
            systolic < 90 || diastolic < 60 -> LOW
            else -> NONE
        }
    }
}

/**
 * A reading that needs confirming (beyond the calibration, or a [BpSafety] level) is confirmed
 * by a second one within [WINDOW_MS] that points the same way and lands close to it.
 */
object BpConfirmation {
    const val WINDOW_MS = 10 * 60_000L

    fun needsConfirming(e: BpEstimate) = e.beyondCalibration || e.safety != BpSafety.NONE

    fun confirms(previous: BpEstimate, previousAtMs: Long, current: BpEstimate, nowMs: Long): Boolean {
        if (nowMs - previousAtMs !in 0..WINDOW_MS) return false
        if (!needsConfirming(previous) || !needsConfirming(current)) return false
        val sameWay =
            sign(previous.deltaSystolic) == sign(current.deltaSystolic) ||
                (previous.safety == current.safety && current.safety != BpSafety.NONE)
        val tolerance = maxOf(15, 2 * maxOf(previous.uncertaintySys, current.uncertaintySys))
        return sameWay && abs(previous.systolic - current.systolic) <= tolerance
    }
}

/**
 * Detects that the user's pressure has moved away from the calibration, so the app asks for a
 * cuff reading instead of repeating an error (Tae et al. 2026: re-calibrate at change points).
 *
 * Two signals: a two-sided CUSUM on watch-minus-cuff residuals from cuff checks, and a run of
 * recent readings flagged beyond the calibration in the same direction.
 */
object BpDrift {
    /** CUSUM slack and decision limit, mmHg. */
    const val SLACK = 4.0
    const val LIMIT = 15.0
    const val RUN = 3

    enum class Direction { HIGHER, LOWER }

    /** @param residuals watch − cuff systolic, oldest first. @return the direction the cuff has moved to, if any. */
    fun fromResiduals(residuals: List<Double>): Direction? {
        var up = 0.0
        var down = 0.0
        var result: Direction? = null
        for (r in residuals) {
            // The watch reads high (positive residual) when the real pressure has gone down.
            up = maxOf(0.0, up + r - SLACK)
            down = maxOf(0.0, down - r - SLACK)
            result = when {
                up > LIMIT -> Direction.LOWER
                down > LIMIT -> Direction.HIGHER
                else -> result
            }
            if (up > LIMIT || down > LIMIT) {
                up = 0.0
                down = 0.0
            }
        }
        return result
    }

    /** @param recent (beyondCalibration, deltaSystolic) of the latest readings, newest last. */
    fun fromReadings(recent: List<Pair<Boolean, Double>>): Direction? {
        if (recent.size < RUN) return null
        val last = recent.takeLast(RUN)
        if (!last.all { it.first }) return null
        return when {
            last.all { it.second > 0 } -> Direction.HIGHER
            last.all { it.second < 0 } -> Direction.LOWER
            else -> null
        }
    }
}

/**
 * Split-conformal half-width from this user's own watch-vs-cuff errors: with n checks, the
 * ⌈(n+1)·coverage⌉-th smallest absolute error covers a new reading with probability ≥ coverage
 * (assuming exchangeable errors). Needs [MIN_CHECKS] checks.
 */
object BpConformal {
    const val MIN_CHECKS = 5

    fun halfWidth(absErrors: List<Double>, coverage: Double = 0.8): Double? {
        if (absErrors.size < MIN_CHECKS) return null
        val sorted = absErrors.map { abs(it) }.sorted()
        val k = ceil((sorted.size + 1) * coverage).toInt().coerceAtMost(sorted.size)
        return sorted[k - 1]
    }
}
