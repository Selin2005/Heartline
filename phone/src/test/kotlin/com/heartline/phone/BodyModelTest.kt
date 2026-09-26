// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone

import com.heartline.phone.ui.model.BodySpan
import com.heartline.phone.ui.model.BodyTrend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BodyModelTest {
    private val body = SampleData.body

    @Test
    fun trendPointsAreOldestFirstWithinTheSpan() {
        val month = body.points(BodyTrend.WEIGHT, BodySpan.MONTH)
        assertEquals(4, month.size) // 0, 9, 18 and 30 days back
        assertEquals(76.4f, month.last().second, 0.01f)
        assertEquals(8, body.points(BodyTrend.WEIGHT, BodySpan.ALL).size)
    }

    @Test
    fun changeOverTheSpan() {
        assertEquals(-5.28f, body.change(BodyTrend.WEIGHT, BodySpan.QUARTER)!!, 0.01f)
        assertEquals(0.70f, body.change(BodyTrend.MUSCLE, BodySpan.QUARTER)!!, 0.01f)
        assertNull(body.copy(entries = body.entries.take(1)).change(BodyTrend.WEIGHT, BodySpan.ALL))
    }
}
