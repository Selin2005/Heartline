// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared

import com.heartline.shared.hr.HrBuckets
import com.heartline.shared.hr.RangeBucket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HrBucketsTest {
    @Test
    fun tenMinuteBucketsKeepTheExtremes() {
        val minutes = (0 until 30).map { m -> HrBuckets.Slot(600 + m, 60 + m % 5, 70 + m % 7, 65) }
        val buckets = HrBuckets.of(minutes, 10)
        assertEquals(listOf(60, 61, 62), buckets.map { it.index })
        assertEquals(60, buckets[0].min)
        assertEquals(76, buckets[0].max)
        assertEquals(65, buckets[1].avg)
    }

    @Test
    fun gapsStayGaps() {
        val buckets = HrBuckets.of(listOf(HrBuckets.Slot(0, 50, 60, 55), HrBuckets.Slot(300, 70, 90, 80)), 10)
        assertEquals(listOf(0, 30), buckets.map { it.index })
    }

    @Test
    fun axisIsRoundAndTallEnough() {
        val axis = HrBuckets.axis(listOf(RangeBucket(0, 58, 64, 60)))
        assertTrue(axis.first % 10 == 0 && axis.last % 10 == 0)
        assertTrue(axis.last - axis.first >= 40)
        val wide = HrBuckets.axis(listOf(RangeBucket(0, 48, 152, 80)))
        assertTrue(wide.first <= 43 && wide.last >= 157)
    }
}
