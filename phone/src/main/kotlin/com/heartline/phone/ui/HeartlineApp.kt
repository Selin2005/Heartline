// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import android.content.Intent
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import com.heartline.phone.ui.bp.BpCalibrationScreen
import com.heartline.phone.ui.bp.BpHomeScreen
import com.heartline.phone.BuildConfig
import com.heartline.phone.export.DataExporter
import com.heartline.phone.ui.about.AboutScreen
import com.heartline.phone.legal.BundledDoc
import com.heartline.phone.ui.legal.LegalDocumentScreen
import com.heartline.phone.notify.PhoneNotifier
import com.heartline.phone.ui.update.UpdatesScreen
import com.heartline.phone.ui.update.WhatsNewAfterUpdate
import androidx.compose.runtime.saveable.rememberSaveable
import com.heartline.phone.diag.DiagnosticsViewModel
import com.heartline.phone.ui.diagnostics.DiagnosticsQuestion
import com.heartline.phone.ui.diagnostics.DiagnosticsScreen
import com.heartline.phone.ui.diagnostics.rememberLogFolderPicker
import com.heartline.phone.update.UpdatesViewModel
import com.heartline.phone.ui.heart.AlertsScreen
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.heartline.phone.link.OpenResult
import com.heartline.phone.link.PhoneRoutes
import com.heartline.phone.link.WatchRoutes
import com.heartline.phone.ui.help.DevModeHelpScreen
import com.heartline.phone.ui.model.OpenOnWatchViewModel
import com.heartline.phone.ui.model.WatchLinkViewModel
import org.koin.compose.koinInject
import com.heartline.phone.ui.body.BodyCompositionScreen
import com.heartline.phone.ui.metric.MetricDetailScreen
import com.heartline.phone.ui.model.BodyCompositionViewModel
import com.heartline.phone.ui.model.MetricDetailViewModel
import com.heartline.phone.ui.model.ProfileViewModel
import com.heartline.phone.ui.profile.ProfileScreen
import com.heartline.phone.ui.model.BpHomeViewModel
import com.heartline.phone.ui.model.CalibrationViewModel
import com.heartline.phone.ui.heart.HeartRateScreen
import com.heartline.phone.ui.model.AlertsViewModel
import com.heartline.phone.ui.model.HeartRateViewModel
import com.heartline.shared.model.Metric
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.heartline.phone.ui.ecg.DeleteRecordingDialog
import com.heartline.phone.ui.ecg.SymptomsSheetContent
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.CompositionLocalProvider
import com.heartline.phone.ui.components.LocalOpenAppHome
import com.heartline.shared.nav.EntryLinks
import com.heartline.phone.qs.BpQsTile
import com.heartline.phone.qs.EcgQsTile
import com.heartline.phone.qs.HeartlineQsTile
import com.heartline.phone.qs.QuickTilePrefs
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.heartline.phone.R
import com.heartline.phone.report.EcgReportBuilder
import com.heartline.phone.share.ResultSummary
import com.heartline.phone.ui.share.ShareFormat
import com.heartline.phone.ui.share.ShareRequest
import com.heartline.phone.ui.share.rememberShareSheet
import com.heartline.phone.ui.ecg.EcgDetailScreen
import com.heartline.phone.ui.ecg.EcgHistoryScreen
import com.heartline.phone.ui.ecg.EcgHomeScreen
import com.heartline.phone.ui.home.HomeScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.heartline.phone.ui.model.EcgDetailViewModel
import com.heartline.phone.ui.model.EcgListViewModel
import com.heartline.phone.ui.model.HomeViewModel
import com.heartline.phone.ui.model.SettingsViewModel
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import com.heartline.phone.ui.settings.SettingsScreen
import com.heartline.phone.ui.theme.HeartlineTheme

object Routes {
    const val HOME = "home"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val MONITORING_SETUP = "monitoring_setup"
    const val ECG = "ecg"
    const val ECG_DETAIL = "ecg/{id}"
    const val HEART_RATE = "heart_rate"
    const val ALERTS = "alerts"
    const val BLOOD_PRESSURE = "blood_pressure"
    const val BP_CALIBRATION = "blood_pressure/calibration"
    const val METRIC = "metric/{metric}"
    const val PROFILE = "profile"
    const val ABOUT = "about"
    const val DEV_MODE_HELP = "help/dev_mode"
    const val DOC = "doc/{doc}"
    const val UPDATES = PhoneNotifier.UPDATES_ROUTE
    const val DIAGNOSTICS = "diagnostics"

