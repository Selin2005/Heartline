package com.heartline.wear.bp

import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.LocalActivity
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
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.heartline.shared.model.Metric
import com.heartline.shared.sensor.PermissionPolicy
import com.heartline.wear.R
import com.heartline.wear.sensor.SensorGateway
import com.heartline.wear.sensor.SensorProblem
import com.heartline.wear.ui.screens.BpCalibrationRecordedScreen
import com.heartline.wear.ui.screens.BpInstructionScreen
import com.heartline.wear.ui.screens.BpNeedsCalibrationScreen
import com.heartline.wear.ui.screens.BpResultScreen
import com.heartline.wear.ui.screens.MeasuringScreen
import com.heartline.wear.ui.screens.SensorErrorScreen
import com.heartline.wear.ui.theme.WearColors
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun BpFlow(onExit: () -> Unit, vm: BpMeasureViewModel = koinViewModel(), gateway: SensorGateway = koinInject()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val capture by vm.pendingCapture.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = LocalActivity.current
    val haptics = LocalHapticFeedback.current
    val permissions = PermissionPolicy.permissionsFor(Metric.BLOOD_PRESSURE, Build.VERSION.SDK_INT).toTypedArray()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { if (it.values.all { ok -> ok }) vm.start() }
    fun start() {
        val missing = permissions.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) vm.start() else launcher.launch(missing.toTypedArray())
    }

    LaunchedEffect(Unit) { vm.checkReady() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { if (state is BpState.Measuring) vm.cancel() }
    DisposableEffect(Unit) { onDispose { vm.cancel() } }
    val measuring = state as? BpState.Measuring
    val view = LocalView.current
    DisposableEffect(measuring != null) {
        view.keepScreenOn = measuring != null
        onDispose { view.keepScreenOn = false }
    }
    LaunchedEffect(state is BpState.Done || state is BpState.CalibrationRecorded) {
        if (state is BpState.Done || state is BpState.CalibrationRecorded) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
    }
    val done = {
        vm.reset()
        onExit()
    }

    when (val s = state) {
        BpState.Idle -> BpInstructionScreen(capture?.round, onStart = ::start)
        BpState.NeedsCalibration -> BpNeedsCalibrationScreen(onDone = done)
        is BpState.Measuring -> MeasuringScreen(
            title = stringResource(R.string.metric_bp),
            color = WearColors.metric(Metric.BLOOD_PRESSURE),
            progress = s.progress,
            secondsLeft = s.secondsLeft,
            samples = s.trace,
            hint = stringResource(if (s.contact) R.string.bp_keep_still else R.string.bp_adjust_watch),
            warn = !s.contact,
            fixedRangeMv = null,
        )
        is BpState.Done -> BpResultScreen(s.systolic, s.diastolic, s.pulse, s.category, onDone = done)
        is BpState.CalibrationRecorded -> BpCalibrationRecordedScreen(s.round, onDone = done)
        BpState.PoorSignal -> SensorErrorScreen(SensorProblem.OFF_BODY, onAction = { vm.reset() })
        is BpState.Failed -> SensorErrorScreen(s.problem, onAction = {
            if (s.problem == SensorProblem.SERVICE_MISSING || s.problem == SensorProblem.SERVICE_OUTDATED) activity?.let(gateway::resolve) else done()
        })
    }
}
