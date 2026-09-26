package com.heartline.wear.ecg

import com.heartline.wear.ui.components.Buzz
import com.heartline.wear.ui.components.rememberBuzz
import com.heartline.shared.model.Severity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.heartline.shared.model.Metric
import com.heartline.shared.sensor.PermissionPolicy
import com.heartline.wear.sensor.SensorGateway
import com.heartline.wear.sensor.SensorProblem
import com.heartline.wear.ui.screens.EcgAnalyzingScreen
import com.heartline.wear.ui.screens.EcgInstructionScreen
import com.heartline.wear.ui.screens.EcgMeasuringScreen
import com.heartline.wear.ui.screens.EcgResultScreen
import com.heartline.wear.ui.screens.SensorErrorScreen
import org.koin.androidx.compose.koinViewModel
import com.heartline.wear.monitor.WatchSettingsStore
import org.koin.compose.koinInject

/** Instruction → permission → 30 s recording → result, as on SHM. */
@Composable
fun EcgFlow(
    onExit: () -> Unit,
    vm: EcgMeasureViewModel = koinViewModel(),
    gateway: SensorGateway = koinInject(),
    settingsStore: WatchSettingsStore = koinInject(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val prefs by settingsStore.settings.collectAsStateWithLifecycle()
    val buzz = prefs.haptics
    val context = LocalContext.current
    val activity = LocalActivity.current
    val haptics = LocalHapticFeedback.current
    val permissions = remember { PermissionPolicy.permissionsFor(Metric.ECG, Build.VERSION.SDK_INT).toTypedArray() }
    var permissionDenied by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) {
            permissionDenied = false
            vm.start()
        } else {
            permissionDenied = true
        }
    }
    fun startWithPermission() {
        val missing = permissions.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) vm.start() else permissionLauncher.launch(missing.toTypedArray())
    }

    // On-demand trackers only run in the foreground: stop if the user leaves.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { if (state is EcgMeasureState.Measuring) vm.cancel() }
    DisposableEffect(Unit) { onDispose { vm.cancel() } }

    val measuring = state as? EcgMeasureState.Measuring
    val vibrate = rememberBuzz(buzz)
    val view = LocalView.current
    DisposableEffect(measuring != null) {
        view.keepScreenOn = measuring != null
        onDispose { view.keepScreenOn = false }
    }
    // Buzz when contact is lost mid-recording (not while waiting for or confirming the first touch).
    LaunchedEffect(measuring?.leadOff) {
        if (measuring?.leadOff == true && measuring.started && !measuring.arming) if (buzz) haptics.performHapticFeedback(HapticFeedbackType.Reject)
    }
    // One short buzz when the countdown really starts: the touch was confirmed as an ECG.
    LaunchedEffect(measuring?.started) {
        if (measuring?.started == true) vibrate(Buzz.STARTED)
    }
    LaunchedEffect(state is EcgMeasureState.Done) {
        (state as? EcgMeasureState.Done)?.let { vibrate(if (it.result.severity == Severity.WARN || it.result.severity == Severity.ALERT) Buzz.ATTENTION else Buzz.DONE) }
    }

    when (val s = state) {
        EcgMeasureState.Idle ->
            if (permissionDenied) {
                SensorErrorScreen(SensorProblem.PERMISSION, onAction = ::startWithPermission)
            } else {
                EcgInstructionScreen(onStart = ::startWithPermission)
            }
        is EcgMeasureState.Measuring -> EcgMeasuringScreen(
            s.progress,
            s.secondsLeft,
            s.trace,
            s.leadOff,
            bpm = s.bpm,
            endIndex = s.endIndex,
            waitingForTouch = s.waitingForTouch,
            showWave = prefs.liveWave,
            arming = s.arming,
            struggling = s.struggling,
        )
        EcgMeasureState.Analyzing -> EcgAnalyzingScreen()
        is EcgMeasureState.Done -> EcgResultScreen(s.result, s.averageBpm, s.metrics, onDone = {
            vm.reset()
            onExit()
        })
        is EcgMeasureState.Failed -> SensorErrorScreen(s.problem, onAction = {
            when (s.problem) {
                SensorProblem.PERMISSION -> {
                    vm.reset()
                    startWithPermission()
                }
                SensorProblem.SERVICE_MISSING, SensorProblem.SERVICE_OUTDATED -> activity?.let(gateway::resolve)
                else -> {
                    vm.reset()
                    onExit()
                }
            }
        })
    }
}
