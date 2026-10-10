// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear

import com.heartline.wear.link.WatchCommandBus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WatchCommandBusTest {
    /** The phone's request arrived while the activity was being recreated, before the UI listened. */
    @Test fun aRouteSentBeforeAnyoneListensWaitsForTheUi() {
        val bus = WatchCommandBus()
        bus.post("remote/HEART_RATE?session=a")
        assertEquals("remote/HEART_RATE?session=a", bus.pending.value)
    }

    @Test fun aRouteIsOpenedOnce() {
        val bus = WatchCommandBus()
        bus.post("ecg")
        bus.take("ecg")
        assertNull(bus.pending.value)
    }

    /** Taking an older route leaves a newer one waiting. */
    @Test fun aNewerRouteStays() {
        val bus = WatchCommandBus()
        bus.post("ecg")
        bus.post("heart_rate")
        bus.take("ecg")
        assertEquals("heart_rate", bus.pending.value)
    }
}
