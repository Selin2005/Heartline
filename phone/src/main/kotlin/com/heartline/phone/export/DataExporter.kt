// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.heartline.phone.data.BackgroundAll
import com.heartline.phone.data.RecordEntity
import com.heartline.shared.model.RecordSummary
import com.heartline.shared.sync.Protocol
import java.io.File
import java.time.Instant

/**
 * Exports every record as one CSV, and the watch's background readings (source=background);
 * waveforms stay in the PDF reports.
 */
object CsvFormat {
    const val HEADER = "timestamp_utc,type,value,unit,details"

    fun backgroundRows(all: BackgroundAll): List<Pair<Long, String>> =
        all.spo2.map { it.tsMs to line(it.tsMs, "SPO2", "${it.percent}", "%", "source=background;context=${it.context};recheck=${it.confirmation}") } +
            all.temps.map { it.tsMs to line(it.tsMs, "SKIN_TEMPERATURE", "${it.skinC}", "C", "source=background;ambient=${it.ambientC ?: ""};context=${it.context}") } +
            all.stress.map { it.tsMs to line(it.tsMs, "STRESS", it.score?.toString().orEmpty(), "score", "source=background;rmssd_ms=${"%.1f".format(java.util.Locale.US, it.rmssdMs)};bpm=${it.bpm};context=${it.context}") }

    private fun line(atMs: Long, type: String, value: String, unit: String, details: String) =
        listOf(Instant.ofEpochMilli(atMs).toString(), type, value, unit, details).joinToString(",") { escape(it) }

    fun row(entity: RecordEntity): String {
        val summary = Protocol.json.decodeFromString<RecordSummary>(entity.summaryJson)
        val (value, unit, details) = when (summary) {
            is RecordSummary.Ecg -> Triple(summary.averageBpm?.toString().orEmpty(), "bpm", "result=${summary.result};symptoms=${summary.symptoms.joinToString("|")}")
            is RecordSummary.BloodPressure -> Triple("${summary.systolic}/${summary.diastolic}", "mmHg", "pulse=${summary.pulse ?: ""}")
            is RecordSummary.Spo2 -> Triple("${summary.percent}", "%", "heart_rate=${summary.heartRate ?: ""}")
            is RecordSummary.SkinTemperature -> Triple("${summary.skinCelsius}", "C", "ambient=${summary.ambientCelsius ?: ""}")
            is RecordSummary.BodyComposition -> Triple(
                "${summary.bodyFatPercent}",
                "%",
                listOf(
                    "weight_kg" to summary.weightKg,
                    "height_cm" to summary.heightCm,
                    "fat_mass_kg" to summary.bodyFatMassKg,
                    "muscle_kg" to summary.skeletalMuscleKg,
                    "muscle_pct" to summary.skeletalMusclePercent,
                    "water_kg" to summary.bodyWaterKg,
                    "fat_free_kg" to summary.fatFreeMassKg,
                    "fat_free_pct" to summary.fatFreePercent,
                    "bmr" to summary.bmrKcal,
                    "impedance_ohm" to summary.impedanceOhm,
                    "phase_angle_deg" to summary.phaseAngleDeg,
                ).joinToString(";") { (k, v) -> "$k=${v ?: ""}" },
            )
            is RecordSummary.Stress -> Triple("${summary.score}", "score", "rmssd_ms=${summary.rmssdMs ?: ""}")
            is RecordSummary.HeartRate -> Triple("${summary.bpm}", "bpm", "min=${summary.minBpm};max=${summary.maxBpm};samples=${summary.samples}")
        }
        return listOf(Instant.ofEpochMilli(entity.startedAtMs).toString(), entity.kind.name, value, unit, details).joinToString(",") { escape(it) }
    }

    private fun escape(value: String) = if (value.any { it == ',' || it == '"' || it == '\n' }) "\"${value.replace("\"", "\"\"")}\"" else value
}

class DataExporter(private val context: Context) {
    fun export(records: List<RecordEntity>, fileName: String = "heartline-export.csv", background: BackgroundAll = BackgroundAll()): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, fileName)
        file.bufferedWriter().use { w ->
            w.appendLine(CsvFormat.HEADER)
            (records.map { it.startedAtMs to CsvFormat.row(it) } + CsvFormat.backgroundRows(background))
                .sortedBy { it.first }
                .forEach { w.appendLine(it.second) }
        }
        return file
    }

    fun shareIntent(file: File): Intent = Intent(Intent.ACTION_SEND)
        .setType("text/csv")
        .putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(context, "${context.packageName}.reports", file))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}
