// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.diag

import com.heartline.shared.bp.BpSessionHeader
import com.heartline.shared.bp.SensorStream
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A raw session as readable files for the export: the header as JSON and one CSV per sensor stream. */
object SessionCsv {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }
    private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

    fun header(header: BpSessionHeader): String = json.encodeToString(header)

    /** File name of [stream] in the session folder ("PPG_ON_DEMAND.csv", "android.accelerometer.csv"). */
    fun fileName(stream: SensorStream): String = stream.name.replace(Regex("[^A-Za-z0-9._#-]"), "_") + ".csv"

    /**
     * One row per sample: the timestamp (ns since 1970, and as local time) and every column; an
     * empty cell where the sensor had no value.
     */
    fun write(stream: SensorStream, out: OutputStream) {
        val w = out.bufferedWriter(Charsets.UTF_8)
        w.write("timestampNs,time,")
        w.write(stream.columns.joinToString(","))
        w.write("\n")
        val c = stream.columns.size
        for (i in 0 until stream.size) {
            val t = stream.timestampsNs[i]
            w.write(t.toString())
            w.write(",")
            w.write(TIME.format(Instant.ofEpochSecond(0, t)))
            for (j in 0 until c) {
                w.write(",")
                val v = stream.values[i * c + j]
                if (v.isFinite()) w.write(number(v))
            }
            w.write("\n")
        }
        w.flush()
    }

    private fun number(v: Float): String = if (v == kotlin.math.floor(v) &&
        kotlin.math.abs(v) < 1e7f
    ) {
        v.toLong().toString()
    } else {
        String.format(Locale.ROOT, "%.6g", v)
    }
}
