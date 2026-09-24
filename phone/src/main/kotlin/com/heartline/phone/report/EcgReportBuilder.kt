package com.heartline.phone.report

import android.content.Context
import com.heartline.phone.R
import com.heartline.phone.ui.components.explanation
import com.heartline.phone.ui.components.label
import com.heartline.phone.ui.model.EcgRecordUi
import com.heartline.shared.design.Palette
import com.heartline.shared.model.Severity
import java.io.File

/** Builds and exports the localised ECG report for a record. */
class EcgReportBuilder(private val context: Context, private val exporter: EcgPdfExporter = EcgPdfExporter(context)) {
    fun data(record: EcgRecordUi): EcgReportData {
        val symptoms = record.symptoms.joinToString { context.getString(it.label) }.ifEmpty { context.getString(R.string.ecg_no_symptoms) }
        return EcgReportData(
            title = context.getString(R.string.report_title),
            result = context.getString(record.result.label),
            resultColor = when (record.result.severity) {
                Severity.NORMAL -> Palette.Light.STATUS_NORMAL
                Severity.WARN -> Palette.Light.STATUS_WARN
                Severity.ALERT -> Palette.Light.STATUS_ALERT
                Severity.NEUTRAL -> Palette.Light.ON_SURFACE_VARIANT
            }.toInt(),
            recordedAt = "${record.date} · ${record.time}",
            details = listOf(
                context.getString(R.string.ecg_avg_hr) to (record.averageBpm?.let { context.getString(R.string.ecg_bpm_value, it) } ?: "–"),
                context.getString(R.string.ecg_duration) to "${record.durationSec} ${context.getString(R.string.unit_seconds)}",
                context.getString(R.string.ecg_symptoms) to symptoms,
            ),
            explanation = context.getString(record.result.explanation),
            disclaimer = context.getString(R.string.not_a_diagnosis),
            footer = context.getString(R.string.report_footer, record.sampleRateHz),
            samples = record.samples ?: FloatArray(0),
            sampleRateHz = record.sampleRateHz,
        )
    }

    fun export(record: EcgRecordUi): File = exporter.export(data(record), "heartline-ecg-${record.id.take(8)}.pdf")

    fun shareIntent(file: File) = exporter.shareIntent(file, context.getString(R.string.report_title))
}
