// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.monitor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import com.heartline.wear.diag.RawCapture

/** Tracks recent steps so rhythm checks only use windows where the wearer is still. */
fun interface MotionMonitor {
    /** True if the wearer moved (took steps) since [sinceMs]. */
    fun movedSince(sinceMs: Long): Boolean

    /**
     * True if the wearer moved between [fromMs] and [toMs]. Heart-rate readings can arrive minutes
     * late, so a reading is judged by movement around its own time, not by any movement since.
     */
    fun movedBetween(fromMs: Long, toMs: Long): Boolean = movedSince(fromMs)
}

/**
 * When movements happened, by the sensor event's own time (events can arrive batched too).
 * Keeps the last [keepMs].
 */
internal class MoveTimes(private val keepMs: Long = 20 * 60_000L) {
    private val times = ArrayDeque<Long>()

    @Synchronized fun add(atMs: Long) {
        // One mark per half second is plenty.
        if (times.isNotEmpty() && atMs - times.last() < 500) return
        times.addLast(atMs)
        while (times.isNotEmpty() && atMs - times.first() > keepMs) times.removeFirst()
    }

    @Synchronized fun any(fromMs: Long, toMs: Long) = times.any { it in fromMs..toMs }

    @Synchronized fun latest() = times.lastOrNull() ?: 0L

    companion object {
        /** A sensor event's time (elapsed-realtime nanoseconds) as wall-clock milliseconds. */
        fun wallMs(event: SensorEvent) = System.currentTimeMillis() - (SystemClock.elapsedRealtimeNanos() - event.timestamp) / 1_000_000
    }
}

class StepMotionMonitor(context: Context) :
    MotionMonitor,
    SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val steps = MoveTimes()

    fun start() {
        manager?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)?.let {
            manager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    fun stop() = manager?.unregisterListener(this)

    override fun onSensorChanged(event: SensorEvent) {
        RawCapture.android(event)
        steps.add(MoveTimes.wallMs(event))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun movedSince(sinceMs: Long) = steps.latest() >= sinceMs

    override fun movedBetween(fromMs: Long, toMs: Long) = steps.any(fromMs, toMs)
}

/**
 * Whether the watch is on the wrist (Android's off-body sensor, where the watch has one) and when
 * the arm last moved (accelerometer). Steps alone miss typing or gesturing, which spoil the
 * beat-to-beat intervals as much as walking does.
 */
class WristState(context: Context) :
    MotionMonitor,
    SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val offBodySensor = manager?.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT, true)
    private val accelerometer = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    @Volatile var offBody = false
        private set

    /** Whether the off-body sensor has reported at all since [start] (it can be late, or missing). */
    @Volatile var offBodyReported = false
        private set

    private val moves = MoveTimes()
    private var average = Double.NaN

    fun start() {
        offBodySensor?.let { manager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        accelerometer?.let { manager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    fun stop() = manager?.unregisterListener(this)

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT -> {
                offBody = event.values.firstOrNull() == 0f
                offBodyReported = true
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val (x, y, z) = event.values
                val magnitude = kotlin.math.sqrt((x * x + y * y + z * z).toDouble())
                average = if (average.isNaN()) magnitude else average * 0.9 + magnitude * 0.1
                if (kotlin.math.abs(magnitude - average) > MOVE_MS2) moves.add(MoveTimes.wallMs(event))
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun movedSince(sinceMs: Long) = moves.latest() >= sinceMs

    override fun movedBetween(fromMs: Long, toMs: Long) = moves.any(fromMs, toMs)

    private companion object {
        /** A resting arm stays within about 0.15 m/s² of its average; this is a clear movement. */
        const val MOVE_MS2 = 1.0
    }
}
