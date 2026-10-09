// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.heartline.wear.ui.components.LocalUserName
import com.heartline.wear.ui.components.LocalCelebrations
import androidx.compose.runtime.LaunchedEffect
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.activity.compose.LocalActivity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import com.heartline.shared.sync.SetupTarget
import com.heartline.wear.R
import com.heartline.wear.link.WatchCommandBus
import com.heartline.wear.sensor.SensorGateway
import com.heartline.wear.ui.setup.CheckingScreen
import com.heartline.wear.ui.setup.CheckingSensorsScreen
import com.heartline.wear.ui.setup.DevModeGuideScreen
import com.heartline.wear.ui.setup.GateState
import com.heartline.wear.ui.setup.PermissionsScreen
import com.heartline.wear.ui.setup.PhoneProblemScreen
import com.heartline.wear.ui.setup.ServiceProblemScreen
import com.heartline.wear.ui.setup.SetupGateViewModel
import com.heartline.wear.ui.setup.SetupIncompleteScreen
import com.heartline.wear.ui.setup.SetupPermissions
import org.koin.compose.koinInject
import com.heartline.datalayer.RemoteOpener
import com.heartline.shared.AppInfo
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
import com.heartline.shared.nav.EntryLinks
import com.heartline.shared.sample.SyntheticEcg
import com.heartline.wear.ui.screens.LauncherEntry
import com.heartline.wear.sensor.SensorProblem
import com.heartline.wear.ui.screens.DiagnosticsScreen
import com.heartline.wear.ui.screens.HeartRateScreen
import com.heartline.wear.ui.screens.BreathingScreen
import com.heartline.wear.ui.screens.WatchSettingsScreen
import com.heartline.wear.ui.screens.WatchToggle
import com.heartline.wear.ui.screens.HistoryScreen
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import com.heartline.wear.monitor.BackgroundMonitoring
import com.heartline.wear.monitor.WatchSettingsStore
import kotlinx.coroutines.launch
import com.heartline.wear.ui.screens.LauncherScreen
import com.heartline.wear.ui.screens.MetricOptionsScreen
import com.heartline.wear.ui.screens.SensorErrorScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import com.heartline.wear.ui.theme.HeartlineWearTheme

object WearSample {
    /** Fixed "now" for screenshots, so "2 h ago" labels never change. */
    const val NOW_MS = 1_790_000_000_000L
    private const val MIN = 60_000L

    val launcher = listOf(
        LauncherEntry(Metric.ECG, "68 bpm", NOW_MS - 2 * 60 * MIN, com.heartline.shared.model.Severity.NORMAL, pinned = true),
        LauncherEntry(Metric.BLOOD_PRESSURE, "118/76", NOW_MS - 25 * MIN, com.heartline.shared.model.Severity.NORMAL),
        LauncherEntry(Metric.HEART_RATE, "64 bpm", NOW_MS - 3 * MIN),
        LauncherEntry(Metric.SPO2, "97%", NOW_MS - 26 * 60 * MIN, com.heartline.shared.model.Severity.NORMAL),
        LauncherEntry(Metric.SKIN_TEMPERATURE, "33.9 °C", NOW_MS - 3 * 24 * 60 * MIN),
        LauncherEntry(Metric.BODY_COMPOSITION, "21.4%", NOW_MS - 5 * 24 * 60 * MIN),
        LauncherEntry(Metric.STRESS, "38/100", NOW_MS - 4 * 60 * MIN, com.heartline.shared.model.Severity.WARN),
    )
    val launcherHeader = com.heartline.wear.ui.LauncherHeader(com.heartline.shared.profile.DayPart.MORNING, "Sara", done = 1, total = 3, next = Metric.BLOOD_PRESSURE, streak = 6)
    val liveEcg: FloatArray by lazy { SyntheticEcg.generate(durationSec = 3.0, heartRateBpm = 72.0) }
    val livePpg: FloatArray by lazy { com.heartline.shared.sample.SyntheticPpg.generate(3.0, 68.0, 0.5) }
}

