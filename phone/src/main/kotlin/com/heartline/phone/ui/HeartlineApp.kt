package com.heartline.phone.ui

import androidx.compose.foundation.layout.Box
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
import com.heartline.phone.ui.heart.AlertsScreen
import com.heartline.phone.ui.model.OpenOnWatchViewModel
import org.koin.compose.koinInject
import com.heartline.phone.ui.metric.MetricDetailScreen
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
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.heartline.phone.R
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
    const val ECG = "ecg"
    const val ECG_DETAIL = "ecg/{id}"
    const val HEART_RATE = "heart_rate"
    const val ALERTS = "alerts"
    const val BLOOD_PRESSURE = "blood_pressure"
    const val BP_CALIBRATION = "blood_pressure/calibration"
    const val METRIC = "metric/{metric}"
    const val PROFILE = "profile"
    const val ABOUT = "about"

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
fun HeartlineApp(navController: NavHostController = rememberNavController(), openAlerts: Boolean = false) {
    LaunchedEffect(openAlerts) { if (openAlerts) navController.navigate(Routes.ALERTS) }
    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route ?: Routes.HOME
    Scaffold(
        containerColor = HeartlineTheme.colors.background,
        bottomBar = { if (tabs.any { it.route == route }) OneUiBottomBar(route) { navController.navigateTab(it) } },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
            NavHost(navController, startDestination = Routes.HOME) {
                composable(Routes.HOME) {
                    val vm: HomeViewModel = koinViewModel()
                    val state by vm.state.collectAsStateWithLifecycle()
                    HomeScreen(
                        state,
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
                    SettingsScreen(
                        monitor,
                        onIrregularRhythm = { vm.setIrregularRhythm(it) },
                        onHeartRateAlerts = { vm.setHeartRateAlerts(it) },
                        onDeleteAll = { vm.deleteAll() },
                        onProfile = { navController.navigate(Routes.PROFILE) },
                        onExport = {
                            vm.export(exporter) { context.startActivity(Intent.createChooser(it, context.getString(R.string.settings_export))) }
                        },
                        onAbout = { navController.navigate(Routes.ABOUT) },
                    )
                }
                composable(Routes.ECG) {
                    val vm: EcgListViewModel = koinViewModel()
                    val open: OpenOnWatchViewModel = koinViewModel()
                    val state by vm.state.collectAsStateWithLifecycle()
                    EcgHomeScreen(
                        state,
                        onRecordOnWatch = { open.open("ecg") },
                        onBack = { navController.popBackStack() },
                        onOpenRecord = { navController.navigate(Routes.ecgDetail(it)) },
                        onViewAll = { navController.navigateTab(Routes.HISTORY) },
                    )
                }
                composable(Routes.HEART_RATE) {
                    val vm: HeartRateViewModel = koinViewModel()
                    val state by vm.state.collectAsStateWithLifecycle()
                    HeartRateScreen(state, onBack = { navController.popBackStack() }, onOpenAlerts = { navController.navigate(Routes.ALERTS) })
                }
                composable(Routes.BLOOD_PRESSURE) {
                    val vm: BpHomeViewModel = koinViewModel()
                    val state by vm.state.collectAsStateWithLifecycle()
                    BpHomeScreen(state, onBack = { navController.popBackStack() }, onCalibrate = { navController.navigate(Routes.BP_CALIBRATION) })
                }
                composable(Routes.BP_CALIBRATION) {
                    val vm: CalibrationViewModel = koinViewModel()
                    val state by vm.state.collectAsStateWithLifecycle()
                    BpCalibrationScreen(
                        state,
                        onBack = { navController.popBackStack() },
                        onStart = { vm.startRound() },
                        onSubmit = { s, d, p -> vm.submitCuff(s, d, p) },
                        onDone = { navController.popBackStack() },
                    )
                }
                composable(Routes.METRIC) { entry ->
                    val metric = Metric.valueOf(entry.arguments?.getString("metric") ?: Metric.SPO2.name)
                    val vm: MetricDetailViewModel = koinViewModel(key = metric.name) { parametersOf(metric) }
                    val state by vm.state.collectAsStateWithLifecycle()
                    MetricDetailScreen(state, onBack = { navController.popBackStack() })
                }
                composable(Routes.PROFILE) {
                    val vm: ProfileViewModel = koinViewModel()
                    val profile by vm.profile.collectAsStateWithLifecycle()
                    ProfileScreen(profile, onBack = { navController.popBackStack() }, onSave = { vm.save(it) { navController.popBackStack() } })
                }
                composable(Routes.ABOUT) {
                    AboutScreen(BuildConfig.VERSION_NAME, onBack = { navController.popBackStack() })
                }
                composable(Routes.ALERTS) {
                    val vm: AlertsViewModel = koinViewModel()
                    val alerts by vm.alerts.collectAsStateWithLifecycle()
                    LaunchedEffect(alerts) { vm.markRead() }
                    AlertsScreen(alerts, onBack = { navController.popBackStack() })
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
                            onBack = { navController.popBackStack() },
                            onSharePdf = {
                                vm.sharePdf { intent ->
                                    context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_via)))
                                }
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
                                    vm.delete { navController.popBackStack() }
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
