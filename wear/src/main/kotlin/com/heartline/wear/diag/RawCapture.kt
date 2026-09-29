// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.diag

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.os.Build
import android.os.SystemClock
import com.heartline.datalayer.diag.HLog
import com.heartline.shared.bp.BpSessionLog
import com.heartline.shared.diag.RawSessions
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.ValueKey
import java.io.File
import java.lang.reflect.Modifier

/**
 * The watch's raw sensor recorder: every value from every Samsung tracker and Android sensor the
 * app listens to, sample by sample, in [RawSessions] (one file per measurement, plus `other` for
 * background monitoring). Sensor listeners call [sdk] or [android]; measurement screens call
 * [begin] and [end]. Only records while diagnostic logs are on ([setEnabled]).
 */
object RawCapture {
    /** Raw sessions on the watch (the text log has its own 20 MB); older ones move to the phone. */
    const val BUDGET_BYTES = 30L * 1024 * 1024

    @Volatile private var sessions: RawSessions? = null

    fun init(context: Context, version: String, onSaved: (File) -> Unit) {
        if (sessions != null) return
        sessions = RawSessions(
            File(context.filesDir, "raw"),
            BUDGET_BYTES,
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            appVersion = version,
            onSaved = onSaved,
        )
    }

    fun setEnabled(on: Boolean) {
        val s = sessions ?: return
        if (!on && s.enabled) s.clear()
        s.enabled = on
    }

    /**
     * Starts a measurement session ("ecg", "spo2", "bia", …); returns its id for [end]. A
     * [background] one (a monitoring window) doesn't interrupt a measurement that is running.
     */
    fun begin(kind: String, notes: Map<String, String> = emptyMap(), background: Boolean = false): String =
        sessions?.begin(kind, notes, background).orEmpty()

    fun end(id: String, values: Map<String, Double?> = emptyMap(), notes: Map<String, String> = emptyMap()) {
        sessions?.end(id, values, notes)
    }

    /** Ends measurement [id] without keeping it (the blood-pressure flow keeps its own, complete log). */
    fun discard(id: String) {
        sessions?.discard(id)
    }

    fun event(type: String, detail: String = "") {
        sessions?.event(type, detail)
    }

    /** The blood-pressure flow records its own complete session; a copy is kept with the others. */
    fun saveBpSession(log: BpSessionLog) {
        sessions?.saveLog("bp", log)
    }

    fun files(): List<File> = sessions?.files().orEmpty()

    fun sizeBytes(): Long = sessions?.sizeBytes() ?: 0

    /** Closes the background session so an export has everything. */
    fun flush() {
        sessions?.flush()
        sessions?.awaitWritten()
    }

    fun clear() {
        sessions?.clear()
    }

    /** Every value of [points] from Samsung tracker [tracker] (a TrackerKind / HealthTrackerType name). */
    fun sdk(tracker: String, points: List<DataPoint>) {
        val s = sessions ?: return
        if (!s.enabled || points.isEmpty()) return
        runCatching {
            val keys = SdkKeys.forTracker(tracker)
            val t = LongArray(points.size) { points[it].timestamp * 1_000_000 }
            s.rows(tracker, keys.scalarNames, t, points.map { p -> FloatArray(keys.scalars.size) { i -> number(p.value(keys.scalars[i])) } })
            // List values (the heart-rate tracker's IBIs and their statuses): one row per element.
            for ((i, key) in keys.lists.withIndex()) {
                val times = ArrayList<Long>()
                val rows = ArrayList<FloatArray>()
                points.forEach { p ->
                    (p.value(key) as? List<*>)?.forEachIndexed { index, v ->
                        times += p.timestamp * 1_000_000
                        rows += floatArrayOf(number(v), index.toFloat())
                    }
                }
                if (times.isNotEmpty()) s.rows("$tracker.${keys.listNames[i]}", listOf("value", "index"), times.toLongArray(), rows)
            }
        }.onFailure { HLog.w(TAG, "raw $tracker: ${it.message}") }
    }