private object Routes {
    const val LAUNCHER = "launcher"
    const val ECG = MainActivity.ROUTE_ECG
    const val HISTORY = "history"
    const val HEART_RATE = MainActivity.ROUTE_HEART_RATE
    const val BLOOD_PRESSURE = MainActivity.ROUTE_BP
    const val BP_CALIBRATION = MainActivity.ROUTE_BP_CALIBRATION
    const val QUICK = "quick/{metric}"
    const val BREATHE = MainActivity.ROUTE_BREATHE
    const val SETTINGS = "settings"
    const val DEV_MODE = "dev_mode"
    const val DIAGNOSTICS = "diagnostics"
    const val OPTIONS = "options/{metric}"

    fun options(metric: Metric) = "options/${metric.name}"

    fun quick(metric: Metric) = "quick/${metric.name}"

    fun measure(metric: Metric) = when (metric) {
        Metric.ECG -> ECG
        Metric.HEART_RATE -> HEART_RATE
        Metric.BLOOD_PRESSURE -> BLOOD_PRESSURE
        else -> quick(metric)
    }

    /** A measurement the phone started: `remote/<METRIC>?session=<id>[&round=<n>]`. */
    const val REMOTE = "remote/{metric}?session={session}&round={round}"

    /** Screens the phone, notifications, tiles and complications may open. */
    fun isExternal(route: String) = route in setOf(ECG, HEART_RATE, BLOOD_PRESSURE, BP_CALIBRATION, HISTORY) ||
        (route.startsWith("quick/") && Metric.entries.any { route == quick(it) }) ||
        com.heartline.shared.measure.RemoteMeasureLink.parse(route) != null
}

/**
 * Opens [link]. From a tile, complication or notification the screen stands alone, so swiping
 * back (or finishing) returns to that tile or watch face; from the phone it opens on top of the
 * launcher.
 */
private fun NavHostController.openExternal(link: String) {
    val entry = EntryLinks.parse(link)
    val route = entry.route
    if (route == Routes.LAUNCHER || !Routes.isExternal(route)) {
        if (!popBackStack(Routes.LAUNCHER, inclusive = false)) {
            navigate(Routes.LAUNCHER) { popUpTo(graph.id) { inclusive = true } }
        }
        return
    }
    val launcherBelow = runCatching { getBackStackEntry(Routes.LAUNCHER) }.isSuccess
    navigate(route) {
        when {
            entry.external -> popUpTo(graph.id) { inclusive = true }
            launcherBelow -> popUpTo(Routes.LAUNCHER)
        }
        launchSingleTop = true
    }
}

/** Leaves the current screen: back to the one below, or out of the app when it stands alone. */
private fun NavHostController.exit(activity: android.app.Activity?) {
    if (previousBackStackEntry == null) activity?.finish() else popBackStack()
}

