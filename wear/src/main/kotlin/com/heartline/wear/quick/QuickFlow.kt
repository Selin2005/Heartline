package com.heartline.wear.quick

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
import com.heartline.shared.model.Metric
import com.heartline.shared.sensor.PermissionPolicy
import com.heartline.wear.sensor.SensorProblem
import com.heartline.wear.ui.screens.ProfileNeededScreen
import com.heartline.wear.ui.screens.QuickInstructionScreen
import com.heartline.wear.ui.screens.QuickMeasuringScreen
import com.heartline.wear.ui.screens.QuickResultScreen
import com.heartline.wear.ui.screens.SensorErrorScreen
import org.koin.androidx.compose.koinViewModel
import com.heartline.wear.monitor.WatchSettingsStore
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

@Composable
fun QuickFlow(metric: Metric, onExit: () -> Unit, vm: QuickMeasureViewModel = koinViewModel(key = metric.name) { parametersOf(metric) }) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val permissions = PermissionPolicy.permissionsFor(metric, Build.VERSION.SDK_INT).toTypedArray()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { if (it.values.all { ok -> ok }) vm.start() }
    fun start() {
        val missing = permissions.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) vm.start() else launcher.launch(missing.toTypedArray())
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { if (state is QuickState.Measuring) vm.cancel() }
    DisposableEffect(Unit) { onDispose { vm.cancel() } }
    val view = LocalView.current
    DisposableEffect(state is QuickState.Measuring) {
        view.keepScreenOn = state is QuickState.Measuring
        onDispose { view.keepScreenOn = false }
    }
    val prefs by koinInject<WatchSettingsStore>().settings.collectAsStateWithLifecycle()
    LaunchedEffect(state is QuickState.Done) { if (state is QuickState.Done && prefs.haptics) haptics.performHapticFeedback(HapticFeedbackType.Confirm) }
    val done = {
        vm.reset()
        onExit()
    }
    when (val s = state) {
        QuickState.Idle -> QuickInstructionScreen(metric, onStart = ::start)
        QuickState.NeedsProfile -> ProfileNeededScreen(onDone = done)
        is QuickState.Measuring -> QuickMeasuringScreen(metric, s.progress, s.secondsLeft, s.hint)
        is QuickState.Done -> QuickResultScreen(metric, s.summary, onDone = done)
        is QuickState.Failed -> SensorErrorScreen(s.problem ?: SensorProblem.OFF_BODY, onAction = { vm.reset() })
    }
}
