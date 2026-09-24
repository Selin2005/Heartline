package com.heartline.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.TimeText
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.heartline.wear.MainActivity
import com.heartline.wear.bp.BpFlow
import com.heartline.wear.ecg.EcgFlow
import com.heartline.wear.quick.QuickFlow
import com.heartline.shared.model.Metric
import com.heartline.shared.sample.SyntheticEcg
import com.heartline.wear.ui.screens.LauncherEntry
import com.heartline.wear.sensor.SensorProblem
import com.heartline.wear.ui.screens.DevModeGuideScreen
import com.heartline.wear.ui.screens.DiagnosticsScreen
import com.heartline.wear.ui.screens.HeartRateScreen
import com.heartline.wear.ui.screens.WatchSettingsScreen
import com.heartline.wear.ui.screens.HistoryScreen
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import com.heartline.wear.monitor.HeartMonitorService
import com.heartline.wear.ui.screens.LauncherScreen
import com.heartline.wear.ui.screens.SensorErrorScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import com.heartline.wear.ui.theme.HeartlineWearTheme

object WearSample {
    val launcher = listOf(
        LauncherEntry(Metric.ECG, "Sinus rhythm"),
        LauncherEntry(Metric.BLOOD_PRESSURE, "118/76"),
        LauncherEntry(Metric.HEART_RATE, "64 bpm"),
        LauncherEntry(Metric.SPO2, "97%"),
        LauncherEntry(Metric.SKIN_TEMPERATURE, "+0.3 °C"),
        LauncherEntry(Metric.BODY_COMPOSITION, "21.4%"),
        LauncherEntry(Metric.STRESS, "Low"),
    )
    val liveEcg: FloatArray by lazy { SyntheticEcg.generate(durationSec = 3.0, heartRateBpm = 72.0) }
    val livePpg: FloatArray by lazy { com.heartline.shared.sample.SyntheticPpg.generate(3.0, 68.0, 0.5) }
}

private object Routes {
    const val LAUNCHER = "launcher"
    const val ECG = MainActivity.ROUTE_ECG
    const val HISTORY = "history"
    const val HEART_RATE = MainActivity.ROUTE_HEART_RATE
    const val BLOOD_PRESSURE = MainActivity.ROUTE_BP
    const val QUICK = "quick/{metric}"
    const val SETTINGS = "settings"
    const val DEV_MODE = "dev_mode"
    const val DIAGNOSTICS = "diagnostics"

    fun quick(metric: Metric) = "quick/${metric.name}"
}

@Composable
fun HeartlineWearApp(startRoute: String? = null) {
    val launcher: LauncherViewModel = koinViewModel()
    val launcherState by launcher.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { launcher.connect() }
    val nav = rememberSwipeDismissableNavController()
    val context = LocalContext.current
    // First launch: ask for the permissions background monitoring needs, then start it.
    val monitorPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        HeartMonitorService.sync(context, enabled = true)
    }
    LaunchedEffect(Unit) {
        if (!HeartMonitorService.canRun(context)) monitorPermissions.launch(HeartMonitorService.monitoringPermissions)
        // Opened from a notification, tile or complication.
        if (startRoute in setOf(Routes.BLOOD_PRESSURE, Routes.ECG, Routes.HEART_RATE)) nav.navigate(startRoute!!)
    }
    HeartlineWearTheme {
        AppScaffold(timeText = { TimeText() }) {
            SwipeDismissableNavHost(navController = nav, startDestination = Routes.LAUNCHER) {
                composable(Routes.LAUNCHER) {
                    when (val state = launcherState) {
                        is LauncherState.Ready ->
                            LauncherScreen(
                                state.entries,
                                onOpen = {
                                    when (it) {
                                        Metric.ECG -> nav.navigate(Routes.ECG)
                                        Metric.HEART_RATE -> nav.navigate(Routes.HEART_RATE)
                                        Metric.BLOOD_PRESSURE -> nav.navigate(Routes.BLOOD_PRESSURE)
                                        else -> nav.navigate(Routes.quick(it))
                                    }
                                },
                                onHistory = { nav.navigate(Routes.HISTORY) },
                                onSettings = { nav.navigate(Routes.SETTINGS) },
                            )
                        is LauncherState.Problem -> SensorErrorScreen(state.problem, onAction = {
                            if (state.problem == SensorProblem.SDK_POLICY) nav.navigate(Routes.DEV_MODE) else launcher.connect()
                        })
                        LauncherState.Loading -> LauncherScreen(emptyList())
                    }
                }
                composable(Routes.ECG) { EcgFlow(onExit = { nav.popBackStack() }) }
                composable(Routes.BLOOD_PRESSURE) { BpFlow(onExit = { nav.popBackStack() }) }
                composable(Routes.QUICK) { entry ->
                    val metric = Metric.valueOf(entry.arguments?.getString("metric") ?: Metric.SPO2.name)
                    QuickFlow(metric, onExit = { nav.popBackStack() })
                }
                composable(Routes.SETTINGS) {
                    val vm: WatchSettingsViewModel = koinViewModel()
                    val state by vm.state.collectAsStateWithLifecycle()
                    WatchSettingsScreen(state, onDevMode = { nav.navigate(Routes.DEV_MODE) }, onDiagnostics = { nav.navigate(Routes.DIAGNOSTICS) })
                }
                composable(Routes.DIAGNOSTICS) {
                    val vm: WatchSettingsViewModel = koinViewModel()
                    val state by vm.state.collectAsStateWithLifecycle()
                    DiagnosticsScreen(state)
                }
                composable(Routes.DEV_MODE) { DevModeGuideScreen() }
                composable(Routes.HEART_RATE) {
                    val vm: HeartRateViewModel = koinViewModel()
                    val hr by vm.state.collectAsStateWithLifecycle()
                    HeartRateScreen(hr.bpm, hr.recent, hr.onBody)
                }
                composable(Routes.HISTORY) {
                    val vm: HistoryViewModel = koinViewModel()
                    val items by vm.items.collectAsStateWithLifecycle()
                    HistoryScreen(items)
                }
            }
        }
    }
}