@Composable
fun HeartlineWearApp(startRoute: String? = null) {
    val gate: SetupGateViewModel = koinViewModel()
    val gateState by gate.state.collectAsStateWithLifecycle()
    val openedOnPhone by gate.openedOnPhone.collectAsStateWithLifecycle()
    val gateway: SensorGateway = koinInject()
    val bus: WatchCommandBus = koinInject()
    val context = LocalContext.current
    val activity = LocalActivity.current
    val nav = rememberSwipeDismissableNavController()
    // Every start re-checks the phone link; once set up this runs quietly in the background.
    LifecycleEventEffect(Lifecycle.Event.ON_START) { gate.check() }

    var pendingRoute by rememberSaveable { mutableStateOf(startRoute) }
    LaunchedEffect(bus) { bus.navigate.collect { pendingRoute = it } }
    val ready = gateState is GateState.Ready
    val coordinator: com.heartline.wear.remote.RemoteMeasureCoordinator = koinInject()
    LaunchedEffect(pendingRoute, gateState) {
        // A measurement the phone started can't wait for setup: tell the phone why, and drop it.
        val link = pendingRoute?.let { com.heartline.shared.measure.RemoteMeasureLink.parse(EntryLinks.parse(it).route) } ?: return@LaunchedEffect
        val problem = when (val g = gateState) {
            is GateState.PhoneProblem, is GateState.SetupIncomplete -> com.heartline.shared.sync.MeasureProblem.NEEDS_SETUP
            GateState.NeedsPermissions -> com.heartline.shared.sync.MeasureProblem.PERMISSION
            is GateState.SensorIssue -> when (g.problem) {
                SensorProblem.SDK_POLICY -> com.heartline.shared.sync.MeasureProblem.SDK_POLICY
                SensorProblem.PERMISSION -> com.heartline.shared.sync.MeasureProblem.PERMISSION
                else -> com.heartline.shared.sync.MeasureProblem.SENSOR
            }
            else -> null
        } ?: return@LaunchedEffect
        coordinator.reject(link, problem)
        pendingRoute = null
    }
    LaunchedEffect(pendingRoute, ready) {
        val route = pendingRoute ?: return@LaunchedEffect
        if (route == MainActivity.ROUTE_SETUP) {
            // "Open the check on my watch" from the phone's help page.
            pendingRoute = null
            gate.recheckSensors()
        } else if (ready) {
            pendingRoute = null
            nav.openExternal(route)
        }
    }

    var permissionDenials by rememberSaveable { mutableIntStateOf(0) }
    val settingsStore: WatchSettingsStore = koinInject()
    val scope = rememberCoroutineScope()
    fun applyMonitoring() {
        scope.launch { BackgroundMonitoring.sync(context, settingsStore.settings.value) }
    }
    // Background sensor access must be asked for on its own, after the foreground permission.
    val background = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { applyMonitoring() }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val granted = SetupPermissions.required.all { p -> context.checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (!granted) permissionDenials++
        applyMonitoring()
        gate.onPermissionsResult()
        if (granted && !BackgroundMonitoring.hasBackgroundPermission(context)) background.launch(BackgroundMonitoring.backgroundPermission)
    }

    val profiles: com.heartline.wear.quick.WatchProfileStore = koinInject()
    val profile by profiles.profile.collectAsStateWithLifecycle()
    val monitor by settingsStore.settings.collectAsStateWithLifecycle()
    val userName = profile?.displayName?.takeIf { monitor.showNameOnWatch && it.isNotBlank() }
    val records: com.heartline.wear.data.WatchRecordStore = koinInject()
    val history by records.history.collectAsStateWithLifecycle(emptyList())
    // The newest record is usually the one on screen: judge it against the ones before.
    val baselines = remember(history) { com.heartline.shared.profile.PersonalBaseline.all(history, System.currentTimeMillis(), exclude = history.firstOrNull()?.id) }

    HeartlineWearTheme(monitor.accent) {
        CompositionLocalProvider(
            LocalUserName provides userName,
            LocalCelebrations provides monitor.celebrations,
            com.heartline.wear.ui.components.LocalBaselines provides baselines,
        ) {
        AppScaffold(timeText = { TimeText() }) {
            when (val g = gateState) {
                GateState.CheckingPhone -> CheckingScreen(
                    Icons.Rounded.PhoneAndroid,
                    stringResource(R.string.link_checking_title),
                    stringResource(R.string.link_checking_body),
                )
                is GateState.PhoneProblem -> PhoneProblemScreen(
                    g.stage,
                    onRetry = { gate.check() },
                    onOpenOnPhone = { gate.openOnPhone(SetupTarget.HOME) },
                    opened = openedOnPhone,
                )
                is GateState.SetupIncomplete -> SetupIncompleteScreen(
                    g.status.displayName.ifBlank { null },
                    // The phone app shows the terms before any other screen, so HOME is enough.
                    onOpenOnPhone = { gate.openOnPhone(if (g.status.termsAccepted) SetupTarget.PROFILE else SetupTarget.HOME) },
                    opened = openedOnPhone,
                    termsPending = !g.status.termsAccepted,
                )
                GateState.NeedsPermissions -> PermissionsScreen(onAllow = {
                    if (permissionDenials >= 2) {
                        // The system stops showing the dialog after repeated denials: open app settings instead.
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    } else {
                        permissions.launch(SetupPermissions.requested)
                    }
                })
                GateState.CheckingSensors -> CheckingSensorsScreen()
                is GateState.SensorIssue -> when (g.problem) {
                    SensorProblem.SDK_POLICY -> DevModeGuideScreen(
                        problemFound = true,
                        onCheckAgain = { gate.recheckSensors() },
                        onShowOnPhone = { gate.openOnPhone(SetupTarget.DEV_MODE_HELP) },
                    )
                    SensorProblem.SERVICE_MISSING, SensorProblem.SERVICE_OUTDATED -> ServiceProblemScreen(
                        outdated = g.problem == SensorProblem.SERVICE_OUTDATED,
                        onAction = { activity?.let(gateway::resolve) },
                    )
                    SensorProblem.PERMISSION -> PermissionsScreen(onAllow = { permissions.launch(SetupPermissions.requested) })
                    else -> SensorErrorScreen(g.problem, onAction = { gate.recheckSensors() })
                }
                is GateState.Ready -> AppNavHost(nav, gate)
            }
        }
        }
    }
}

