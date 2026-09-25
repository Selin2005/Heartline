package com.heartline.phone.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import com.heartline.shared.hr.HrBuckets
import com.heartline.shared.hr.RangeBucket

/**
 * Charts for widgets, drawn into bitmaps (Glance has no canvas). Colours are chosen to read on
 * light and dark backgrounds alike, since a bitmap can't follow the theme.
 */
object WidgetCharts {
    private const val GRID = 0x338E8E93
    private const val AXIS_TEXT = 0xFF8E8E93.toInt()

    /**
     * Min–max capsules like the app's range chart. [slots] bars across; optional dashed resting
     * line and right-hand bpm labels.
     */
    fun rangeBars(
        buckets: List<RangeBucket>,
        slots: Int,
        width: Int,
        height: Int,
        color: Int,
        density: Float,
        resting: Int? = null,
        axisLabels: Boolean = true,
        xLabels: List<String> = emptyList(),
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val axis = HrBuckets.axis(buckets)
        val axisWidth = if (axisLabels) 26 * density else 0f
        val plot = width - axisWidth
        val top = 4 * density
        val bottom = height - (if (xLabels.isEmpty()) 4 else 16) * density
        val span = (axis.last - axis.first).toFloat()
        fun y(v: Int) = bottom - (v - axis.first) / span * (bottom - top)
        val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = GRID
            strokeWidth = density
        }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = AXIS_TEXT
            textSize = 10 * density
        }
        val step = if (span > 80) 40 else 20
        var g = (axis.first + step - 1) / step * step
        while (g <= axis.last) {
            canvas.drawLine(0f, y(g), plot, y(g), grid)
            if (axisLabels) canvas.drawText("$g", plot + 5 * density, (y(g) + 4 * density).coerceIn(10 * density, height.toFloat()), text)
            g += step
        }
        resting?.takeIf { it in axis }?.let { r ->
            val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = AXIS_TEXT
                strokeWidth = 1.2f * density
                pathEffect = DashPathEffect(floatArrayOf(4 * density, 4 * density), 0f)
            }
            canvas.drawLine(0f, y(r), plot, y(r), dash)
        }
        // Evenly spaced time labels under the plot (00 06 12 18 24).
        xLabels.forEachIndexed { i, label ->
            val x = i * plot / (xLabels.size - 1).coerceAtLeast(1)
            val w = text.measureText(label)
            canvas.drawText(label, (x - w / 2).coerceIn(0f, plot - w), height - 3 * density, text)
        }
        val slot = plot / slots
        val bar = (slot * 0.62f).coerceIn(2 * density, 10 * density)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        buckets.forEach { b ->
            val x = b.index * slot + slot / 2
            val t = y(b.max)
            val bt = maxOf(y(b.min), t + bar)
            canvas.drawRoundRect(RectF(x - bar / 2, t, x + bar / 2, bt), bar / 2, bar / 2, paint)
        }
        return bitmap
    }

    /**
     * Half-ring gauge in three segments (green, yellow, red: Samsung Health stress style) with a marker at
     * [score] out of 100.
     */
    fun gauge(score: Int?, width: Int, height: Int, density: Float): Bitmap {
        val bitmap = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val stroke = 10 * density
        val radius = minOf(width / 2f, height.toFloat()) - stroke
        val cx = width / 2f
        val cy = stroke / 2 + radius + stroke / 2
        val oval = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
        }
        // Three coloured segments are clearer than a subtle gradient at widget size.
        val segments = listOf(0xFF2FBF71.toInt(), 0xFFF5B400.toInt(), 0xFFE5484D.toInt())
        val gap = 4f
        segments.forEachIndexed { i, c ->
            track.color = c
            canvas.drawArc(oval, 180f + i * 60f + gap / 2, 60f - gap, false, track)
        }
        score?.let { s ->
            val angle = Math.toRadians(180.0 + s.coerceIn(0, 100) * 1.8)
            val mx = cx + radius * kotlin.math.cos(angle).toFloat()
            val my = cy + radius * kotlin.math.sin(angle).toFloat()
            val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
            canvas.drawCircle(mx, my, stroke * 0.75f, ring)
            ring.color = 0xFF1B1B1F.toInt()
            canvas.drawCircle(mx, my, stroke * 0.38f, ring)
        }
        return bitmap
    }
}
