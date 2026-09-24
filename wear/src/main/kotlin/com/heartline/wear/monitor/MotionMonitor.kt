package com.heartline.wear.monitor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

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
        lastStepMs = now()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun movedSince(sinceMs: Long) = lastStepMs >= sinceMs
}
