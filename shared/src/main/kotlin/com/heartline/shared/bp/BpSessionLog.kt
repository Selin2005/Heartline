// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.bp

import com.heartline.shared.sync.Protocol
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * Everything a blood-pressure session recorded, so the algorithm can be developed and replayed
 * on real watch data (see docs/algorithms/BP_ALGORITHM.md, "Raw session logs"):
 * - [header]: device, versions, sensor capabilities and actual rates, timed events (phases,
 *   window choice, errors), every intermediate value (features, state, per-channel estimates,
 *   the fused result) and, later, the cuff reading the user entered for it;
 * - [streams]: every sensor sample exactly as received, each with its own timestamp.
 *
 * On disk and in transit ([encode]/[decode]) it is gzip of: "HLBP", format, the header as JSON,
 * then each stream (name, column names, n, n × int64 timestamp ns, n × columns float32),
 * big-endian. tools/bp-ml/read_session.py reads the same layout.
 */
data class BpSessionLog(val header: BpSessionHeader, val streams: List<SensorStream>) {
    fun stream(name: String): SensorStream? = streams.firstOrNull { it.name == name }

    fun encode(): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(GZIPOutputStream(bytes)).use { out ->
            out.write(MAGIC)
            out.writeInt(FORMAT)
            val json = Protocol.json.encodeToString(header).encodeToByteArray()
            out.writeInt(json.size)
            out.write(json)
            out.writeInt(streams.size)
            for (s in streams) {
                out.writeUTF(s.name)
                out.writeInt(s.columns.size)
                s.columns.forEach { out.writeUTF(it) }
                out.writeInt(s.size)
                for (i in 0 until s.size) out.writeLong(s.timestampsNs[i])
                for (i in 0 until s.size * s.columns.size) out.writeFloat(s.values[i])
            }
        }
        return bytes.toByteArray()
    }

    companion object {
        const val FORMAT = 1
        private val MAGIC = "HLBP".encodeToByteArray()

        fun decode(bytes: ByteArray): BpSessionLog = DataInputStream(GZIPInputStream(ByteArrayInputStream(bytes))).use { input ->
            val magic = ByteArray(4).also { input.readFully(it) }
            require(magic.contentEquals(MAGIC)) { "not a Heartline BP session" }
            val format = input.readInt()
            require(format <= FORMAT) { "session format $format is newer than $FORMAT" }
            val json = ByteArray(input.readInt()).also { input.readFully(it) }
            val header = Protocol.json.decodeFromString<BpSessionHeader>(json.decodeToString())
            val streams = List(input.readInt()) {
                val name = input.readUTF()
                val columns = List(input.readInt()) { input.readUTF() }
                val n = input.readInt()
                val t = LongArray(n) { input.readLong() }
                val v = FloatArray(n * columns.size) { input.readFloat() }
                SensorStream(name, columns, t, v)
            }
            BpSessionLog(header, streams)
        }
    }
}

/**
 * One sensor's samples. [values] is row-major: sample i, column c is at i × columns.size + c.
 * NaN marks a value the sensor didn't deliver for that sample.
 */
class SensorStream(val name: String, val columns: List<String>, val timestampsNs: LongArray, val values: FloatArray) {
    val size: Int get() = timestampsNs.size

    fun column(name: String): FloatArray? {
        val c = columns.indexOf(name).takeIf { it >= 0 } ?: return null
        return FloatArray(size) { values[it * columns.size + c] }
    }

    /** Samples per second from the timestamps (0 when fewer than 2). */
    fun rateHz(): Double {
        if (size < 2) return 0.0
        val span = (timestampsNs.last() - timestampsNs.first()) / 1e9
        return if (span <= 0) 0.0 else (size - 1) / span
    }

    override fun equals(other: Any?) = other is SensorStream &&
        name == other.name &&
        columns == other.columns &&
        timestampsNs.contentEquals(other.timestampsNs) &&
        values.contentEquals(other.values)

    override fun hashCode() = name.hashCode() * 31 + columns.hashCode()
}

@Serializable
data class BpSessionHeader(
    val id: String,
    val startedAtMs: Long,
    /** "measure" or "calibration". */
    val kind: String,
    /** "quick" (PPG + motion) or "precise" (ECG + PPG + motion, maneuver). */
    val mode: String = MODE_QUICK,
    val device: String = "",
    val appVersion: String = "",
    val algorithm: Int = 0,
    /** Which trackers and channels were available / used, and the requested vs actual rates. */
    val capabilities: Map<String, String> = emptyMap(),
    val events: List<SessionEvent> = emptyList(),
    /** Every intermediate and final number, flat ("green.upstrokeMs", "fusion.systolic", …). */
    val values: Map<String, Double> = emptyMap(),
    val notes: Map<String, String> = emptyMap(),
    /** Calibration round, or the reading id the phone links a cuff check to. */
    val calibrationRound: Int? = null,
    val recordId: String? = null,
    /** The reference cuff reading, when known (calibration rounds; filled in on the phone for checks). */
    val cuffSystolic: Int? = null,
    val cuffDiastolic: Int? = null,
    val cuffPulse: Int? = null
) {
    companion object {
        const val MODE_QUICK = "quick"
        const val MODE_PRECISE = "precise"
    }
}

