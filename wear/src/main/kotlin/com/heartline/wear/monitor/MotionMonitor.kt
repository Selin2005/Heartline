// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.monitor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.heartline.wear.diag.RawCapture

/** Tracks recent steps so rhythm checks only use windows where the wearer is still. */
fun interface MotionMonitor {
    /** True if the wearer moved (took steps) since [sinceMs]. */
    fun movedSince(sinceMs: Long): Boolean
}

class StepMotionMonitor(context: Context, private val now: () -> Long = System::currentTimeMillis) :
    MotionMonitor,
    SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    @Volatile private var lastStepMs = 0L

    fun start() {
        manager?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)?.let {
            manager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    fun stop() = manager?.unregisterListener(this)

    override fun onSensorChanged(event: SensorEvent) {
        RawCapture.android(event)
        lastStepMs = now()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun movedSince(sinceMs: Long) = lastStepMs >= sinceMs
}

/**
 * Whether the watch is on the wrist (Android's off-body sensor, where the watch has one) and when
 * the arm last moved (accelerometer). Steps alone miss typing or gesturing, which spoil the
 * beat-to-beat intervals as much as walking does.
 */
class WristState(context: Context, private val now: () -> Long = System::currentTimeMillis) :
    MotionMonitor,
    SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val offBodySensor = manager?.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT, true)
    private val accelerometer = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    @Volatile var offBody = false
        private set

    @Volatile private var lastMoveMs = 0L
    private var average = Double.NaN

    fun start() {
        offBodySensor?.let { manager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        accelerometer?.let { manager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    fun stop() = manager?.unregisterListener(this)

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT -> offBody = event.values.firstOrNull() == 0f
            Sensor.TYPE_ACCELEROMETER -> {
                val (x, y, z) = event.values
                val magnitude = kotlin.math.sqrt((x * x + y * y + z * z).toDouble())
                average = if (average.isNaN()) magnitude else average * 0.9 + magnitude * 0.1
                if (kotlin.math.abs(magnitude - average) > MOVE_MS2) lastMoveMs = now()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun movedSince(sinceMs: Long) = lastMoveMs >= sinceMs

    private companion object {
        /** A resting arm stays within about 0.15 m/s² of its average; this is a clear movement. */
        const val MOVE_MS2 = 1.0
    }
}
