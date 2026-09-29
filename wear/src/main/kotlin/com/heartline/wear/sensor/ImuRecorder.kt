// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import com.heartline.shared.bp.BpSessionRecorder
import com.heartline.shared.bp.ImuStreams
import com.heartline.wear.diag.RawCapture
import kotlin.math.sqrt

/**
 * The watch's motion sensors during a blood-pressure session, recorded in full: accelerometer
 * (as fast as the watch allows, for the wrist ballistocardiogram), gyroscope and game rotation
 * vector (forearm angle, for the hydrostatic correction). Timestamps are the sensor's own,
 * moved to wall-clock nanoseconds so they line up with the PPG and ECG points.
 */
interface ImuRecorder : MotionMeter {
    /** The streams of the last recording (empty ones when a sensor is missing). */
    fun streams(): ImuStreams

    companion object {
        val NONE = object : ImuRecorder {
            override fun start() = Unit

            override fun stop(): Double? = null

            override fun streams() = ImuStreams.EMPTY
        }
    }
}

class AndroidImuRecorder(context: Context) : ImuRecorder, SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val accel = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyro = manager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val rotation = manager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private var accelW = writer(ImuStreams.ACCEL, "x", "y", "z")
    private var gyroW = writer(ImuStreams.GYRO, "x", "y", "z")
    private var rotationW = writer(ImuStreams.ROTATION, "x", "y", "z", "w")

    /** Sensor clock (elapsed realtime) → wall clock, ns. */
    @Volatile private var offsetNs = 0L

    private fun writer(name: String, vararg columns: String) = BpSessionRecorder.StreamWriter(name, columns.toList())

    override fun start() {
        synchronized(this) {
            accelW = writer(ImuStreams.ACCEL, "x", "y", "z")
            gyroW = writer(ImuStreams.GYRO, "x", "y", "z")
            rotationW = writer(ImuStreams.ROTATION, "x", "y", "z", "w")
            offsetNs = System.currentTimeMillis() * 1_000_000L - SystemClock.elapsedRealtimeNanos()
        }
        // As fast as the hardware goes: the ballistocardiogram needs ≥ 100 Hz.
        listOfNotNull(accel, gyro, rotation).forEach { manager?.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
    }

    override fun stop(): Double? {
        manager?.unregisterListener(this)
        val a = streams().accel ?: return null
        if (a.size < 20) return null
        val mags = DoubleArray(a.size) { i -> sqrt((0 until 3).sumOf { c -> a.values[i * 3 + c].toDouble().let { it * it } }) }
        val mean = mags.average()
        return sqrt(mags.sumOf { (it - mean) * (it - mean) } / mags.size)
    }

    override fun gravity(): List<Double>? = streams().meanGravity()

    @Synchronized
    override fun streams() = ImuStreams(
        accelW.snapshot().takeIf { it.size > 0 },
        gyroW.snapshot().takeIf { it.size > 0 },
        rotationW.snapshot().takeIf { it.size > 0 },
    )

    override fun onSensorChanged(event: SensorEvent) {
        RawCapture.android(event)
        val t = event.timestamp + offsetNs
        val v = event.values
        synchronized(this) {
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> accelW.append(t, v[0], v[1], v[2])
                Sensor.TYPE_GYROSCOPE -> gyroW.append(t, v[0], v[1], v[2])
                Sensor.TYPE_GAME_ROTATION_VECTOR -> rotationW.append(t, v[0], v[1], v[2], if (v.size > 3) v[3] else Float.NaN)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