/** [tMs] since the session start. */
@Serializable
data class SessionEvent(val tMs: Long, val type: String, val detail: String = "")

/**
 * Collects a session while it runs. Thread-safe: sensor callbacks append from their own threads.
 * Streams grow without a fixed size; [build] snapshots them.
 */
class BpSessionRecorder(
    private val id: String,
    private val kind: String,
    private val startedAtMs: Long,
    private val clockMs: () -> Long = System::currentTimeMillis
) {
    private val writers = linkedMapOf<String, StreamWriter>()
    private val events = mutableListOf<SessionEvent>()
    private val values = linkedMapOf<String, Double>()
    private val notes = linkedMapOf<String, String>()
    private val capabilities = linkedMapOf<String, String>()

    @Synchronized
    fun stream(name: String, vararg columns: String): StreamWriter = writers.getOrPut(name) { StreamWriter(name, columns.toList()) }

    /** Adds a stream recorded elsewhere (e.g. by the IMU recorder), replacing one of the same name. */
    @Synchronized
    fun attach(stream: SensorStream) {
        writers[stream.name] = StreamWriter(stream.name, stream.columns).also { w ->
            for (i in 0 until stream.size) {
                w.append(
                    stream.timestampsNs[i],
                    *FloatArray(stream.columns.size) {
                        stream.values[
                            i *
                                stream.columns.size +
                                it
                        ]
                    }
                )
            }
        }
    }

    @Synchronized
    fun event(type: String, detail: String = "") {
        events += SessionEvent(clockMs() - startedAtMs, type, detail)
    }

    @Synchronized
    fun value(key: String, v: Double?) {
        if (v != null && !v.isNaN()) values[key] = v
    }

    /** Every numeric field of [f] under "[prefix].name". */
    fun features(prefix: String, f: PpgFeatureVector?) {
        f ?: return
        value("$prefix.heartRateBpm", f.heartRateBpm)
        value("$prefix.upstrokeMs", f.upstrokeMs)
        value("$prefix.width50Ms", f.width50Ms)
        value("$prefix.width25Ms", f.width25Ms)
        value("$prefix.areaRatio", f.areaRatio)
        value("$prefix.apgBa", f.apgBa)
        value("$prefix.apgDa", f.apgDa)
        value("$prefix.reflectionDelayMs", f.reflectionDelayMs)
        value("$prefix.reflectionIndex", f.reflectionIndex)
        value("$prefix.quality", f.quality)
        value("$prefix.beats", f.beats.toDouble())
        value("$prefix.rmssdMs", f.rmssdMs)
        value("$prefix.skewness", f.skewness)
        value("$prefix.ibiCv", f.ibiCv)
        value("$prefix.ectopicCount", f.ectopicCount.toDouble())
        value("$prefix.rejectedFraction", f.rejectedFraction)
        value("$prefix.hrSlopeBpmPerS", f.hrSlopeBpmPerS)
        value("$prefix.perfusionIndex", f.perfusionIndex)
        value("$prefix.amplitudeTrend", f.amplitudeTrend)
        value("$prefix.rr.nRmssd", f.rrNRmssd)
        value("$prefix.rr.entropy", f.rrEntropy)
        value("$prefix.rr.turningPoint", f.rrTurningPoint)
        value("$prefix.rr.count", f.rrCount.toDouble())
        value("$prefix.inverted", if (f.inverted) 1.0 else 0.0)
        value("$prefix.version", f.version.toDouble())
    }

    @Synchronized
    fun note(key: String, v: String) {
        notes[key] = v
    }

    @Synchronized
    fun capability(key: String, v: String) {
        capabilities[key] = v
    }

    @Synchronized
    fun build(header: BpSessionHeader.() -> BpSessionHeader = { this }): BpSessionLog {
        val streams = writers.values.map { it.snapshot() }
        streams.forEach { s -> capabilities["rate.${s.name}"] = "%.2f".format(java.util.Locale.ROOT, s.rateHz()) }
        val base = BpSessionHeader(
            id = id,
            startedAtMs = startedAtMs,
            kind = kind,
            capabilities = capabilities.toMap(),
            events = events.toList(),
            values = values.toMap(),
            notes = notes.toMap()
        )
        return BpSessionLog(base.header(), streams)
    }

    /** Appends samples of one stream; [append] takes one value per column. */
    /**
     * One growing stream. [maxSamples] bounds it: a recorder left running (a real watch ran out of
     * memory after hours of motion samples) stops growing instead; [full] tells it to stop.
     */
    class StreamWriter(val name: String, val columns: List<String>, private val maxSamples: Int = Int.MAX_VALUE) {
        private var t = LongArray(256)
        private var v = FloatArray(256 * columns.size)
        private var n = 0

        val full: Boolean @Synchronized get() = n >= maxSamples

        /** @return false once [maxSamples] are stored (the sample is dropped). */
        @Synchronized
        fun append(timestampNs: Long, vararg sample: Float): Boolean {
            require(sample.size == columns.size) { "$name: ${sample.size} values for ${columns.size} columns" }
            if (n >= maxSamples) return false
            if (n == t.size) {
                t = t.copyOf(n * 2)
                v = v.copyOf(n * 2 * columns.size)
            }
            t[n] = timestampNs
            sample.copyInto(v, n * columns.size)
            n++
            return true
        }

        val size: Int @Synchronized get() = n

        @Synchronized
        fun snapshot() = SensorStream(name, columns, t.copyOf(n), v.copyOf(n * columns.size))
    }
}