    fun doc(doc: BundledDoc) = "doc/${doc.route}"

    fun metric(metric: Metric) = "metric/${metric.name}"

    fun ecgDetail(id: String) = "ecg/$id"
}

private data class Tab(val route: String, val label: Int, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, R.string.tab_home, Icons.Rounded.Home),
    Tab(Routes.HISTORY, R.string.tab_history, Icons.Rounded.History),
    Tab(Routes.SETTINGS, R.string.tab_settings, Icons.Rounded.Settings),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HeartlineApp(
    navController: NavHostController = rememberNavController(),
    openAlerts: Boolean = false,
    deepLink: String? = null,
    onDeepLinkHandled: () -> Unit = {},
) {
    LaunchedEffect(openAlerts) { if (openAlerts) navController.navigate(Routes.ALERTS) }
    LaunchedEffect(deepLink) {
        val link = EntryLinks.parse(deepLink ?: return@LaunchedEffect)
        val target = when (link.route) {
            PhoneRoutes.PROFILE -> Routes.PROFILE
            PhoneRoutes.BP_CALIBRATION -> Routes.BP_CALIBRATION
            PhoneRoutes.DEV_MODE_HELP -> Routes.DEV_MODE_HELP
            PhoneRoutes.SETTINGS -> Routes.SETTINGS
            Routes.UPDATES, PhoneNotifier.UPDATES_INSTALL_ROUTE -> link.route
            // Home-screen widgets open their metric.
            Routes.HEART_RATE, Routes.ECG, Routes.BLOOD_PRESSURE -> link.route
            else -> link.route.takeIf { it.startsWith("metric/") && Metric.entries.any { m -> it == Routes.metric(m) } } ?: Routes.HOME
        }
        when {
            target == Routes.HOME || target == Routes.SETTINGS -> navController.navigateTab(target)
            // From a widget, Quick Settings or a notification the screen stands alone: Back returns
            // to wherever the user was (the home screen), not to the app's home.
            link.external -> navController.navigate(target) {
                popUpTo(navController.graph.id) { inclusive = true }
                launchSingleTop = true
            }
            else -> navController.navigate(target) { launchSingleTop = true }
        }
        onDeepLinkHandled()
    }
    WhatsNewAfterUpdate(BuildConfig.VERSION_NAME, onAllNotes = { navController.navigate(Routes.doc(BundledDoc.CHANGELOG)) })
    // Stable users are asked once whether to keep diagnostic logs (beta users have them on).
    val diagnostics: DiagnosticsViewModel = koinViewModel()
    val diagnosticsUi by diagnostics.ui.collectAsStateWithLifecycle()
    var askedNow by rememberSaveable { mutableStateOf(false) }
    if (diagnosticsUi.shouldAsk && !askedNow) {
        DiagnosticsQuestion { keep ->
            askedNow = true
            diagnostics.setEnabled(keep)
        }
    }
    // One opener for every "Measure on watch" button, with a snackbar saying what happened.
    val opener: OpenOnWatchViewModel = koinViewModel()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(opener) {
        opener.events.collect { result ->
            val text = when (result) {
                OpenResult.OPENED -> R.string.open_result_opened
                OpenResult.NOTIFIED -> R.string.open_result_notified
                OpenResult.NO_WATCH -> R.string.open_result_no_watch
            }
            snackbar.showSnackbar(context.getString(text))
        }
    }
    val openOnWatch: (String) -> Unit = { opener.open(it) }
    val share = rememberShareSheet()
    val reports: EcgReportBuilder = koinInject()
    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route ?: Routes.HOME
    val activity = LocalActivity.current
    // A screen opened on its own (from a widget…) has nothing under it: Back leaves the app.
    val standalone = backStack != null && navController.previousBackStackEntry == null && tabs.none { it.route == route }
    val goBack: () -> Unit = { if (navController.previousBackStackEntry == null) activity?.finish() else navController.popBackStack() }
    BackHandler(enabled = standalone) { activity?.finish() }
    val openHome: () -> Unit = {
        navController.navigate(Routes.HOME) {
            popUpTo(navController.graph.id) { inclusive = true }
            launchSingleTop = true
        }
    }
    Scaffold(
        containerColor = HeartlineTheme.colors.background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { if (tabs.any { it.route == route }) OneUiBottomBar(route) { navController.navigateTab(it) } },
    ) { padding ->
        // Only the bottom is applied (screens draw under the status bar themselves); consume it so a
        // screen's imePadding doesn't add the navigation and bottom bars on top of the keyboard.
        val bottom = PaddingValues(bottom = padding.calculateBottomPadding())
        Box(Modifier.fillMaxSize().padding(bottom).consumeWindowInsets(bottom)) {
            CompositionLocalProvider(LocalOpenAppHome provides if (standalone) openHome else null) {
            NavHost(navController, startDestination = Routes.HOME) {
                composable(Routes.HOME) {
                    val vm: HomeViewModel = koinViewModel()
                    val state by vm.state.collectAsStateWithLifecycle()
                    val linkVm: WatchLinkViewModel = koinViewModel()
                    val link by linkVm.link.collectAsStateWithLifecycle()
                    LifecycleResumeEffect(Unit) {
                        linkVm.refresh()
                        onPauseOrDispose {}
                    }
                    val updatePrefs by koinInject<com.heartline.phone.update.UpdateRepository>().prefs.collectAsStateWithLifecycle(null)
                    HomeScreen(
                        state,
                        versionMismatch = updatePrefs?.watchVersion?.takeIf { it != BuildConfig.VERSION_NAME }?.let { it to BuildConfig.VERSION_NAME },
                        updateReady = updatePrefs?.ready?.version?.takeIf { it != BuildConfig.VERSION_NAME },
                        onUpdates = { navController.navigate(Routes.UPDATES) },
                        onInstallUpdate = { navController.navigate(PhoneNotifier.UPDATES_INSTALL_ROUTE) },
                        watchLink = link,
                        onWatchRetry = linkVm::refresh,
                        onOpenWatch = linkVm::openWatchApp,
                        onOpenEcg = { navController.navigate(Routes.ECG) },
                        onOpenMetric = {
                            when (it) {
                                Metric.HEART_RATE -> navController.navigate(Routes.HEART_RATE)
                                Metric.BLOOD_PRESSURE -> navController.navigate(Routes.BLOOD_PRESSURE)
                                Metric.ECG -> navController.navigate(Routes.ECG)
                                else -> navController.navigate(Routes.metric(it))
                            }
                        },
                    )
                }
                composable(Routes.HISTORY) {
                    val vm: EcgListViewModel = koinViewModel()
                    val state by vm.state.collectAsStateWithLifecycle()
                    EcgHistoryScreen(state, onOpenRecord = { navController.navigate(Routes.ecgDetail(it)) })
                }
                composable(Routes.SETTINGS) {
                    val vm: SettingsViewModel = koinViewModel()
                    val context = LocalContext.current
                    val exporter: DataExporter = koinInject()
                    val monitor by vm.monitor.collectAsStateWithLifecycle()
                    val heartLimits by vm.heartLimits.collectAsStateWithLifecycle()
                    val vitalsLimits by vm.vitalsLimits.collectAsStateWithLifecycle()
                    val stressLimits by vm.stressLimits.collectAsStateWithLifecycle()
                    val sharingPrefs by vm.sharing.collectAsStateWithLifecycle()
                    val linkVm: WatchLinkViewModel = koinViewModel()
                    val link by linkVm.link.collectAsStateWithLifecycle()
                    LifecycleResumeEffect(Unit) {
                        linkVm.refresh()
                        onPauseOrDispose {}
                    }
                    val scope = rememberCoroutineScope()
                    val quickTileMetric by remember { QuickTilePrefs.metric(context) }.collectAsStateWithLifecycle(Metric.SPO2)
                    SettingsScreen(
                        monitor,
                        versionName = BuildConfig.VERSION_NAME,
                        watchConnected = link?.let { it.probe == com.heartline.shared.sync.PeerProbe.REACHABLE },
                        onChange = vm::change,
                        onDeleteAll = { vm.deleteAll() },
                        onProfile = { navController.navigate(Routes.PROFILE) },
                        onWatch = linkVm::refresh,
                        onExport = {
                            share(
                                ShareRequest(
                                    kind = "Export",
                                    formats = listOf(ShareFormat("csv", "text/csv", R.string.share_format_csv) { name, _, _ -> vm.exportFile(exporter, name) }),
                                    text = { _, _ -> "" },
                                    allowAi = false,
                                ),
                            )
                        },
                        onAbout = { navController.navigate(Routes.ABOUT) },
                        onUpdates = { navController.navigate(Routes.UPDATES) },
                        onDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
                        diagnosticLogs = monitor.diagnosticLogs,
                        sharing = sharingPrefs,
                        onAiPrompt = vm::setAiPrompt,
                        onAiAttachPdf = vm::setAiAttachPdf,
                        onReportName = vm::setReportName,
                        quickTileMetric = quickTileMetric,
                        onQuickTileMetric = { m -> scope.launch { QuickTilePrefs.setMetric(context, m) } },
                        heartLimits = heartLimits,
                        vitalsLimits = vitalsLimits,
                        stressLimits = stressLimits,
                        onHealthAnswers = { navController.navigate(Routes.MONITORING_SETUP) },
                        onAddQuickTiles = {
                            val asked = addQuickTiles(context, HeartlineQsTile.ALL) { added ->
                                if (added) scope.launch { snackbar.showSnackbar(context.getString(R.string.settings_quick_tiles_added)) }
                            }
                            if (!asked) scope.launch { snackbar.showSnackbar(context.getString(R.string.settings_quick_tiles_manual)) }
                        },
                    )
                }
                composable(Routes.MONITORING_SETUP) {
                    com.heartline.phone.ui.onboarding.MonitoringSetupRoute(onDone = goBack, onBack = goBack)
                }
                composable(Routes.ECG) {
                    val vm: EcgListViewModel = koinViewModel()
                    val state by vm.state.collectAsStateWithLifecycle()
                    EcgHomeScreen(
                        state,
                        onRecordOnWatch = { openOnWatch(WatchRoutes.ECG) },
                        onBack = goBack,
                        onOpenRecord = { navController.navigate(Routes.ecgDetail(it)) },
                        onViewAll = { navController.navigateTab(Routes.HISTORY) },
                    )
                }
                composable(Routes.HEART_RATE) {
                    val vm: HeartRateViewModel = koinViewModel()
                    val state by vm.state.collectAsStateWithLifecycle()
                    HeartRateScreen(
                        state,
                        onBack = goBack,
                        onOpenAlerts = { navController.navigate(Routes.ALERTS) },
                        onMeasureOnWatch = { openOnWatch(WatchRoutes.HEART_RATE) },
                        onShare = {
                            share(
                                ShareRequest("HeartRate", text = { prompt, person ->
                                    ResultSummary.heartRate(context.resources, state, person, prompt)
                                }),
                            )
                        },
                    )
                }
                composable(Routes.BLOOD_PRESSURE) {
                    val vm: BpHomeViewModel = koinViewModel()
                    val state by vm.state.collectAsStateWithLifecycle()
                    BpHomeScreen(
                        state,
                        onBack = goBack,
                        onCalibrate = { navController.navigate(Routes.BP_CALIBRATION) },
                        onMeasureOnWatch = { openOnWatch(WatchRoutes.BLOOD_PRESSURE) },
                        onValidate = vm::validateLatest,
                        onProfileChange = vm::saveProfile,
                        onShare = {
                            share(
                                ShareRequest(
                                    "BloodPressure",
                                    // Raw pulse waves with their cuff readings, for offline analysis (tools/bp-ml).
                                    formats = listOf(
                                        ShareFormat("json", "application/json", R.string.share_format_bp_data) { name, _, _ ->
                                            vm.exportDataset(java.io.File(context.cacheDir, "exports"), name)
                                        },
                                        // Every sensor of every session, for developing the algorithm (tools/bp-ml/read_session.py).
                                        ShareFormat("zip", "application/zip", R.string.share_format_bp_sessions) { name, _, _ ->
                                            vm.exportSessions(java.io.File(context.cacheDir, "exports"), name)
                                        },
                                    ),
                                    text = { prompt, person -> ResultSummary.bp(context.resources, state, person, prompt) },
                                ),
                            )
                        },
                    )
                }
                composable(Routes.BP_CALIBRATION) {
                    val vm: CalibrationViewModel = koinViewModel()
                    val state by vm.state.collectAsStateWithLifecycle()
                    BpCalibrationScreen(
                        state,
                        onBack = goBack,
                        onStart = { vm.startRound() },
                        onSubmit = { s, d, p -> vm.submitCuff(s, d, p) },
                        onDone = goBack,
                        onProfileChange = vm::setProfile,
                        onAddStanding = { vm.addStandingRound() },
                        onFinish = { vm.finish() },
                    )
                }
                composable(Routes.METRIC) { entry ->
                    val metric = Metric.valueOf(entry.arguments?.getString("metric") ?: Metric.SPO2.name)
                    if (metric == Metric.BODY_COMPOSITION) {
                        val vm: BodyCompositionViewModel = koinViewModel()
                        val state by vm.state.collectAsStateWithLifecycle()
                        BodyCompositionScreen(state, onBack = goBack, onMeasureOnWatch = { openOnWatch(WatchRoutes.quick(metric)) })
                    } else {
                        val vm: MetricDetailViewModel = koinViewModel(key = metric.name) { parametersOf(metric) }
                        val state by vm.state.collectAsStateWithLifecycle()
                        MetricDetailScreen(state, onBack = goBack, onMeasureOnWatch = { openOnWatch(WatchRoutes.quick(metric)) })
                    }
                }
                composable(Routes.PROFILE) {
                    val vm: ProfileViewModel = koinViewModel()
                    val profile by vm.profile.collectAsStateWithLifecycle()
                    ProfileScreen(profile, onBack = goBack, onSave = { vm.save(it) { goBack() } })
                }
                composable(Routes.DEV_MODE_HELP) {
                    DevModeHelpScreen(onBack = goBack, onCheckOnWatch = { openOnWatch(WatchRoutes.SETUP) })
                }
                composable(Routes.ABOUT) {
                    AboutScreen(BuildConfig.VERSION_NAME, onBack = goBack, onOpenDoc = { navController.navigate(Routes.doc(it)) })
                }
                // "updates?install=true" (the "ready to install" notification) starts the installer.
                composable(
                    "${Routes.UPDATES}?install={install}",
                    arguments = listOf(navArgument("install") {
                        type = NavType.BoolType
                        defaultValue = false
                    }),
                ) { entry ->
                    val vm: UpdatesViewModel = koinViewModel()
                    val ui by vm.ui.collectAsStateWithLifecycle()
                    LaunchedEffect(vm) { vm.startActivity.collect { runCatching { context.startActivity(it) } } }
                    LaunchedEffect(Unit) { vm.check() }
                    var installAsked by rememberSaveable { mutableStateOf(false) }
                    LaunchedEffect(Unit) {
                        if (entry.arguments?.getBoolean("install") == true && !installAsked) {
                            installAsked = true
                            vm.installReady()
                        }
                    }
                    LifecycleResumeEffect(vm) {
                        vm.onResume()
                        onPauseOrDispose {}
                    }
                    UpdatesScreen(
                        ui,
                        onBack = goBack,
                        onCheck = vm::check,
                        onInstall = vm::install,
                        onCancelDownload = vm::cancelDownload,
                        onAutoCheck = { vm.setAutoCheck(it) },
                        onTrack = { vm.setTrack(it) },
                    )
                }
                composable(Routes.DIAGNOSTICS) {
                    val vm: DiagnosticsViewModel = koinViewModel()
                    val ui by vm.ui.collectAsStateWithLifecycle()
                    val pickFolder = rememberLogFolderPicker(ui.folder) { vm.export(it) }
                    DiagnosticsScreen(
                        ui,
                        onBack = goBack,
                        onEnabled = { vm.setEnabled(it) },
                        onExport = pickFolder,
                        onDelete = { vm.delete() },
                    )
                }
                composable(Routes.DOC) { entry ->
                    val doc = BundledDoc.fromRoute(entry.arguments?.getString("doc")) ?: BundledDoc.TERMS
                    LegalDocumentScreen(doc, onBack = goBack, onOpenDoc = { navController.navigate(Routes.doc(it)) })
                }
                composable(Routes.ALERTS) {
                    val vm: AlertsViewModel = koinViewModel()
                    val alerts by vm.alerts.collectAsStateWithLifecycle()
                    LaunchedEffect(alerts) { vm.markRead() }
                    AlertsScreen(alerts, onBack = goBack)
                }
                composable(Routes.ECG_DETAIL) { entry ->
                    val id = entry.arguments?.getString("id").orEmpty()
                    val vm: EcgDetailViewModel = koinViewModel(key = id) { parametersOf(id) }
                    val record by vm.state.collectAsStateWithLifecycle()
                    val context = LocalContext.current
                    var editing by remember { mutableStateOf(false) }
                    var confirmDelete by remember { mutableStateOf(false) }
                    record?.let { current ->
                        EcgDetailScreen(
                            current,
                            onBack = goBack,
                            onSharePdf = {
                                share(
                                    ShareRequest(
                                        kind = "ECG",
                                        formats = listOf(
                                            ShareFormat("pdf", "application/pdf", R.string.share_format_pdf) { file, name, profile -> reports.export(current, profile, name, file) },
                                            ShareFormat("png", "image/png", R.string.share_format_image) { file, name, profile -> reports.exportImage(current, profile, name, file) },
                                        ),
                                        text = { prompt, person -> ResultSummary.ecg(context.resources, current, person, prompt) },
                                    ),
                                )
                            },
                            onDelete = { confirmDelete = true },
                            onEditSymptoms = { editing = true },
                        )
                        if (editing) {
                            ModalBottomSheet(onDismissRequest = { editing = false }, containerColor = HeartlineTheme.colors.surface) {
                                SymptomsSheetContent(current.symptoms) {
                                    vm.updateSymptoms(it)
                                    editing = false
                                }
                            }
                        }
                        if (confirmDelete) {
                            DeleteRecordingDialog(
                                onConfirm = {
                                    confirmDelete = false
                                    vm.delete { goBack() }
                                },
                                onDismiss = { confirmDelete = false },
                            )
                        }
                    }
                }
            }
            }
        }
    }
}

