// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear

import com.heartline.shared.model.Metric
import com.heartline.shared.model.RecordKind
import com.heartline.shared.model.RecordSummary
import com.heartline.shared.profile.UserProfile
import com.heartline.wear.monitor.VitalsMeasurer
import com.heartline.wear.sensor.QuickEvent
import com.heartline.wear.sensor.QuickHint
import com.heartline.wear.sensor.QuickSource
import com.heartline.wear.sensor.sdk.Spo2Status
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VitalsMeasurerTest {
    /** Moves once after 2 s, then gives a result at 25 s (as the sensor would, had it not stopped). */
    private val moving = object : QuickSource {
        override val metric = Metric.SPO2
        override val kind = RecordKind.SPO2
        override val seconds = 30
        var stillRunningAtEnd = false

        override fun measure(profile: UserProfile?): Flow<QuickEvent> = flow {
            emit(QuickEvent.Progress(0.1f))
            delay(2_000)
            emit(QuickEvent.Progress(0.2f, QuickHint.HOLD_STILL))
            delay(23_000)
            stillRunningAtEnd = true
            emit(QuickEvent.Result(RecordSummary.Spo2(97, 70, false)))
        }
    }

    @Test
    fun aBackgroundTryStopsAtTheFirstMovement() = runTest {
        val reading = VitalsMeasurer.measure(moving, stopOnMove = true)
        assertEquals(VitalsMeasurer.STOPPED_MOVED, reading.failure)
        assertTrue(reading.moved)
        assertNull(reading.summary)
        // The light went off at once: the source never reached its 25 s.
        assertTrue(!moving.stillRunningAtEnd)
    }

    @Test
    fun anOnScreenMeasurementRunsToItsEnd() = runTest {
        val reading = VitalsMeasurer.measure(moving)
        assertEquals(97, (reading.summary as RecordSummary.Spo2).percent)
        assertTrue(reading.moved)
    }

    @Test
    fun theSensorsMovedStatusIsReportedAtOnce() {
        // The statuses of the 10/09 11:24 try (raw session 20261009-112422): moved from the second point.
        val statuses = listOf(0, -4, -4, -4, -4, 0, -4, -4, -4, -4, 0, -4, -4, -4, -4, 0, -4, -4, -4, -4, -5, -5)
        assertEquals(1, statuses.indexOfFirst { Spo2Status.hint(it) == QuickHint.HOLD_STILL })
        assertNull(Spo2Status.hint(0))
        assertNull(Spo2Status.hint(2))
    }
}