@Composable
private fun AppNavHost(nav: NavHostController, gate: SetupGateViewModel) {
    val activity = LocalActivity.current
    val exit: () -> Unit = { nav.exit(activity) }
    val launcher: LauncherViewModel = koinViewModel()
    val launcherState by launcher.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { launcher.connect() }
    SwipeDismissableNavHost(navController = nav, startDestination = Routes.LAUNCHER) {
        composable(Routes.LAUNCHER) {
            when (val state = launcherState) {
                is LauncherState.Ready -> {
                    // Decided once per visit to the launcher (and at most once a day per reason).
                    val celebrate = remember(state.header.birthday, state.header.next) { launcher.celebrate(state.header) }
                    LauncherScreen(
                        state.entries,
                        header = state.header,
                        celebrate = celebrate,
                        onOpen = { nav.navigate(Routes.measure(it)) },
                        onOptions = { nav.navigate(Routes.options(it)) },
                        onHistory = { nav.navigate(Routes.HISTORY) },
                        onSettings = { nav.navigate(Routes.SETTINGS) },
                    )
                }
                is LauncherState.Problem -> SensorErrorScreen(state.problem, onAction = {
                    if (state.problem == SensorProblem.SDK_POLICY) nav.navigate(Routes.DEV_MODE) else launcher.connect()
                })
                LauncherState.Loading -> LauncherScreen(emptyList())
            }
        }
        composable(Routes.ECG) { EcgFlow(onExit = exit) }
        composable(
            Routes.REMOTE,
            arguments = listOf(
                androidx.navigation.navArgument("session") { type = androidx.navigation.NavType.StringType },
                androidx.navigation.navArgument("round") {
                    type = androidx.navigation.NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            val metric = entry.arguments?.getString("metric")?.let { name -> Metric.entries.firstOrNull { it.name == name } }
            val session = entry.arguments?.getString("session")
            if (metric != null && session != null && metric.measuresOnPhone) {
                val link = com.heartline.shared.measure.RemoteMeasureLink(metric, session, entry.arguments?.getString("round")?.toIntOrNull())
                com.heartline.wear.remote.RemoteMeasureFlow(link, onExit = exit)
            } else {
                LaunchedEffect(Unit) { exit() }
            }
        }
        composable(Routes.BLOOD_PRESSURE) {
            BpFlow(onExit = exit, onStartCalibration = { nav.openExternal(Routes.BP_CALIBRATION) })
        }
        composable(Routes.BP_CALIBRATION) { BpFlow(onExit = exit, calibrationSession = true) }
        composable(Routes.QUICK) { entry ->
            val metric = Metric.valueOf(entry.arguments?.getString("metric") ?: Metric.SPO2.name)
            QuickFlow(metric, onExit = exit)
        }
        composable(Routes.OPTIONS) { entry ->
            val metric = Metric.valueOf(entry.arguments?.getString("metric") ?: Metric.ECG.name)
            val pinned = (launcherState as? LauncherState.Ready)?.entries?.firstOrNull { it.metric == metric }?.pinned == true
            MetricOptionsScreen(
                metric,
                pinned,
                onPin = {
                    launcher.togglePin(metric)
                    nav.popBackStack()
                },
                onMeasure = {
                    nav.popBackStack()
                    nav.navigate(Routes.measure(metric))
                },
                onHistory = {
                    nav.popBackStack()
                    nav.navigate(Routes.HISTORY)
                },
            )
        }
        composable(Routes.SETTINGS) {
            val vm: WatchSettingsViewModel = koinViewModel()
            val state by vm.state.collectAsStateWithLifecycle()
            val scope = rememberCoroutineScope()
            val remote: RemoteOpener = koinInject()
            WatchSettingsScreen(
                state,
                onToggle = { toggle, on ->
                    vm.change { s ->
                        when (toggle) {
                            WatchToggle.ALL_DAY_HEART_RATE -> s.withAllDayHeartRate(on)
                            WatchToggle.HEART_MONITORING -> s.withMonitoring(on)
                            WatchToggle.HEART_PART -> s.withHeartAlerts(on)
                            WatchToggle.SPO2_PART -> s.copy(spo2Monitoring = on)
                            WatchToggle.TEMP_PART -> s.copy(skinTempMonitoring = on)
                            WatchToggle.STRESS_PART -> s.copy(stressMonitoring = on)
                            WatchToggle.HAPTICS -> s.copy(haptics = on)
                            WatchToggle.LIVE_WAVE -> s.copy(liveWave = on)
                        }
                    }
                },
                onSensitivity = { level -> vm.change { it.copy(alertSensitivity = level) } },
                onDevMode = { nav.navigate(Routes.DEV_MODE) },
                onDiagnostics = { nav.navigate(Routes.DIAGNOSTICS) },
                onSourceCode = { scope.launch { remote.open(AppInfo.REPO_URL) } },
                onCommunity = { scope.launch { remote.open(AppInfo.COMMUNITY_URL) } },
            )
        }
        composable(Routes.DIAGNOSTICS) {
            val vm: WatchSettingsViewModel = koinViewModel()
            val state by vm.state.collectAsStateWithLifecycle()
            DiagnosticsScreen(state)
        }
        composable(Routes.DEV_MODE) {
            DevModeGuideScreen(
                problemFound = false,
                onCheckAgain = {
                    nav.popBackStack(Routes.LAUNCHER, inclusive = false)
                    gate.recheckSensors()
                },
                onShowOnPhone = { gate.openOnPhone(SetupTarget.DEV_MODE_HELP) },
            )
        }
        composable(Routes.HEART_RATE) {
            val vm: HeartRateViewModel = koinViewModel()
            val hr by vm.state.collectAsStateWithLifecycle()
            HeartRateScreen(hr.bpm, hr.onBody)
        }
        composable(Routes.BREATHE) {
            val store: com.heartline.wear.monitor.WatchSettingsStore = koinInject()
            val prefs by store.settings.collectAsStateWithLifecycle()
            BreathingScreen(
                buzz = com.heartline.wear.ui.components.rememberBuzz(prefs.haptics),
                onMeasure = { nav.navigate(Routes.quick(Metric.STRESS)) { popUpTo(Routes.BREATHE) { inclusive = true } } },
                onDone = exit,
            )
        }
        composable(Routes.HISTORY) {
            val vm: HistoryViewModel = koinViewModel()
            val items by vm.items.collectAsStateWithLifecycle()
            HistoryScreen(items)
        }
    }
}
