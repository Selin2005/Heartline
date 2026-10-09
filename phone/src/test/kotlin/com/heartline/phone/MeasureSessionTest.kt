// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.heartline.phone.measure.MeasureIssue
import com.heartline.phone.measure.MeasureSessionManager
import com.heartline.phone.measure.MeasureStep
import com.heartline.shared.model.Metric
import com.heartline.shared.model.RecordSummary
import com.heartline.shared.sync.Features
import com.heartline.shared.sync.MeasureHint
import com.heartline.shared.sync.MeasureOutcome
import com.heartline.shared.sync.MeasureProblem
import com.heartline.shared.sync.MeasureResult
import com.heartline.shared.sync.MeasureStage
import com.heartline.shared.sync.MeasureState
import com.heartline.shared.sync.PeerProbe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class MeasureSessionTest {
    private val scope = TestScope(StandardTestDispatcher())
    private var probe = PeerProbe.REACHABLE
    private var features: List<String>? = listOf(Features.REMOTE_MEASURE)
    private var calibrated = true
    private var opens = true
    private val opened = mutableListOf<String>()
    private val cancelled = mutableListOf<String>()
    private var record: Pair<String, RecordSummary>? = null
    private var ids = 0

    private val manager = MeasureSessionManager(
        probe = { probe },
        features = { features },
        calibrated = { calibrated },
        open = { opened += it; opens },
        cancelOnWatch = { cancelled += it; true },
        recordSince = { _, _ -> record },
        scope = scope,
        now = { scope.testScheduler.currentTime },
        newId = { "s${++ids}" },
    )

    private fun state(stage: MeasureStage, seq: Int, progress: Float = 0f, hint: MeasureHint = MeasureHint.NONE, live: Int? = null, reason: MeasureProblem? = null) =
        MeasureState("s1", Metric.SPO2, stage, seq, progress, 10, hint, live, reason)

    @Test fun opensTheRemoteRouteAndFollowsTheWatch() {
        manager.start(Metric.SPO2)
        scope.runCurrent()
        assertEquals(listOf("remote/SPO2?session=s1"), opened)
        assertEquals(MeasureStep.WAITING_WATCH, manager.state.value!!.step)

        manager.onState(state(MeasureStage.ACCEPTED, 1))
        assertEquals(MeasureStep.PREPARING, manager.state.value!!.step)
        manager.onState(state(MeasureStage.MEASURING, 3, 0.5f, MeasureHint.HOLD_STILL))
        manager.onState(state(MeasureStage.MEASURING, 2, 0.2f))
        val ui = manager.state.value!!
        assertEquals(MeasureStep.MEASURING, ui.step)
        assertEquals("a late state is ignored", 0.5f, ui.progress, 0f)
        assertEquals(MeasureHint.HOLD_STILL, ui.hint)

        val summary = RecordSummary.Spo2(97, 66, false)
        manager.onResult(MeasureResult("r", "s1", Metric.SPO2, MeasureOutcome.OK, recordId = "rec", summary = summary))
        assertEquals(MeasureStep.RESULT, manager.state.value!!.step)
        assertEquals(summary, manager.state.value!!.summary)
        assertEquals("rec", manager.state.value!!.recordId)
    }

    @Test fun noWatchIsSaidAtOnce() {
        probe = PeerProbe.NO_DEVICE
        manager.start(Metric.HEART_RATE)
        scope.runCurrent()
        assertEquals(MeasureIssue.NO_WATCH, manager.state.value!!.issue)
        assertTrue(opened.isEmpty())
    }

    @Test fun anOlderWatchAppIsSentToTheOldWay() {
        features = emptyList()
        manager.start(Metric.STRESS)
        scope.runCurrent()
        assertEquals(MeasureIssue.WATCH_OUTDATED, manager.state.value!!.issue)
        assertEquals("quick/STRESS", manager.state.value!!.watchRoute)
    }

    @Test fun unknownFeaturesStillTry() {
        features = null
        manager.start(Metric.STRESS)
        scope.runCurrent()
        assertEquals(1, opened.size)
    }

    @Test fun bloodPressureNeedsACalibrationButARoundDoesNot() {
        calibrated = false
        manager.start(Metric.BLOOD_PRESSURE)
        scope.runCurrent()
        assertEquals(MeasureIssue.NEEDS_CALIBRATION, manager.state.value!!.issue)
        manager.start(Metric.BLOOD_PRESSURE, round = 2)
        scope.runCurrent()
        assertEquals(listOf("remote/BLOOD_PRESSURE?session=s2&round=2"), opened)
        assertEquals("bp_calibration", manager.state.value!!.watchRoute)
    }

    @Test fun aWatchThatDoesntAnswerTimesOut() {
        manager.start(Metric.SPO2)
        scope.runCurrent()
        scope.advanceTimeBy(MeasureSessionManager.ACCEPT_TIMEOUT_MS + 1)
        assertEquals(MeasureIssue.NO_RESPONSE, manager.state.value!!.issue)
    }

    @Test fun openingFailureIsNoResponse() {
        opens = false
        manager.start(Metric.SPO2)
        scope.runCurrent()
        assertEquals(MeasureIssue.NO_RESPONSE, manager.state.value!!.issue)
    }

    @Test fun rejectionCarriesTheWatchReason() {
        manager.start(Metric.SPO2)
        scope.runCurrent()
        manager.onState(state(MeasureStage.REJECTED, 1, reason = MeasureProblem.BUSY))
        assertEquals(MeasureIssue.BUSY, manager.state.value!!.issue)
    }

    @Test fun silenceWhileMeasuringIsLostUnlessTheRecordCame() {
        manager.start(Metric.SPO2)
        scope.runCurrent()
        manager.onState(state(MeasureStage.MEASURING, 1, 0.4f))
        scope.advanceTimeBy(MeasureSessionManager.SILENCE_TIMEOUT_MS + 1)
        assertEquals(MeasureIssue.LOST, manager.state.value!!.issue)

        manager.retry()
        scope.runCurrent()
        manager.onState(MeasureState("s2", Metric.SPO2, MeasureStage.MEASURING, 1, 0.9f))
        record = "rec" to RecordSummary.Spo2(96, null, false)
        scope.advanceTimeBy(MeasureSessionManager.SILENCE_TIMEOUT_MS + 1)
        assertEquals(MeasureStep.RESULT, manager.state.value!!.step)
        assertEquals("rec", manager.state.value!!.recordId)
    }

    @Test fun cancelStopsTheWatchToo() {
        manager.start(Metric.SPO2)
        scope.runCurrent()
        manager.cancel()
        scope.runCurrent()
        assertEquals(listOf("s1"), cancelled)
        assertEquals(MeasureIssue.CANCELLED, manager.state.value!!.issue)
        manager.dismiss()
        assertNull(manager.state.value)
    }

    @Test fun outcomesFromTheWatchMapToIssues() {
        manager.start(Metric.SPO2)
        scope.runCurrent()
        manager.onResult(MeasureResult("r", "s1", Metric.SPO2, MeasureOutcome.CANCELLED, MeasureProblem.WATCH_LEFT))
        assertEquals(MeasureIssue.WATCH_LEFT, manager.state.value!!.issue)
        manager.retry()
        scope.runCurrent()
        manager.onResult(MeasureResult("r2", "s2", Metric.SPO2, MeasureOutcome.FAILED, MeasureProblem.MOVING))
        assertEquals(MeasureIssue.MOVING, manager.state.value!!.issue)
    }

    @Test fun startingTheSameMeasurementAgainKeepsTheSession() {
        manager.start(Metric.SPO2)
        scope.runCurrent()
        manager.start(Metric.SPO2)
        scope.runCurrent()
        assertEquals(1, opened.size)
        manager.start(Metric.STRESS)
        scope.runCurrent()
        assertEquals(listOf("s1"), cancelled)
        assertEquals(Metric.STRESS, manager.state.value!!.metric)
    }

    @Test fun ecgAndBodyCompositionNeverMeasureFromThePhone() {
        manager.start(Metric.ECG)
        scope.runCurrent()
        assertEquals(MeasureIssue.OTHER, manager.state.value!!.issue)
        assertTrue(opened.isEmpty())
    }
}
