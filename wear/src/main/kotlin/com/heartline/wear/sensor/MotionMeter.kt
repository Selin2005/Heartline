// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * Arm movement during a measurement. Movement changes the PPG shape much more than blood
 * pressure does, so a reading taken while moving is repeated rather than trusted.
 */
interface MotionMeter {
    fun start()

    /** Stops and returns the standard deviation of acceleration magnitude, m/s², or null if unknown. */
    fun stop(): Double?

    /** Mean acceleration vector (x, y, z) of the last recording: gravity, i.e. the arm position. Null if unknown. */
    fun gravity(): List<Double>? = null

    companion object {
        /** Above this the arm wasn't resting (a still arm is typically < 0.15 m/s²). */
        const val MAX_STILL = 0.6

        val NONE = object : MotionMeter {
            override fun start() = Unit

            override fun stop(): Double? = null
        }
    }
}

/** The platform accelerometer (no Samsung SDK needed), sampled at the normal rate. */
class AndroidMotionMeter(context: Context) : MotionMeter, SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val sensor = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var n = 0
    private var sum = 0.0
    private var sumSq = 0.0
    private val axes = DoubleArray(3)

    override fun start() {
        synchronized(this) {
            n = 0
            sum = 0.0
            sumSq = 0.0
            axes.fill(0.0)
        }
        sensor?.let { manager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    override fun stop(): Double? {
        manager?.unregisterListener(this)
        synchronized(this) {
            if (n < 20) return null
            val mean = sum / n
            return sqrt((sumSq / n - mean * mean).coerceAtLeast(0.0))
        }
    }

    override fun gravity(): List<Double>? = synchronized(this) { if (n < 20) null else axes.map { it / n } }

    override fun onSensorChanged(event: SensorEvent) {
        val (x, y, z) = Triple(event.values[0].toDouble(), event.values[1].toDouble(), event.values[2].toDouble())
        val magnitude = sqrt(x * x + y * y + z * z)
        synchronized(this) {
            n++
            sum += magnitude
            sumSq += magnitude * magnitude
            axes[0] += x
            axes[1] += y
            axes[2] += z
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
