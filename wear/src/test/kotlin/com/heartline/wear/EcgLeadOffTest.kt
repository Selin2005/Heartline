// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear

import com.heartline.wear.sensor.batchContact
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LEAD_OFF: only 5 means "no contact" (Samsung's sample and the rule that worked on a Galaxy Watch8).
 * Requiring 0 kept the ECG from ever starting there; the recorder's checks decide the rest.
 */
class EcgLeadOffTest {
    private fun batch(first: Int?, rest: Int? = null) = listOf(first) + List(9) { rest }

    @Test
    fun fiveAnywhereInTheBatchIsNoContact() {
        assertFalse(batchContact(batch(5)))
        assertFalse(batchContact(batch(0, rest = 5)))
        assertFalse(batchContact(listOf(null, null, 5)))
    }

    @Test
    fun zeroNullAndOtherValuesDoNotBlockContact() {
        assertTrue(batchContact(batch(0)))
        assertTrue(batchContact(batch(null)))
        assertTrue(batchContact(batch(1, rest = 1)))
    }
}
