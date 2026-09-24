package com.heartline.phone.report

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import com.heartline.phone.R
import com.heartline.shared.design.Palette
import com.heartline.shared.report.EcgStripLayout
import com.heartline.shared.report.EcgStripLayout.mm
import java.io.File

/** Everything printed on the ECG report, already localised. */
data class EcgReportData(
    val title: String,
    val result: String,
    val resultColor: Int,
    val recordedAt: String,
    val details: List<Pair<String, String>>,
    val explanation: String,
    val disclaimer: String,
    val footer: String,
    val samples: FloatArray,
    val sampleRateHz: Int,
    /** Recording details (usable/noise/HR range…) printed below the strips. */
    val recordingDetails: List<Pair<String, String>> = emptyList(),
    val noisySeconds: List<Int> = emptyList(),
)

/** Draws the report page on any Canvas (PDF page, or a bitmap for screenshots). */
class EcgReportPainter(private val regular: Typeface = Typeface.DEFAULT, private val bold: Typeface = Typeface.DEFAULT_BOLD) {
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(17, 17, 20) }
    private val grid = Paint().apply { style = Paint.Style.STROKE }
    private val trace = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.rgb(17, 17, 20)
        strokeWidth = 0.7f
        strokeJoin = Paint.Join.ROUND
    }

    fun draw(canvas: Canvas, data: EcgReportData) {
        canvas.drawColor(Color.WHITE)
        val left = mm(EcgStripLayout.MARGIN_MM).toFloat()
        var y = mm(EcgStripLayout.MARGIN_MM).toFloat() + 14f

        text.typeface = bold
        text.textSize = 16f
        canvas.drawText(data.title, left, y, text)
        text.typeface = regular
        text.textSize = 9f
        text.color = Color.rgb(110, 110, 118)
        canvas.drawText(data.recordedAt, left, y + 14f, text)

        // Result with a coloured dot.
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = data.resultColor }
        y += 36f
        canvas.drawCircle(left + 4f, y - 4f, 4f, dot)
        text.typeface = bold
        text.textSize = 13f
        text.color = Color.rgb(17, 17, 20)
        canvas.drawText(data.result, left + 14f, y, text)

        // Detail columns to the right of the title block.
        var x = mm(110.0).toFloat()
        data.details.forEach { (label, value) ->
            text.typeface = regular
            text.textSize = 8f
            text.color = Color.rgb(110, 110, 118)
            canvas.drawText(label, x, mm(EcgStripLayout.MARGIN_MM).toFloat() + 12f, text)
            text.typeface = bold
            text.textSize = 11f
            text.color = Color.rgb(17, 17, 20)
            canvas.drawText(value, x, mm(EcgStripLayout.MARGIN_MM).toFloat() + 27f, text)
            x += mm(42.0).toFloat()
        }

        val strips = EcgStripLayout.strips(data.samples.size, data.sampleRateHz)
        strips.forEach { strip -> drawStrip(canvas, strip, data) }

        val bottom = EcgStripLayout.PAGE_HEIGHT_PT - mm(EcgStripLayout.MARGIN_MM).toFloat()
        text.typeface = regular
        text.textSize = 9f
        text.color = Color.rgb(17, 17, 20)
        var belowStrips = (strips.lastOrNull()?.let { it.top + it.height } ?: mm(60.0)).toFloat() + mm(7.0).toFloat()
        if (data.recordingDetails.isNotEmpty()) belowStrips = drawDetails(canvas, data.recordingDetails, left, belowStrips) + mm(4.0).toFloat()
        text.typeface = regular
        text.textSize = 9f
        text.color = Color.rgb(17, 17, 20)
        drawWrapped(canvas, data.explanation, left, belowStrips, EcgStripLayout.PAGE_WIDTH_PT - 2 * left)
        text.textSize = 8f
        text.color = Color.rgb(110, 110, 118)
        drawWrapped(canvas, data.disclaimer, left, bottom - 14f, EcgStripLayout.PAGE_WIDTH_PT - 2 * left)
        text.textSize = 7f
        canvas.drawText(data.footer, left, bottom, text)
    }

    /** Recording details in three label/value columns; returns the y below them. */
    private fun drawDetails(canvas: Canvas, rows: List<Pair<String, String>>, left: Float, top: Float): Float {
        val columns = 3
        val colWidth = (EcgStripLayout.PAGE_WIDTH_PT - 2 * left) / columns
        val lineHeight = 11f
        val perColumn = (rows.size + columns - 1) / columns
        rows.forEachIndexed { i, (label, value) ->
            val x = left + (i / perColumn) * colWidth
            val y = top + (i % perColumn) * lineHeight
            text.typeface = regular
            text.textSize = 7.5f
            text.color = Color.rgb(110, 110, 118)
            canvas.drawText(label, x, y, text)
            text.typeface = bold
            text.color = Color.rgb(17, 17, 20)
            canvas.drawText(value, x + colWidth * 0.36f, y, text)
        }
        return top + perColumn * lineHeight
    }

    private fun drawStrip(canvas: Canvas, strip: EcgStripLayout.Strip, data: EcgReportData) {
        val mm1 = mm(1.0).toFloat()
        val l = strip.left.toFloat()
        val t = strip.top.toFloat()
        val w = strip.width.toFloat()
        val h = strip.height.toFloat()
        // Seconds treated as noise by the analysis are shaded.
        val shade = Paint().apply { color = Color.argb(34, 255, 149, 0) }
        val xsShade = EcgStripLayout.xScale(data.sampleRateHz).toFloat()
        data.noisySeconds.forEach { s ->
            val from = s * data.sampleRateHz
            val to = from + data.sampleRateHz
            if (to <= strip.fromSample || from >= strip.toSample) return@forEach
            val x0 = l + (maxOf(from, strip.fromSample) - strip.fromSample) * xsShade
            val x1 = l + (minOf(to, strip.toSample) - strip.fromSample) * xsShade
            canvas.drawRect(x0, t, x1, t + h, shade)
        }
        val cols = (w / mm1).toInt()
        val rows = (h / mm1).toInt()
        for (i in 0..cols) {
            grid.color = (if (i % 5 == 0) Palette.Light.ECG_GRID_MAJOR else Palette.Light.ECG_GRID_MINOR).toInt()
            grid.strokeWidth = if (i % 5 == 0) 0.5f else 0.25f
            canvas.drawLine(l + i * mm1, t, l + i * mm1, t + rows * mm1, grid)
        }
        for (j in 0..rows) {
            grid.color = (if (j % 5 == 0) Palette.Light.ECG_GRID_MAJOR else Palette.Light.ECG_GRID_MINOR).toInt()
            grid.strokeWidth = if (j % 5 == 0) 0.5f else 0.25f
            canvas.drawLine(l, t + j * mm1, l + cols * mm1, t + j * mm1, grid)
        }
        // Clip so an unusually large deflection can't run into the neighbouring strip.
        canvas.save()
        canvas.clipRect(l, t, l + w, t + h)
        val baseline = t + h * 0.62f
        val xs = EcgStripLayout.xScale(data.sampleRateHz).toFloat()
        val ys = EcgStripLayout.yScale.toFloat()
        val path = Path()
        for (i in strip.fromSample until strip.toSample) {
            val px = l + (i - strip.fromSample) * xs
            val py = baseline - data.samples[i] * ys
            if (i == strip.fromSample) path.moveTo(px, py) else path.lineTo(px, py)
        }
        canvas.drawPath(path, trace)
        canvas.restore()
        text.typeface = regular
        text.textSize = 7f
        text.color = Color.rgb(110, 110, 118)
        canvas.drawText("${strip.index * EcgStripLayout.SECONDS_PER_STRIP} s", l, t - 2f, text)
    }

    private fun drawWrapped(canvas: Canvas, value: String, x: Float, y: Float, width: Float) {
        val words = value.split(' ')
        val lines = mutableListOf<String>()
        var line = ""
        for (word in words) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (text.measureText(candidate) > width && line.isNotEmpty()) {
                lines += line
                line = word
            } else {
                line = candidate
            }
        }
        if (line.isNotEmpty()) lines += line
        lines.forEachIndexed { i, l -> canvas.drawText(l, x, y + i * (text.textSize + 2f) - (lines.size - 1) * (text.textSize + 2f), text) }
    }
}

/** Writes the report to a PDF in the cache and builds a share intent via FileProvider. */
class EcgPdfExporter(private val context: Context) {
    fun export(data: EcgReportData, fileName: String): File {
        val painter = EcgReportPainter(
            ResourcesCompat.getFont(context, R.font.inter_regular) ?: Typeface.DEFAULT,
            ResourcesCompat.getFont(context, R.font.inter_semibold) ?: Typeface.DEFAULT_BOLD,
        )
        val document = PdfDocument()
        try {
            val page = document.startPage(PdfDocument.PageInfo.Builder(EcgStripLayout.PAGE_WIDTH_PT, EcgStripLayout.PAGE_HEIGHT_PT, 1).create())
            painter.draw(page.canvas, data)
            document.finishPage(page)
            val dir = File(context.cacheDir, "reports").apply { mkdirs() }
            val file = File(dir, fileName)
            file.outputStream().use(document::writeTo)
            return file
        } finally {
            document.close()
        }
    }

    fun shareIntent(file: File, subject: String): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.reports", file)
        return Intent(Intent.ACTION_SEND)
            .setType("application/pdf")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
