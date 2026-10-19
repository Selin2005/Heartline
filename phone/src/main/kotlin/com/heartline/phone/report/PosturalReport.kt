// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.report

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.res.ResourcesCompat
import com.heartline.phone.R
import com.heartline.phone.ui.components.label
import com.heartline.phone.ui.model.PosturalReportUi
import com.heartline.phone.ui.model.PosturalSummaries
import com.heartline.shared.hr.RangeBucket
import com.heartline.shared.pots.PosturalStatus
import com.heartline.shared.profile.UserProfile
import java.io.File

/** Everything printed on the report of rises after getting up, already localised. */
data class PosturalReportData(
    val title: String,
    val subtitle: String,
    val status: String,
    val summary: List<Pair<String, String>>,
    val chartTitle: String,
    val chart: List<RangeBucket>,
    val chartLabels: List<String>,
    val headers: List<String>,
    val rows: List<List<String>>,
    val guidance: String,
    val disclaimer: String,
    val footer: String,
)

/** Draws the one-page A4 report (portrait) on any Canvas: a PDF page, or a bitmap for screenshots. */
class PosturalReportPainter(private val regular: Typeface = Typeface.DEFAULT, private val bold: Typeface = Typeface.DEFAULT_BOLD) {
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shape = Paint(Paint.ANTI_ALIAS_FLAG)

    fun draw(canvas: Canvas, data: PosturalReportData) {
        canvas.drawColor(Color.WHITE)
        val left = MARGIN
        val width = PAGE_WIDTH - 2 * MARGIN
        var y = MARGIN + 16f
        write(canvas, data.title, left, y, 18f, bold)
        y += 16f
        write(canvas, data.subtitle, left, y, 9f, regular, GREY)
        y += 22f
        write(canvas, data.status, left, y, 11f, bold, WARN)
        y += 18f

        // Summary: label/value pairs in two columns.
        data.summary.forEachIndexed { i, (label, value) ->
            val x = left + (i % 2) * width / 2
            val rowY = y + (i / 2) * 14f
            write(canvas, label, x, rowY, 8.5f, regular, GREY)
            write(canvas, value, x + width * 0.24f, rowY, 8.5f, bold)
        }
        y += ((data.summary.size + 1) / 2) * 14f + 14f

        write(canvas, data.chartTitle, left, y, 10f, bold)
        y += 8f
        y = chart(canvas, data, left, y, width, 130f) + 22f

        // Rises: a table with a header row.
        val columns = floatArrayOf(0f, 0.15f, 0.25f, 0.36f, 0.46f, 0.54f, 0.81f)
        data.headers.forEachIndexed { i, h -> write(canvas, h, left + columns[i] * width, y, 7.5f, bold, GREY) }
        y += 4f
        shape.color = LINE
        canvas.drawLine(left, y, left + width, y, shape)
        y += 11f
        val bottomLimit = PAGE_HEIGHT - MARGIN - 70f
        for (row in data.rows) {
            if (y > bottomLimit) break
            var height = 1
            row.forEachIndexed { i, cell ->
                val cellWidth = (columns.getOrElse(i + 1) { 1f } - columns[i]) * width - 6f
                // Up to two lines a cell (how the wearer felt can be long), then cut.
                text.textSize = 8f
                text.typeface = regular
                val parts = lines(cell, cellWidth).let { if (it.size > 2) listOf(it[0], ellipsize(it.drop(1).joinToString(" "), cellWidth, 8f)) else it }
                parts.forEachIndexed { k, part -> write(canvas, ellipsize(part, cellWidth, 8f), left + columns[i] * width, y + k * 10f, 8f, regular) }
                height = maxOf(height, parts.size)
            }
            y += 12f + (height - 1) * 10f
        }
        y += 10f
        text.textSize = 9f
        text.typeface = regular
        text.color = INK
        y = wrapped(canvas, data.guidance, left, y, width) + 6f

        val bottom = PAGE_HEIGHT - MARGIN
        text.textSize = 7.5f
        text.color = GREY
        wrappedUp(canvas, data.disclaimer, left, bottom - 14f, width)
        write(canvas, data.footer, left, bottom, 7f, regular, GREY)
    }

