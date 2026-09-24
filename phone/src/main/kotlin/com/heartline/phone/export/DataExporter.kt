package com.heartline.phone.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.heartline.phone.data.RecordEntity
import com.heartline.shared.model.RecordSummary
import com.heartline.shared.sync.Protocol
import java.io.File
import java.time.Instant

/** Exports every record as one CSV (MASTER_PLAN F19); waveforms stay in the PDF reports. */
object CsvFormat {
    const val HEADER = "timestamp_utc,type,value,unit,details"

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
                "muscle_kg=${summary.skeletalMuscleKg ?: ""};water_kg=${summary.bodyWaterKg ?: ""};bmr=${summary.bmrKcal ?: ""}",
            )
            is RecordSummary.Stress -> Triple("${summary.score}", "score", "rmssd_ms=${summary.rmssdMs ?: ""}")
        }
        return listOf(Instant.ofEpochMilli(entity.startedAtMs).toString(), entity.kind.name, value, unit, details).joinToString(",") { escape(it) }
    }

    private fun escape(value: String) = if (value.any { it == ',' || it == '"' || it == '\n' }) "\"${value.replace("\"", "\"\"")}\"" else value
}

class DataExporter(private val context: Context) {
    fun export(records: List<RecordEntity>, fileName: String = "heartline-export.csv"): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, fileName)
        file.bufferedWriter().use { w ->
            w.appendLine(CsvFormat.HEADER)
            records.sortedBy { it.startedAtMs }.forEach { w.appendLine(CsvFormat.row(it)) }
        }
        return file
    }

    fun shareIntent(file: File): Intent = Intent(Intent.ACTION_SEND)
        .setType("text/csv")
        .putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(context, "${context.packageName}.reports", file))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}
