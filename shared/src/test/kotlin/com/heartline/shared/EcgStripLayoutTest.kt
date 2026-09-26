// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared

import com.heartline.shared.report.EcgStripLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EcgStripLayoutTest {
    @Test
    fun threeTenSecondStripsFitOnA4Landscape() {
        val strips = EcgStripLayout.strips(15_000, 500)
        assertEquals(3, strips.size)
        assertEquals(708.66, strips[0].width, 0.01) // 250 mm
        strips.forEach {
            assertTrue(it.left > 0 && it.left + it.width < EcgStripLayout.PAGE_WIDTH_PT)
            assertTrue(it.top + it.height < EcgStripLayout.PAGE_HEIGHT_PT - EcgStripLayout.mm(12.0))
        }
        assertEquals(10_000 until 15_000, strips[2].fromSample until strips[2].toSample)
        // 10 s of samples spans exactly the strip width.
        assertEquals(strips[0].width, 5_000 * EcgStripLayout.xScale(500), 1e-6)
    }

    @Test
    fun shortRecordingsProduceFewerStrips() {
        assertEquals(2, EcgStripLayout.strips(7_000, 500).size)
    }
}