    /** Daily bars from the resting to the held rate, on a round-number axis; returns the y below. */
    private fun chart(canvas: Canvas, data: PosturalReportData, left: Float, top: Float, width: Float, height: Float): Float {
        val lo = ((data.chart.minOfOrNull { it.min } ?: 60) - 10) / 10 * 10
        val hi = ((data.chart.maxOfOrNull { it.max } ?: 120) + 19) / 10 * 10
        val axisWidth = 22f
        val plotWidth = width - axisWidth
        val bottom = top + height
        fun y(v: Int) = bottom - (v - lo).toFloat() / (hi - lo) * height
        shape.strokeWidth = 0.5f
        for (v in lo..hi step 20) {
            shape.color = LINE
            canvas.drawLine(left, y(v), left + plotWidth, y(v), shape)
            write(canvas, "$v", left + plotWidth + 4f, y(v) + 3f, 7f, regular, GREY)
        }
        val slot = plotWidth / PosturalSummaries.CHART_DAYS
        shape.color = WARN
        data.chart.forEach { b ->
            val x = left + b.index * slot + slot * 0.2f
            canvas.drawRoundRect(RectF(x, y(b.max), x + slot * 0.6f, y(b.min)), 2f, 2f, shape)
        }
        data.chartLabels.forEachIndexed { i, l -> write(canvas, l, left + i * 7 * slot, bottom + 11f, 7f, regular, GREY) }
        return bottom + 11f
    }

    private fun write(canvas: Canvas, value: String, x: Float, y: Float, size: Float, face: Typeface, color: Int = INK) {
        text.textSize = size
        text.typeface = face
        text.color = color
        canvas.drawText(value, x, y, text)
    }

    private fun ellipsize(value: String, max: Float, size: Float): String {
        text.textSize = size
        if (text.measureText(value) <= max) return value
        var cut = value
        while (cut.isNotEmpty() && text.measureText("$cut…") > max) cut = cut.dropLast(1)
        return "$cut…"
    }

    private fun lines(value: String, width: Float): List<String> {
        val out = mutableListOf<String>()
        var line = ""
        for (word in value.split(' ')) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (text.measureText(candidate) > width && line.isNotEmpty()) {
                out += line
                line = word
            } else {
                line = candidate
            }
        }
        if (line.isNotEmpty()) out += line
        return out
    }

    /** Draws [value] from [y] down; returns the y below it. */
    private fun wrapped(canvas: Canvas, value: String, x: Float, y: Float, width: Float): Float {
        val all = lines(value, width)
        all.forEachIndexed { i, l -> canvas.drawText(l, x, y + i * (text.textSize + 3f), text) }
        return y + all.size * (text.textSize + 3f)
    }

    /** Draws [value] so its last line sits at [y]. */
    private fun wrappedUp(canvas: Canvas, value: String, x: Float, y: Float, width: Float) {
        val all = lines(value, width)
        all.forEachIndexed { i, l -> canvas.drawText(l, x, y - (all.size - 1 - i) * (text.textSize + 2f), text) }
    }

    companion object {
        /** A4 portrait in PDF points. */
        const val PAGE_WIDTH = 595
        const val PAGE_HEIGHT = 842
        private const val MARGIN = 40f
        private val INK = Color.rgb(17, 17, 20)
        private val GREY = Color.rgb(110, 110, 118)
        private val LINE = Color.rgb(222, 222, 228)
        private val WARN = Color.rgb(214, 128, 0)
    }
}

