package com.heartline.shared.report

/**
 * Geometry of the printed ECG report: A4 landscape, three 10-second strips at 25 mm/s and
 * 10 mm/mV on 1 mm / 5 mm grid paper. Units are PDF points (1/72 in).
 */
object EcgStripLayout {
    const val PT_PER_MM = 72.0 / 25.4
    const val PAGE_WIDTH_PT = 842
    const val PAGE_HEIGHT_PT = 595
    const val MM_PER_SECOND = 25.0
    const val MM_PER_MV = 10.0
    const val SECONDS_PER_STRIP = 10
    const val STRIP_HEIGHT_MM = 30.0
    const val MARGIN_MM = 12.0
    const val HEADER_MM = 34.0
    const val STRIP_GAP_MM = 6.0

    data class Strip(
        val index: Int,
        val left: Double,
        val top: Double,
        val width: Double,
        val height: Double,
        val fromSample: Int,
        val toSample: Int
    )

    fun mm(value: Double) = value * PT_PER_MM

    fun strips(sampleCount: Int, fs: Int, count: Int = 3): List<Strip> {
        val perStrip = SECONDS_PER_STRIP * fs
        val width = mm(SECONDS_PER_STRIP * MM_PER_SECOND)
        val left = (PAGE_WIDTH_PT - width) / 2
        return (0 until count).mapNotNull { i ->
            val from = i * perStrip
            if (from >= sampleCount) return@mapNotNull null
            val top = mm(MARGIN_MM + HEADER_MM + i * (STRIP_HEIGHT_MM + STRIP_GAP_MM))
            Strip(i, left, top, width, mm(STRIP_HEIGHT_MM), from, minOf(sampleCount, from + perStrip))
        }
    }

    /** Points per sample horizontally and per millivolt vertically. */
    fun xScale(fs: Int) = mm(MM_PER_SECOND) / fs

    val yScale = mm(MM_PER_MV)
}
