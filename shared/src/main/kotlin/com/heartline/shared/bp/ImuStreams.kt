// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.bp

import kotlin.math.asin
import kotlin.math.sqrt

/** Motion sensor recordings of one session (see [BpSessionLog]); null when the sensor is missing. */
data class ImuStreams(val accel: SensorStream?, val gyro: SensorStream?, val rotation: SensorStream?) {
    fun all() = listOfNotNull(accel, gyro, rotation)

    /** Mean acceleration vector (gravity, i.e. the arm position) in the watch's frame. */
    fun meanGravity(fromNs: Long = Long.MIN_VALUE, toNs: Long = Long.MAX_VALUE): List<Double>? {
        val a = accel ?: return null
        val idx = (0 until a.size).filter { a.timestampsNs[it] in fromNs..toNs }
        if (idx.size < 20) return null
        return List(3) { c -> idx.sumOf { a.values[it * 3 + c].toDouble() } / idx.size }
    }

    /** Standard deviation of the acceleration magnitude between two times, m/s² (movement), or null. */
    fun motionSd(fromNs: Long = Long.MIN_VALUE, toNs: Long = Long.MAX_VALUE): Double? {
        val a = accel ?: return null
        val m = (0 until a.size).filter { a.timestampsNs[it] in fromNs..toNs }
            .map { i -> sqrt((0 until 3).sumOf { c -> a.values[i * 3 + c].toDouble().let { it * it } }) }
        if (m.size < 20) return null
        val mean = m.average()
        return sqrt(m.sumOf { (it - mean) * (it - mean) } / m.size)
    }

    /** Where |forearm pitch| is above [thresholdDeg] (the raised part of the maneuver), or null. */
    fun raisedWindow(thresholdDeg: Double = 45.0): LongRange? {
        val a = accel ?: return null
        val up = (0 until a.size).filter { i ->
            val g = List(3) { c -> a.values[i * 3 + c].toDouble() }
            (pitchDeg(g)?.let { kotlin.math.abs(it) } ?: 0.0) > thresholdDeg
        }
        if (up.size < 20) return null
        return a.timestampsNs[up.first()]..a.timestampsNs[up.last()]
    }

    companion object {
        const val ACCEL = "accel"
        const val GYRO = "gyro"
        const val ROTATION = "rotation"
        val EMPTY = ImuStreams(null, null, null)

        /**
         * Elevation of the forearm above the horizontal, degrees, from a gravity vector:
         * sign × asin(g_x / |g|). The straps sit at 12 and 6 o'clock, so the forearm runs along the
         * watch's x axis (3 ↔ 9 o'clock); worn on the left wrist with the crown towards the hand,
         * +x points to the hand and [sign] +1 makes a raised hand positive. The arm-raise maneuver
         * measures the sign for other ways of wearing it ([HydrostaticCalibration]).
         */
        fun pitchDeg(gravity: List<Double>, sign: Double = 1.0): Double? {
            if (gravity.size != 3) return null
            val n = sqrt(gravity.sumOf { it * it })
            if (n < 5.0) return null
            return sign * Math.toDegrees(asin((gravity[0] / n).coerceIn(-1.0, 1.0)))
        }
    }
}
