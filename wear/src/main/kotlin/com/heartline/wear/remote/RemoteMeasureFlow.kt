// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.remote

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.heartline.shared.measure.RemoteMeasureLink
import com.heartline.shared.model.Metric
import com.heartline.shared.sync.MeasureHint
import com.heartline.shared.sync.MeasureOutcome
import com.heartline.shared.sync.MeasureProblem
import com.heartline.shared.sync.MeasureStage
import com.heartline.wear.bp.BpFlow
import com.heartline.wear.monitor.WatchSettingsStore
import com.heartline.wear.quick.QuickFlow
import com.heartline.wear.quick.REMOTE_RESULT_MS
import com.heartline.wear.ui.components.Buzz
import com.heartline.wear.ui.components.rememberBuzz
import com.heartline.wear.ui.screens.HeartRateCheckResultScreen
import com.heartline.wear.ui.screens.HeartRateCheckScreen
import kotlinx.coroutines.delay
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/**
 * A measurement the phone started ([link]): it begins at once, shows the same steps as the phone
 * and closes by itself after the result. If the watch is already measuring, the phone is told.
 */
@Composable
fun RemoteMeasureFlow(link: RemoteMeasureLink, onExit: () -> Unit) {
    val coordinator: RemoteMeasureCoordinator = koinInject()
    val reporter = remember(link) { coordinator.reporter(link) }
    val busy = remember(link) { coordinator.busy }
    if (busy) {
        LaunchedEffect(link) {
            reporter.reject(MeasureProblem.BUSY)
            onExit()
        }
        return
    }
    when (link.metric) {
        Metric.HEART_RATE -> HeartRateCheckFlow(reporter, onExit)
        Metric.BLOOD_PRESSURE -> BpFlow(onExit = onExit, calibrationSession = link.round != null, remote = reporter)
        else -> QuickFlow(link.metric, onExit, remote = reporter)
    }
}

@Composable
private fun HeartRateCheckFlow(remote: RemoteMeasureReporter, onExit: () -> Unit, vm: HeartRateCheckViewModel = koinViewModel(key = remote.link.sessionId)) {
    val state by vm.state.collectAsStateWithLifecycle()
    val coordinator: RemoteMeasureCoordinator = koinInject()
    val prefs by koinInject<WatchSettingsStore>().settings.collectAsStateWithLifecycle()
    val vibrate = rememberBuzz(prefs.haptics)
    val recording = state is HeartRateCheckState.Measuring
    DisposableEffect(recording) {
        coordinator.recording(recording)
        onDispose { if (recording) coordinator.recording(false) }
    }
    val view = LocalView.current
    DisposableEffect(recording) {
        view.keepScreenOn = recording
        onDispose { view.keepScreenOn = false }
    }
    LaunchedEffect(Unit) {
        remote.report(MeasureStage.ACCEPTED)
        vm.start()
        vibrate(Buzz.STARTED)
    }
    LaunchedEffect(remote) {
        coordinator.cancels.collect { id ->
            if (id == remote.link.sessionId) {
                vm.cancel()
                remote.finish(MeasureOutcome.CANCELLED)
                onExit()
            }
        }
    }
    // Started from the phone, the check goes on while the watch screen is off (the wrist lowered
    // to look at the phone); leaving the screen (back) still cancels it, below.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { RemoteMeasureCoordinator.logHidden(remote.link) }
    DisposableEffect(Unit) {
        onDispose {
            vm.cancel()
            remote.finish(MeasureOutcome.CANCELLED, MeasureProblem.WATCH_LEFT)
        }
    }
    // From the view model, not the screen's state: the phone hears every step even while the
    // watch screen is off and the screen doesn't update.
    LaunchedEffect(remote) {
        vm.state.collect { s -> when (s) {
            HeartRateCheckState.Idle -> Unit
            is HeartRateCheckState.Measuring -> remote.report(
                MeasureStage.MEASURING,
                s.progress,
                s.secondsLeft,
                when {
                    s.offWrist -> MeasureHint.WRIST_CONTACT
                    s.weak -> MeasureHint.HOLD_STILL
                    else -> MeasureHint.NONE
                },
                live = s.bpm,
            )
            is HeartRateCheckState.Done -> {
                remote.finish(MeasureOutcome.OK, recordId = s.recordId, summary = s.summary)
                vibrate(Buzz.DONE)
                delay(REMOTE_RESULT_MS)
                onExit()
            }
            HeartRateCheckState.TooFewReadings -> {
                remote.finish(MeasureOutcome.FAILED, MeasureProblem.LOW_SIGNAL)
                vibrate(Buzz.ATTENTION)
                delay(REMOTE_RESULT_MS / 2)
                onExit()
            }
        } }
    }
    when (val s = state) {
        is HeartRateCheckState.Done -> HeartRateCheckResultScreen(s.summary.bpm, s.summary.minBpm, s.summary.maxBpm, onDone = onExit)
        is HeartRateCheckState.Measuring -> HeartRateCheckScreen(s.progress, s.secondsLeft, s.bpm, s.offWrist, weak = s.weak)
        else -> HeartRateCheckScreen(0f, 30, null, hint = state is HeartRateCheckState.TooFewReadings)
    }
}
