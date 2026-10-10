// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.quick

import com.heartline.wear.ui.components.Buzz
import com.heartline.wear.ui.components.rememberBuzz
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.heartline.shared.body.BodyComposition
import com.heartline.shared.model.Metric
import com.heartline.shared.model.RecordSummary
import com.heartline.shared.sensor.PermissionPolicy
import com.heartline.wear.sensor.SensorProblem
import com.heartline.wear.ui.screens.BodyResultScreen
import com.heartline.wear.ui.screens.ProfileNeededScreen
import com.heartline.wear.ui.screens.QuickInstructionScreen
import com.heartline.wear.ui.screens.QuickMeasuringScreen
import com.heartline.wear.ui.screens.QuickResultScreen
import com.heartline.wear.ui.screens.SensorErrorScreen
import com.heartline.wear.ui.screens.WeightConfirmScreen
import org.koin.androidx.compose.koinViewModel
import com.heartline.wear.monitor.WatchSettingsStore
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import com.heartline.shared.sync.MeasureOutcome
import com.heartline.shared.sync.MeasureProblem
import com.heartline.shared.sync.MeasureStage
import com.heartline.wear.remote.RemoteMeasureCoordinator
import com.heartline.wear.remote.RemoteMeasureReporter

/**
 * One SpO2 / skin temperature / body composition / stress measurement. With [remote] the phone
 * started it: it begins at once and every step goes to the phone, which shows the same.
 */
@Composable
fun QuickFlow(
    metric: Metric,
    onExit: () -> Unit,
    remote: RemoteMeasureReporter? = null,
    vm: QuickMeasureViewModel = koinViewModel(key = remote?.link?.sessionId ?: metric.name) { parametersOf(metric) },
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val permissions = PermissionPolicy.permissionsFor(metric, Build.VERSION.SDK_INT).toTypedArray()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { if (it.values.all { ok -> ok }) vm.start() }
    fun start() {
        val missing = permissions.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) vm.start() else launcher.launch(missing.toTypedArray())
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        // Started from the phone, it goes on while the watch screen is off (the wrist lowered to
        // look at the phone); leaving the screen (back) still cancels it, below.
        if (remote != null) {
            com.heartline.wear.remote.RemoteMeasureCoordinator.logHidden(remote.link)
        } else if (state is QuickState.Measuring) {
            vm.cancel()
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            vm.cancel()
            remote?.finish(MeasureOutcome.CANCELLED, MeasureProblem.WATCH_LEFT)
        }
    }
    val coordinator: RemoteMeasureCoordinator = koinInject()
    val recording = state is QuickState.Measuring
    DisposableEffect(recording) {
        coordinator.recording(recording)
        onDispose { if (recording) coordinator.recording(false) }
    }
    if (remote != null) {
        LaunchedEffect(Unit) {
            remote.report(MeasureStage.ACCEPTED)
            start()
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
        // From the view model, not the screen's state: the phone hears every step even while the
        // watch screen is off and the screen doesn't update.
        LaunchedEffect(remote) {
            vm.state.collect { s -> when (s) {
                QuickState.Idle, is QuickState.ConfirmWeight -> Unit
                QuickState.NeedsProfile -> remote.reject(MeasureProblem.NEEDS_PROFILE)
                is QuickState.Measuring -> remote.report(
                    MeasureStage.MEASURING,
                    s.progress,
                    s.secondsLeft,
                    RemoteMeasureReporter.hint(s.hint),
                    live = s.bpm,
                )
                is QuickState.Done -> {
                    remote.finish(MeasureOutcome.OK, recordId = s.recordId, summary = s.summary)
                    // The phone has the result too: leave the watch screen by itself after a while.
                    kotlinx.coroutines.delay(REMOTE_RESULT_MS)
                    vm.reset()
                    onExit()
                }
                is QuickState.Failed -> remote.finish(MeasureOutcome.FAILED, RemoteMeasureReporter.problem(s.problem, s.hint))
            } }
        }
    }
    val view = LocalView.current
    DisposableEffect(state is QuickState.Measuring) {
        view.keepScreenOn = state is QuickState.Measuring
        onDispose { view.keepScreenOn = false }
    }
    val prefs by koinInject<WatchSettingsStore>().settings.collectAsStateWithLifecycle()
    val vibrate = rememberBuzz(prefs.haptics)
    LaunchedEffect(state is QuickState.Measuring) { if (state is QuickState.Measuring) vibrate(Buzz.STARTED) }
    LaunchedEffect(state is QuickState.Done) { if (state is QuickState.Done) vibrate(Buzz.DONE) }
    val done = {
        vm.reset()
        onExit()
    }
    when (val s = state) {
        QuickState.Idle -> if (remote != null) QuickMeasuringScreen(metric, 0f, vm.seconds, null, fromPhone = true) else QuickInstructionScreen(metric, onStart = ::start)
        QuickState.NeedsProfile -> ProfileNeededScreen(onDone = done)
        is QuickState.Measuring -> QuickMeasuringScreen(metric, s.progress, s.secondsLeft, s.hint, s.bpm, s.hrvMs, fromPhone = remote != null)
        is QuickState.ConfirmWeight -> WeightConfirmScreen(s.weightKg, onConfirm = vm::confirmWeight)
        is QuickState.Done -> {
            val body = s.summary as? RecordSummary.BodyComposition
            if (body != null) {
                val sex = s.profile?.calcSex
                val age = s.profile?.age()
                val report = BodyComposition.report(body, sex, age, s.profile?.weightKg, s.profile?.heightCm)
                val previous = (s.previous as? RecordSummary.BodyComposition)?.let { BodyComposition.report(it, sex, age, s.profile?.weightKg, s.profile?.heightCm) }
                BodyResultScreen(report, previous, onDone = done)
            } else {
                QuickResultScreen(metric, s.summary, onDone = done)
            }
        }
        is QuickState.Failed -> SensorErrorScreen(s.problem ?: SensorProblem.OFF_BODY, onAction = { vm.reset() })
    }
}

/** How long a result the phone asked for stays on the watch before the screen closes by itself. */
internal const val REMOTE_RESULT_MS = 8_000L
