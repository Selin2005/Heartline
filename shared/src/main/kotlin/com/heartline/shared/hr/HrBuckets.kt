package com.heartline.shared.hr

import kotlin.math.roundToInt

/** Minimum–maximum heart rate within one interval (a bar of the range chart). */
data class RangeBucket(val index: Int, val min: Int, val max: Int, val avg: Int)

/**
 * Groups per-minute heart rate into fixed intervals, keeping each interval's lowest and highest
 * value (Samsung Health style range bars) and the time-weighted average.
 */
object HrBuckets {
    /** [minutes]: (slot, min, max, avg) where slot is e.g. the minute of the day or the day index. */
    fun of(values: List<Slot>, bucketSize: Int): List<RangeBucket> =
        values.groupBy { it.position / bucketSize }.toSortedMap().map { (index, group) ->
            RangeBucket(index, group.minOf { it.min }, group.maxOf { it.max }, group.map { it.avg }.average().roundToInt())
        }

    data class Slot(val position: Int, val min: Int, val max: Int, val avg: Int)

    /** Round axis bounds: 10-bpm steps with a little headroom, at least 40 bpm tall. */
    fun axis(buckets: List<RangeBucket>): IntRange {
        if (buckets.isEmpty()) return 40..120
        var lo = (buckets.minOf { it.min } - 5) / 10 * 10
        var hi = ((buckets.maxOf { it.max } + 5 + 9) / 10) * 10
        if (hi - lo < 40) {
            val mid = (hi + lo) / 2
            lo = mid - 20
            hi = mid + 20
        }
        return lo.coerceAtLeast(20)..hi
    }
}