/** Builds and exports the report of rises after getting up. */
class PosturalReportBuilder(
    private val context: Context,
    private val version: String,
    private val zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    private val locale: java.util.Locale = java.util.Locale.getDefault(),
) {
    fun data(state: PosturalReportUi, profile: UserProfile? = null, name: String? = null): PosturalReportData {
        val res = context.resources
        val person = listOfNotNull(name?.takeIf { it.isNotBlank() }, profile?.age()?.let { res.getQuantityString(R.plurals.profile_age, it, it) })
            .joinToString(", ")
        val day = java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM).withLocale(locale)
        val period = state.chartStart?.let { context.getString(R.string.postural_report_period, it.format(day), it.plusDays(PosturalSummaries.CHART_DAYS - 1L).format(day)) }.orEmpty()
        val bpm = { v: Int? -> v?.let { context.getString(R.string.ecg_bpm_value, it) } ?: "–" }
        return PosturalReportData(
            title = context.getString(R.string.postural_report_title_pdf),
            subtitle = if (person.isEmpty()) period else context.getString(R.string.postural_report_patient, person, period),
            status = context.getString(if (state.status == PosturalStatus.EASING) R.string.postural_status_easing else R.string.postural_status_pattern),
            summary = listOf(
                context.getString(R.string.postural_days_label) to context.getString(R.string.postural_days_value, state.days, PosturalSummaries.PATTERN_DAYS),
                context.getString(R.string.postural_rises_label) to "${state.riseCount}",
                context.getString(R.string.postural_rest_label) to bpm(state.medianRestBpm),
                context.getString(R.string.postural_held_label) to bpm(state.medianPeakBpm),
                context.getString(R.string.postural_report_rise) to (state.medianRiseBpm?.let { "+" + context.getString(R.string.ecg_bpm_value, it) } ?: "–"),
                context.getString(R.string.postural_before_label) to context.getString(R.string.postural_days_value, state.previousDays, PosturalSummaries.PATTERN_DAYS),
            ),
            chartTitle = context.getString(R.string.postural_chart_title),
            chart = state.chart,
            chartLabels = state.chartLabels,
            headers = listOf(
                context.getString(R.string.postural_report_date),
                context.getString(R.string.postural_report_time),
                context.getString(R.string.postural_rest_label),
                context.getString(R.string.postural_held_label),
                context.getString(R.string.postural_report_rise),
                context.getString(R.string.postural_report_table_symptoms),
                context.getString(R.string.postural_report_table_source),
            ),
            rows = state.rises.map { r ->
                // Dates as printed, not "Today": the report is read later.
                val at = java.time.Instant.ofEpochMilli(r.startMs).atZone(zone)
                listOf(
                    at.format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM).withLocale(locale)),
                    at.format(java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT).withLocale(locale)),
                    "${r.restBpm}",
                    "${r.peakBpm}",
                    "+${r.riseBpm}",
                    r.symptoms.joinToString { context.getString(it.label) }.ifEmpty { "–" },
                    context.getString(if (r.measured) R.string.postural_report_source_probe else R.string.postural_report_source_passive),
                )
            },
            guidance = context.getString(R.string.postural_report_guidance),
            disclaimer = context.getString(R.string.postural_not_diagnosis) + " " + context.getString(R.string.not_a_diagnosis),
            footer = context.getString(R.string.postural_report_footer, version),
        )
    }

    fun export(state: PosturalReportUi, profile: UserProfile?, name: String?, fileName: String): File {
        val painter = PosturalReportPainter(
            ResourcesCompat.getFont(context, R.font.inter_regular) ?: Typeface.DEFAULT,
            ResourcesCompat.getFont(context, R.font.inter_semibold) ?: Typeface.DEFAULT_BOLD,
        )
        val document = PdfDocument()
        try {
            val page = document.startPage(PdfDocument.PageInfo.Builder(PosturalReportPainter.PAGE_WIDTH, PosturalReportPainter.PAGE_HEIGHT, 1).create())
            painter.draw(page.canvas, data(state, profile, name))
            document.finishPage(page)
            val file = File(File(context.cacheDir, "reports").apply { mkdirs() }, fileName)
            file.outputStream().use(document::writeTo)
            return file
        } finally {
            document.close()
        }
    }
}