/** Offers each Quick Settings tile in turn (the system shows one dialog at a time). False when the phone can't ask. */
private fun addQuickTiles(context: android.content.Context, tiles: List<Class<out HeartlineQsTile>>, onResult: (Boolean) -> Unit): Boolean {
    val tile = tiles.firstOrNull() ?: return true
    val (label, icon) = when (tile) {
        EcgQsTile::class.java -> R.string.qs_name_ecg to R.drawable.ic_metric_ecg
        BpQsTile::class.java -> R.string.qs_name_bp to R.drawable.ic_metric_bp
        else -> R.string.qs_name_measure to R.drawable.ic_metric_spo2
    }
    return HeartlineQsTile.requestAdd(context, tile, context.getString(label), icon) { added ->
        onResult(added)
        addQuickTiles(context, tiles.drop(1), onResult)
    }
}

private fun NavHostController.navigateTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

@Composable
fun OneUiBottomBar(selectedRoute: String, onSelect: (String) -> Unit) {
    val colors = HeartlineTheme.colors
    NavigationBar(containerColor = colors.background, tonalElevation = androidx.compose.ui.unit.Dp(0f)) {
        tabs.forEach { tab ->
            val selected = tab.route == selectedRoute
            NavigationBarItem(
                selected = selected,
                onClick = { onSelect(tab.route) },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(stringResource(tab.label), style = MaterialTheme.typography.labelMedium) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = colors.onBackground,
                    selectedTextColor = colors.onBackground,
                    unselectedIconColor = colors.onSurfaceVariant,
                    unselectedTextColor = colors.onSurfaceVariant,
                    indicatorColor = Color.Transparent,
                ),
            )
        }
    }
}
