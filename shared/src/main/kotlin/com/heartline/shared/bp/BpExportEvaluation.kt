// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.bp

import com.heartline.shared.diag.SessionCsv
import com.heartline.shared.sync.Protocol
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.serialization.decodeFromString

/**
 * Replays the blood-pressure sessions of an unpacked diagnostic export (Settings → Help &
 * diagnostics → Export logs) through the current algorithm and compares each with the cuff reading
 * taken with it, in the terms validation studies use (ISO 81060-2, IEEE 1708). Every algorithm
 * change can be measured on real recordings this way; nothing of the user's data is kept.
 *
 * Run with `./gradlew :shared:bpEval -Pdir=<unpacked export>`.
 */
object BpExportEvaluation {
    /** One cuff-checked measurement session with the calibration the watch had then. */
    class Case(val log: BpSessionLog, val cuffSystolic: Int, val cuffDiastolic: Int, val calibration: BpCalibration)

    /** How a case's calibration is built: only cuff readings from before it, or every other one (leave-one-out). */
    enum class Mode { ONLINE, LEAVE_ONE_OUT }

    data class Row(
        val id: String,
        val startedAtMs: Long,
        val cuffSystolic: Int,
        val cuffDiastolic: Int,
        val loggedSystolic: Int?,
        val loggedDiastolic: Int?,
        val systolic: Int?,
        val diastolic: Int?,
        val sdSys: Int?,
        val sdDia: Int?,
        val beyond: Boolean,
        val pitchDeg: Double?,
        val posture: Boolean,
        val state: String,
        val outcome: String
    ) {
        val errSys: Int? get() = systolic?.minus(cuffSystolic)
        val errDia: Int? get() = diastolic?.minus(cuffDiastolic)
    }

    data class Stats(
        val n: Int,
        val mean: Double,
        val sd: Double,
        val mae: Double,
        val within5: Double,
        val within10: Double,
        val within15: Double
    ) {
        override fun toString() =
            "n=$n mean=${f(mean)} sd=${f(sd)} mae=${f(mae)} ≤5:${pct(within5)} ≤10:${pct(within10)} ≤15:${pct(within15)}"
    }

    data class Report(val rows: List<Row>, val systolic: Stats, val diastolic: Stats, val diaCovered: Int)

    /** The measurement sessions of an export that have a cuff reading, each with its calibration of the time. */
    fun load(dir: File, mode: Mode = Mode.ONLINE, withoutCuff: Boolean = false): List<Case> {
        val calibrations = File(dir, "bp/calibrations.json").takeIf { it.exists() }
            ?.let { Protocol.json.decodeFromString<List<BpCalibration>>(it.readText()) }.orEmpty()
            .sortedBy { it.createdAtMs }
        val checks = File(dir, "bp/cuff-checks.csv").takeIf { it.exists() }?.readLines().orEmpty().drop(1)
            .mapNotNull { line ->
                line.split(',').takeIf { it.size >= 7 && it[2].isNotEmpty() }?.let {
                    it[2] to
                        (it[5].toInt() to it[6].toInt())
                }
            }
            .toMap()
        val sessions = File(dir, "sessions").listFiles { f ->
            f.isDirectory && File(f, "header.json").exists()
        }.orEmpty().sortedBy { it.name }
        return sessions.mapNotNull { folder ->
            val log = runCatching { SessionCsv.read(folder) }.getOrNull() ?: return@mapNotNull null
            if (log.header.kind != "measure") return@mapNotNull null
            val cuff = log.header.cuffSystolic?.let { s -> log.header.cuffDiastolic?.let { s to it } } ?: checks[log.header.id]
                ?: if (withoutCuff) 0 to 0 else return@mapNotNull null
            val start = log.header.startedAtMs
            val calibration = calibrations.lastOrNull { it.createdAtMs <= start } ?: return@mapNotNull null

            // The cuff reading of this very measurement is never part of its own calibration.
            fun isOwn(p: CalibrationPoint) = p.sessionId == log.header.id || abs((p.atMs ?: 0L) - start) < OWN_POINT_WINDOW_MS
            val extra = calibration.extraPoints.filter { p ->
                !isOwn(p) && (mode == Mode.LEAVE_ONE_OUT || (p.atMs ?: Long.MAX_VALUE) < start)
            }
            Case(log, cuff.first, cuff.second, calibration.copy(extraPoints = extra))
        }
    }

    fun evaluate(cases: List<Case>, tuning: BpTuning = BpTuning.DEFAULT): Report {
        val rows = cases.map { c ->
            val result = BpPipeline.run(c.calibration, BpSessionReplay.input(c.log), c.log.header.startedAtMs, tuning = tuning)
            val e = (result.outcome as? BpOutcome.Ok)?.estimate
            val values = c.log.header.values
            Row(
                c.log.header.id.take(8),
                c.log.header.startedAtMs,
                c.cuffSystolic,
                c.cuffDiastolic,
                values["fusion.systolic"]?.roundToInt(),
                values["fusion.diastolic"]?.roundToInt(),
                e?.systolic,
                e?.diastolic,
                e?.uncertaintySys,
                e?.uncertaintyDia,
                e?.beyondCalibration ?: false,
                values["arm.pitchDeg"],
                e?.postureDiffers ?: false,
                e?.state?.state?.name ?: "-",
                result.outcome::class.simpleName.orEmpty()
            )
        }
        val ok = rows.filter { it.systolic != null }
        val covered = ok.count { r -> r.sdDia != null && abs(r.errDia!!) <= 2 * r.sdDia }
        return Report(rows, stats(ok.map { it.errSys!!.toDouble() }), stats(ok.map { it.errDia!!.toDouble() }), covered)
    }

    fun stats(errors: List<Double>): Stats {
        if (errors.isEmpty()) return Stats(0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        val mean = errors.average()
        val sd = if (errors.size < 2) 0.0 else sqrt(errors.sumOf { (it - mean) * (it - mean) } / (errors.size - 1))
        fun share(limit: Double) = errors.count { abs(it) <= limit }.toDouble() / errors.size
        return Stats(errors.size, mean, sd, errors.map { abs(it) }.average(), share(5.0), share(10.0), share(15.0))
    }

    fun format(name: String, report: Report): String = buildString {
        appendLine("== $name")
        appendLine("systolic:  ${report.systolic}")
        appendLine("diastolic: ${report.diastolic}  (± covers 2·sd: ${report.diaCovered}/${report.rows.count { it.systolic != null }})")
        for (r in report.rows) {
            appendLine(
                "  ${r.id} cuff ${r.cuffSystolic}/${r.cuffDiastolic}  logged ${r.loggedSystolic}/${r.loggedDiastolic}  " +
                    "now ${r.systolic}/${r.diastolic} ±${r.sdSys}/${r.sdDia}  err ${r.errSys}/${r.errDia}  " +
                    "pitch ${r.pitchDeg?.let {
                        f(it)
                    }}${if (r.posture) " POSTURE" else ""} ${r.state}${if (r.beyond) " beyond" else ""} ${r.outcome}"
            )
        }
    }

    private const val OWN_POINT_WINDOW_MS = 120_000L

    private fun f(v: Double) = "%.1f".format(v)

    private fun pct(v: Double) = "${(v * 100).roundToInt()}%"
}
