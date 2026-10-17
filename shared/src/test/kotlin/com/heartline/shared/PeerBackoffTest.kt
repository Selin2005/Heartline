// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared

import com.heartline.shared.sync.PeerBackoff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerBackoffTest {
    @Test
    fun waitsLongerAfterEachMissUpToTheLimitAndStartsOverWhenFound() {
        var now = 0L
        val b = PeerBackoff(clockMs = { now }, firstMs = 30_000, maxMs = 300_000)
        assertTrue(b.shouldTry())
        val waits = (1..6).map {
            b.onLookup(found = false)
            b.waitLeftMs().also { now += it }
        }
        // 30 s, 60 s, 2 min, 4 min, then 5 min at most: about 15 lookups an hour at worst instead of one per heart-rate delivery.
        assertEquals(listOf(30_000L, 60_000L, 120_000L, 240_000L, 300_000L, 300_000L), waits)
        b.onLookup(found = false)
        now += 1_000
        assertFalse(b.shouldTry())
        b.onLookup(found = true)
        assertTrue(b.shouldTry())
        b.onLookup(found = false)
        assertEquals(30_000L, b.waitLeftMs())
        // Heard from the phone: ask again at once.
        b.reset()
        assertTrue(b.shouldTry())
    }
}