    /** Values that come from elsewhere (Health Services): one row per (wall-clock ms, values). */
    fun values(stream: String, columns: List<String>, rows: List<Pair<Long, FloatArray>>) {
        val s = sessions ?: return
        if (!s.enabled || rows.isEmpty()) return
        s.rows(stream, columns, LongArray(rows.size) { rows[it].first * 1_000_000 }, rows.map { it.second })
    }

    /** Every value of an Android sensor event (accelerometer, gyroscope, rotation, …). */
    fun android(event: SensorEvent) {
        val s = sessions ?: return
        if (!s.enabled) return
        // Sensor time is elapsed-realtime ns; the sessions use the wall clock.
        val wallNs = event.timestamp + (System.currentTimeMillis() * 1_000_000 - SystemClock.elapsedRealtimeNanos())
        val n = event.values.size
        val columns = List(n) { "v$it" } + "accuracy"
        s.rows(
            "android.${sensorName(event.sensor)}",
            columns,
            longArrayOf(wallNs),
            listOf(FloatArray(n + 1) { if (it < n) event.values[it] else event.accuracy.toFloat() }),
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun DataPoint.value(key: ValueKey<*>): Any? = runCatching { getValue(key as ValueKey<Any?>) }.getOrNull()

    private fun sensorName(sensor: Sensor): String = sensor.stringType.substringAfterLast('.').ifEmpty { "type${sensor.type}" }

    private fun number(v: Any?): Float = when (v) {
        is Number -> v.toFloat()
        is Boolean -> if (v) 1f else 0f
        else -> Float.NaN
    }

    const val TAG = "Heartline/Raw"
}

/**
 * The Samsung SDK's value keys by tracker, found from the public `ValueKey.<Type>Set` fields, so a
 * key the SDK adds later is recorded too. Scalars become columns; list values (IBIs) their own stream.
 */
internal class SdkKeys(val scalarNames: List<String>, val scalars: List<ValueKey<*>>, val listNames: List<String>, val lists: List<ValueKey<*>>) {
    companion object {
        private val SETS = mapOf(
            "ECG_ON_DEMAND" to listOf("EcgSet"),
            "PPG_ON_DEMAND" to listOf("PpgSet"),
            "PPG_CONTINUOUS" to listOf("PpgSet", "PpgGreenSet", "PpgIrSet", "PpgRedSet"),
            "HEART_RATE_CONTINUOUS" to listOf("HeartRateSet"),
            "SPO2_ON_DEMAND" to listOf("SpO2Set"),
            "SKIN_TEMPERATURE_ON_DEMAND" to listOf("SkinTemperatureSet"),
            "SKIN_TEMPERATURE_CONTINUOUS" to listOf("SkinTemperatureSet"),
            "BIA_ON_DEMAND" to listOf("BiaSet"),
            "MF_BIA_ON_DEMAND" to listOf("MfBiaSet"),
            "EDA_CONTINUOUS" to listOf("EdaSet"),
            "ACCELEROMETER_CONTINUOUS" to listOf("AccelerometerSet"),
            "SWEAT_LOSS" to listOf("SweatLossSet"),
        )
        private val cache = mutableMapOf<String, SdkKeys>()

        @Synchronized
        fun forTracker(tracker: String): SdkKeys = cache.getOrPut(tracker) {
            val sets = SETS[tracker] ?: SETS.values.flatten().distinct()
            val scalars = linkedMapOf<ValueKey<*>, String>()
            val lists = linkedMapOf<ValueKey<*>, String>()
            for (set in sets) {
                val type = runCatching { Class.forName("${ValueKey::class.java.name}\$$set") }.getOrNull() ?: continue
                for (field in type.fields) {
                    if (!Modifier.isStatic(field.modifiers) || !ValueKey::class.java.isAssignableFrom(field.type)) continue
                    val key = runCatching { field.get(null) as? ValueKey<*> }.getOrNull() ?: continue
                    if (key in scalars || key in lists) continue
                    val name = if (sets.size > 1) "$set.${field.name}" else field.name
                    if (field.genericType.typeName.contains("java.util.List")) lists[key] = name else scalars[key] = name
                }
            }
            SdkKeys(scalars.values.toList(), scalars.keys.toList(), lists.values.toList(), lists.keys.toList())
        }
    }
}