/** Stream names and columns written by the watch, read back by [BpSessionReplay] and tools/bp-ml. */
object BpSessionStreams {
    /** PPG_ON_DEMAND points: green, ir, red values and their statuses (NaN / -1 when absent). */
    const val PPG = "ppg"
    val PPG_COLUMNS = arrayOf("green", "ir", "red", "greenStatus", "irStatus", "redStatus")

    /** ECG_ON_DEMAND points: ECG mV, the PPG green that comes with it, lead-off. */
    const val ECG = "ecg"
    val ECG_COLUMNS = arrayOf("ecgMv", "ppgGreen", "leadOff")

    const val SKIN_TEMP = "skinTemp"
    val SKIN_TEMP_COLUMNS = arrayOf("objectC", "ambientC", "status")

    const val EDA = "eda"
    val EDA_COLUMNS = arrayOf("microSiemens")

    /** Notes: wall-clock ns of the raised-arm window, and the user's height. */
    const val NOTE_RAISED_FROM = "maneuver.raisedFromNs"
    const val NOTE_RAISED_TO = "maneuver.raisedToNs"
    const val VALUE_HEIGHT = "profile.heightCm"
}

/**
 * Rebuilds a session's [BpSessionInput] from its raw log and runs the current pipeline on it, so
 * every recorded session can be re-estimated whenever the algorithm changes.
 */
object BpSessionReplay {
    fun input(log: BpSessionLog): BpSessionInput {
        val ppg = log.stream(BpSessionStreams.PPG)
        val rows = ppg?.let { s -> (0 until s.size).filter { s.values[it * s.columns.size].isFinite() } }.orEmpty()
        val green = FloatArray(rows.size) { ppg!!.values[rows[it] * ppg.columns.size] }
        val times = LongArray(rows.size) { ppg!!.timestampsNs[rows[it]] }
        val ir = ppg?.let { s ->
            FloatArray(rows.size) { s.values[rows[it] * s.columns.size + 1] }.takeIf { a -> a.any { it.isFinite() } }
        }
        val ecg = log.stream(BpSessionStreams.ECG)
        val precise = ecg?.takeIf { it.size > 0 }?.let { s ->
            PreciseInput(
                s.column("ecgMv")!!,
                s.column("ppgGreen")!!,
                s.rateHz().roundToIntSafe(500),
                s.timestampsNs.first(),
                log.header.notes[BpSessionStreams.NOTE_RAISED_FROM]?.toLongOrNull()?.let { from ->
                    log.header.notes[BpSessionStreams.NOTE_RAISED_TO]?.toLongOrNull()?.let { to -> from..to }
                }
            )
        }
        val temp = log.stream(BpSessionStreams.SKIN_TEMP)?.column("objectC")?.filter {
            it.isFinite()
        }?.takeIf { it.isNotEmpty() }?.average()
        val eda = log.stream(BpSessionStreams.EDA)?.column("microSiemens")?.filter { it.isFinite() }?.takeIf { it.isNotEmpty() }?.average()
        return BpSessionInput(
            green = green,
            fs = ppg?.rateHz()?.roundToIntSafe(BpCalibration.PPG_FS) ?: BpCalibration.PPG_FS,
            greenTimesNs = times,
            ir = ir,
            imu = ImuStreams(log.stream(ImuStreams.ACCEL), log.stream(ImuStreams.GYRO), log.stream(ImuStreams.ROTATION)),
            precise = precise,
            skinTempC = temp,
            edaMicroSiemens = eda,
            heightCm = log.header.values[BpSessionStreams.VALUE_HEIGHT]
        )
    }

    fun replay(log: BpSessionLog, calibration: BpCalibration?, nowMs: Long = log.header.startedAtMs): BpResult =
        BpPipeline.run(calibration, input(log), nowMs)

    /** Nominal rates (100, 500 Hz) snap to the nearest when the measured rate is within 10 %. */
    private fun Double.roundToIntSafe(nominal: Int): Int = if (this <= 0 ||
        kotlin.math.abs(this - nominal) < nominal * 0.1
    ) {
        nominal
    } else {
        kotlin.math.round(this).toInt()
    }
}
