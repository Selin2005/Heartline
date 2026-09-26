// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.heartline.shared.hr.HrBuckets
import com.heartline.shared.hr.RangeBucket
import kotlin.math.cos
import kotlin.math.sin

/**
 * One layer of a widget chart: a white shape (only its alpha matters) that the widget tints with
 * a theme colour. A baked colour bitmap can't follow the phone's light/dark switch; a tinted mask
 * does, because the colour is resolved by the launcher like any other widget colour.
 */
data class ChartLayer(val mask: Bitmap, val tone: ChartTone)

/** Which theme colour a [ChartLayer] is tinted with. */
enum class ChartTone { METRIC, SUBTLE, TEXT, TRACK, NORMAL, WARN, ALERT, BACKGROUND }

/** Charts for widgets (Glance has no canvas), as layers of tintable masks. */
object WidgetCharts {
    private fun mask(width: Int, height: Int) = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)

    private fun paint(alpha: Float = 1f) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb((alpha * 255).toInt(), 255, 255, 255) }

    /**
     * Min–max capsules like the app's range chart: bars in the metric colour, and grid, resting
     * line and labels in a subtle tone. [slots] bars across.
     */
    fun rangeBars(
        buckets: List<RangeBucket>,
        slots: Int,
        width: Int,
        height: Int,
        density: Float,
        resting: Int? = null,
        axisLabels: Boolean = true,
        xLabels: List<String> = emptyList(),
    ): List<ChartLayer> {
        val guide = mask(width, height)
        val bars = mask(width, height)
        val g = Canvas(guide)
        // Small charts without labels use the data's own range so the bars fill the height.
        val axis = if (axisLabels || xLabels.isNotEmpty() || buckets.isEmpty()) {
            HrBuckets.axis(buckets)
        } else {
            (buckets.minOf { it.min } - 2)..(buckets.maxOf { it.max } + 2)
        }
        val axisWidth = if (axisLabels) 26 * density else 0f
        val plot = width - axisWidth
        val top = 4 * density
        val bottom = height - (if (xLabels.isEmpty()) 4 else 16) * density
        val span = (axis.last - axis.first).toFloat()
        fun y(v: Int) = bottom - (v - axis.first) / span * (bottom - top)
        val grid = paint(0.35f).apply { strokeWidth = density }
        val text = paint().apply { textSize = 10 * density }
        val step = if (span > 80) 40 else 20
        var v = (axis.first + step - 1) / step * step
        while (axisLabels && v <= axis.last) {
            g.drawLine(0f, y(v), plot, y(v), grid)
            if (axisLabels) g.drawText("$v", plot + 5 * density, (y(v) + 4 * density).coerceIn(10 * density, height.toFloat()), text)
            v += step
        }
        resting?.takeIf { it in axis }?.let { r ->
            val dash = paint(0.8f).apply {
                strokeWidth = 1.2f * density
                pathEffect = DashPathEffect(floatArrayOf(4 * density, 4 * density), 0f)
            }
            g.drawLine(0f, y(r), plot, y(r), dash)
        }
        xLabels.forEachIndexed { i, label ->
            val x = i * plot / (xLabels.size - 1).coerceAtLeast(1)
            val w = text.measureText(label)
            g.drawText(label, (x - w / 2).coerceIn(0f, plot - w), height - 3 * density, text)
        }
        val b = Canvas(bars)
        val slot = plot / slots
        val bar = (slot * (if (axisLabels) 0.62f else 0.5f)).coerceIn(2 * density, 10 * density)
        val fill = paint()
        buckets.forEach { bucket ->
            val x = bucket.index * slot + slot / 2
            val t = y(bucket.max)
            val bt = maxOf(y(bucket.min), t + bar)
            b.drawRoundRect(RectF(x - bar / 2, t, x + bar / 2, bt), bar / 2, bar / 2, fill)
        }
        return listOf(ChartLayer(guide, ChartTone.SUBTLE), ChartLayer(bars, ChartTone.METRIC))
    }

    /**
     * Smooth line through [values] (oldest first) with a soft fill under it and a dot on the latest
     * value, like the small trend lines on Samsung Health cards.
     */
    fun sparkline(values: List<Float>, width: Int, height: Int, density: Float): List<ChartLayer> {
        val area = mask(width, height)
        val line = mask(width, height)
        if (values.isEmpty()) return listOf(ChartLayer(line, ChartTone.METRIC))
        val pad = 5 * density
        val lo = values.min()
        val hi = values.max()
        val span = (hi - lo).takeIf { it > 1e-3f } ?: 1f
        fun x(i: Int) = if (values.size == 1) width / 2f else pad + i * (width - 2 * pad) / (values.size - 1)
        fun y(v: Float) = if (hi - lo < 1e-3f) height / 2f else height - pad - (v - lo) / span * (height - 2 * pad)
        val path = Path()
        values.forEachIndexed { i, v ->
            if (i == 0) {
                path.moveTo(x(i), y(v))
            } else {
                // Midpoint curve: smooth without overshooting the real values.
                val px = x(i - 1)
                val py = y(values[i - 1])
                val mx = (px + x(i)) / 2
                path.cubicTo(mx, py, mx, y(v), x(i), y(v))
            }
        }
        val under = Path(path).apply {
            lineTo(x(values.lastIndex), height.toFloat())
            lineTo(x(0), height.toFloat())
            close()
        }
        Canvas(area).drawPath(under, paint(0.18f))
        val c = Canvas(line)
        c.drawPath(path, paint().apply { style = Paint.Style.STROKE; strokeWidth = 2.2f * density; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND })
        c.drawCircle(x(values.lastIndex), y(values.last()), 3.6f * density, paint())
        return listOf(ChartLayer(area, ChartTone.METRIC), ChartLayer(line, ChartTone.METRIC))
    }

    /** Full ring: a track and [progress] (0–1) from the top, clockwise, with round ends. */
    fun ring(progress: Float, size: Int, density: Float, stroke: Float = 7f): List<ChartLayer> {
        val track = mask(size, size)
        val arc = mask(size, size)
        val w = stroke * density
        val oval = RectF(w / 2, w / 2, size - w / 2, size - w / 2)
        val p = paint().apply { style = Paint.Style.STROKE; strokeWidth = w; strokeCap = Paint.Cap.ROUND }
        Canvas(track).drawArc(oval, 0f, 360f, false, paint(1f).apply { style = Paint.Style.STROKE; strokeWidth = w })
        Canvas(arc).drawArc(oval, -90f, 360f * progress.coerceIn(0.02f, 1f), false, p)
        return listOf(ChartLayer(track, ChartTone.TRACK), ChartLayer(arc, ChartTone.METRIC))
    }

    /**
     * Half-ring gauge in three segments (green, yellow, red: Samsung Health stress style) with a
     * marker at [score] out of 100.
     */
    fun gauge(score: Int?, width: Int, height: Int, density: Float): List<ChartLayer> {
        val stroke = 10 * density
        val radius = minOf(width / 2f, height.toFloat()) - stroke
        val cx = width / 2f
        val cy = stroke + radius
        val oval = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        val tones = listOf(ChartTone.NORMAL, ChartTone.WARN, ChartTone.ALERT)
        val layers = tones.mapIndexed { i, tone ->
            val m = mask(width, height)
            val p = paint().apply { style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND }
            Canvas(m).drawArc(oval, 180f + i * 60f + 2f, 56f, false, p)
            ChartLayer(m, tone)
        }.toMutableList()
        score?.let { s ->
            val angle = Math.toRadians(180.0 + s.coerceIn(0, 100) * 1.8)
            val mx = cx + radius * cos(angle).toFloat()
            val my = cy + radius * sin(angle).toFloat()
            val knob = mask(width, height).also { Canvas(it).drawCircle(mx, my, stroke * 0.78f, paint()) }
            val hole = mask(width, height).also { Canvas(it).drawCircle(mx, my, stroke * 0.38f, paint()) }
            layers += ChartLayer(knob, ChartTone.BACKGROUND)
            layers += ChartLayer(hole, ChartTone.TEXT)
        }
        return layers
    }

    /**
     * A horizontal bar in [bands] coloured segments with a round marker at [position] (0–1), like
     * the range bars on Samsung Health (e.g. blood-pressure category).
     */
    fun bandBar(bands: List<Pair<Float, ChartTone>>, position: Float?, width: Int, height: Int, density: Float): List<ChartLayer> {
        val bar = 6 * density
        val cy = height / 2f
        val layers = mutableListOf<ChartLayer>()
        val gap = 2 * density
        var start = 0f
        bands.forEach { (share, tone) ->
            val m = mask(width, height)
            val x0 = start * width
            val x1 = (start + share) * width
            Canvas(m).drawRoundRect(RectF(x0 + gap / 2, cy - bar / 2, x1 - gap / 2, cy + bar / 2), bar / 2, bar / 2, paint())
            layers += ChartLayer(m, tone)
            start += share
        }
        position?.let { p ->
            val x = (p.coerceIn(0f, 1f) * width).coerceIn(bar, width - bar)
            layers += ChartLayer(mask(width, height).also { Canvas(it).drawCircle(x, cy, bar * 1.25f, paint()) }, ChartTone.BACKGROUND)
            layers += ChartLayer(mask(width, height).also { Canvas(it).drawCircle(x, cy, bar * 0.8f, paint()) }, ChartTone.TEXT)
        }
        return layers
    }
}
