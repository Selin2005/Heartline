// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear

import com.heartline.shared.hr.HrSample
import com.heartline.shared.measure.RemoteMeasureLink
import com.heartline.shared.model.Metric
import com.heartline.shared.model.RecordKind
import com.heartline.shared.model.RecordSummary
import com.heartline.shared.sync.MeasureHint
import com.heartline.shared.sync.MeasureOutcome
import com.heartline.shared.sync.MeasureProblem
import com.heartline.shared.sync.MeasureResult
import com.heartline.shared.sync.MeasureStage
import com.heartline.shared.sync.MeasureState
import com.heartline.wear.remote.RemoteMeasureCoordinator
import com.heartline.wear.remote.RemoteMeasureReporter
import com.heartline.wear.sensor.QuickHint
import com.heartline.wear.sensor.SensorProblem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RemoteMeasureTest {
    private var clock = 0L
    private val states = mutableListOf<MeasureState>()
    private val results = mutableListOf<MeasureResult>()
    private val scope = TestScope(UnconfinedTestDispatcher())
    private val coordinator = RemoteMeasureCoordinator({ states += it; true }, { results += it }, scope) { clock }
    private val link = RemoteMeasureLink(Metric.SPO2, "s1")

    @Test fun progressIsThrottledButChangesGoAtOnce() {
        val r = coordinator.reporter(link)
        r.report(MeasureStage.ACCEPTED)
        r.report(MeasureStage.MEASURING, 0.1f)
        clock = 300
        r.report(MeasureStage.MEASURING, 0.2f)
        r.report(MeasureStage.MEASURING, 0.2f, hint = MeasureHint.HOLD_STILL)
        clock = 1_400
        r.report(MeasureStage.MEASURING, 0.5f, hint = MeasureHint.HOLD_STILL)
        assertEquals(listOf(MeasureStage.ACCEPTED, MeasureStage.MEASURING, MeasureStage.MEASURING, MeasureStage.MEASURING), states.map { it.stage })
        assertEquals(listOf(0.1f, 0.2f, 0.5f), states.drop(1).map { it.progress })
        assertEquals(states.map { it.seq }.sorted(), states.map { it.seq })
    }

    @Test fun aHintStaysAtLeastASecondAndAHalf() {
        val r = coordinator.reporter(link)
        r.report(MeasureStage.MEASURING, 0.3f)
        clock = 100
        r.report(MeasureStage.MEASURING, 0.3f, hint = MeasureHint.HOLD_STILL)
        clock = 400
        r.report(MeasureStage.MEASURING, 0.3f)
        clock = 1_200
        r.report(MeasureStage.MEASURING, 0.31f)
        assertTrue("still held", states.last().hint == MeasureHint.HOLD_STILL)
        clock = 1_700
        r.report(MeasureStage.MEASURING, 0.35f)
        assertEquals(MeasureHint.NONE, states.last().hint)
    }

    @Test fun outcomeIsSentOnceAndNothingAfterIt() {
        val r = coordinator.reporter(link)
        r.finish(MeasureOutcome.OK, recordId = "rec", summary = RecordSummary.Spo2(97, 66, false))
        r.finish(MeasureOutcome.CANCELLED, MeasureProblem.WATCH_LEFT)
        r.report(MeasureStage.MEASURING, 0.9f)
        assertEquals(1, results.size)
        assertEquals(MeasureOutcome.OK, results.single().outcome)
        assertEquals("rec", results.single().recordId)
        assertTrue(states.isEmpty())
    }

    @Test fun rejectTellsWhyAndEnds() {
        coordinator.reject(link, MeasureProblem.BUSY)
        assertEquals(MeasureStage.REJECTED, states.single().stage)
        assertEquals(MeasureProblem.BUSY, states.single().reason)
        assertEquals(MeasureOutcome.FAILED, results.single().outcome)
    }

    /** The phone repeats its request when it hears nothing: the session already measuring is known. */
    @Test fun aSessionIsOpenUntilItEnds() {
        assertFalse(coordinator.isOpen(link.sessionId))
        val r = coordinator.reporter(link)
        assertTrue(coordinator.isOpen(link.sessionId))
        r.finish(MeasureOutcome.OK)
        assertFalse(coordinator.isOpen(link.sessionId))
    }

    @Test fun busyCountsRecordingScreens() {
        assertFalse(coordinator.busy)
        coordinator.recording(true)
        coordinator.recording(true)
        coordinator.recording(false)
        assertTrue(coordinator.busy)
        coordinator.recording(false)
        coordinator.recording(false)
        assertFalse(coordinator.busy)
    }

    @Test fun calibrationRoundTravelsWithTheState() {
        val r = coordinator.reporter(RemoteMeasureLink(Metric.BLOOD_PRESSURE, "c", round = 2))
        r.report(MeasureStage.PREPARING)
        assertEquals(2, states.single().round)
    }

    @Test fun sensorWordsMapToTheProtocol() {
        assertEquals(MeasureHint.HOLD_STILL, RemoteMeasureReporter.hint(QuickHint.HOLD_STILL))
        assertEquals(MeasureHint.WRIST_CONTACT, RemoteMeasureReporter.hint(QuickHint.WRIST_CONTACT))
        assertEquals(MeasureHint.NONE, RemoteMeasureReporter.hint(null))
        assertEquals(MeasureProblem.SDK_POLICY, RemoteMeasureReporter.problem(SensorProblem.SDK_POLICY))
        assertEquals(MeasureProblem.WRIST_CONTACT, RemoteMeasureReporter.problem(SensorProblem.OFF_BODY))
        assertEquals(MeasureProblem.MOVING, RemoteMeasureReporter.problem(null, QuickHint.HOLD_STILL))
    }

    @Test fun heartRateRecordsAreTheirOwnKind() {
        assertEquals(Metric.HEART_RATE, RecordKind.HEART_RATE.metric)
        assertTrue(HrSample(0, 70, emptyList()).reliable)
    }
}
