package com.heartline.phone

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import com.heartline.phone.ui.OneUiBottomBar
import com.heartline.phone.ui.Routes
import com.heartline.phone.ui.ecg.EcgDetailScreen
import com.heartline.phone.ui.ecg.EcgHistoryScreen
import com.heartline.phone.ui.ecg.EcgHomeScreen
import com.heartline.phone.ui.home.HomeScreen
import com.heartline.phone.ui.onboarding.OnboardingScreen
import com.heartline.phone.ui.bp.BpCalibrationScreen
import com.heartline.phone.ui.bp.BpHomeScreen
import com.heartline.phone.ui.about.AboutScreen
import com.heartline.phone.ui.heart.AlertsScreen
import com.heartline.phone.ui.metric.MetricDetailScreen
import com.heartline.phone.ui.profile.ProfileScreen
import com.heartline.shared.model.Metric
import com.heartline.shared.profile.Gender
import com.heartline.phone.link.WatchLinkUi
import com.heartline.shared.sync.PeerProbe
import com.heartline.shared.profile.ReportName
import com.heartline.shared.profile.UserProfile
import com.heartline.phone.ui.model.BpHomeUi
import com.heartline.phone.ui.model.CalibrationUi
import com.heartline.phone.ui.heart.HeartRateScreen
import com.heartline.phone.ui.model.EcgListState
import com.heartline.phone.ui.model.HeartRateUi
import com.heartline.phone.ui.model.HomeState
import com.heartline.phone.ui.settings.SettingsScreen
import com.heartline.phone.ui.theme.HeartlineTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Every phone screen in light and dark (MASTER_PLAN §7). */
@RunWith(Parameterized::class)
class PhoneScreenshotTest(private val theme: String) {
    private val dark = theme == "dark"

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun params() = listOf("light", "dark")
    }

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6.copy(nightMode = if (dark) NightMode.NIGHT else NightMode.NOTNIGHT),
        maxPercentDifference = 0.1,
    )

    private fun shot(name: String, content: @Composable () -> Unit) {
        paparazzi.snapshot(name = name) {
            HeartlineTheme(darkTheme = dark) { content() }
        }
    }

    @Test fun home() = shot("home") {
        Scaffold(bottomBar = { OneUiBottomBar(Routes.HOME) {} }) { p ->
            Box(Modifier.padding(p)) { HomeScreen(SampleData.home) }
        }
    }

    @Test fun homeScrolled() = shot("home_scrolled") {
        HomeScreen(SampleData.home, listState = rememberLazyListState(initialFirstVisibleItemIndex = 3))
    }

    @Test fun ecgHome() = shot("ecg_home") { EcgHomeScreen(SampleData.ecgList, onBack = {}) }

    @Test fun ecgHistory() = shot("ecg_history") { EcgHistoryScreen(SampleData.ecgList, onBack = {}) }

    @Test fun ecgDetailSinus() = shot("ecg_detail_sinus") { EcgDetailScreen(SampleData.ecgRecords[0], onBack = {}) }

    @Test fun ecgDetailAfib() = shot("ecg_detail_afib") { EcgDetailScreen(SampleData.ecgRecords[1], onBack = {}) }

    @Test fun homeEmpty() = shot("home_empty") { HomeScreen(HomeState(irregularRhythmNotifications = false)) }

    @Test fun ecgHomeEmpty() = shot("ecg_home_empty") { EcgHomeScreen(EcgListState(loading = false), onBack = {}) }

    @Test fun heartRate() = shot("heart_rate") { HeartRateScreen(SampleData.heartRate, onBack = {}) }

    @Test fun heartRateEmpty() = shot("heart_rate_empty") { HeartRateScreen(HeartRateUi(), onBack = {}) }

    @Test fun alerts() = shot("alerts") { AlertsScreen(SampleData.alerts, onBack = {}) }

    @Test fun bloodPressure() = shot("bp_home") { BpHomeScreen(SampleData.bpHome, onBack = {}) }

    @Test fun bloodPressureUncalibrated() = shot("bp_home_uncalibrated") { BpHomeScreen(BpHomeUi(), onBack = {}) }

    @Test fun calibrationIntro() = shot("bp_calibration_intro") { BpCalibrationScreen(CalibrationUi(), onBack = {}) }

    @Test fun calibrationWaiting() = shot("bp_calibration_waiting") {
        BpCalibrationScreen(CalibrationUi(round = 2, phase = CalibrationUi.Phase.WAITING_FOR_WATCH, completedRounds = 1), onBack = {})
    }

    @Test fun calibrationCuff() = shot("bp_calibration_cuff") {
        BpCalibrationScreen(CalibrationUi(round = 2, phase = CalibrationUi.Phase.ENTER_CUFF, completedRounds = 1, inputError = true), onBack = {})
    }

    @Test fun calibrationDone() = shot("bp_calibration_done") {
        BpCalibrationScreen(CalibrationUi(round = 3, phase = CalibrationUi.Phase.DONE, completedRounds = 3), onBack = {})
    }

    @Test fun spo2() = shot("spo2") { MetricDetailScreen(SampleData.metricDetail(Metric.SPO2), onBack = {}) }

    @Test fun skinTemperature() = shot("skin_temperature") { MetricDetailScreen(SampleData.metricDetail(Metric.SKIN_TEMPERATURE), onBack = {}) }

    @Test fun bodyComposition() = shot("body_composition") { MetricDetailScreen(SampleData.metricDetail(Metric.BODY_COMPOSITION), onBack = {}) }

    @Test fun stress() = shot("stress") { MetricDetailScreen(SampleData.metricDetail(Metric.STRESS), onBack = {}) }

    private val profileToday = java.time.LocalDate.of(2026, 9, 24)

    @Test fun profile() = shot("profile") {
        ProfileScreen(
            UserProfile("Sara", "Karimi", "Sara", "1990-06-12", Gender.WOMAN, heightCm = 168f, weightKg = 61.5f),
            onBack = {},
            today = profileToday
        )
    }

    @Test fun profileErrors() = shot("profile_errors") {
        ProfileScreen(
            UserProfile(firstName = "Sam", heightCm = 34f),
            onBack = {},
            today = profileToday,
            initialShowErrors = true,
            listState = rememberLazyListState(initialFirstVisibleItemIndex = 1)
        )
    }

    @Test fun profileNonBinary() = shot("profile_non_binary") {
        ProfileScreen(
            UserProfile("Alex", "Moradi", "Lex", "1995-01-30", Gender.NON_BINARY, heightCm = 172f, weightKg = 66f, reportName = ReportName.PREFERRED_NAME),
            onBack = {},
            today = profileToday,
            listState = rememberLazyListState(initialFirstVisibleItemIndex = 3)
        )
    }

    @Test fun about() = shot("about") { AboutScreen("0.1.0", onBack = {}) }

    @Test fun settings() = shot("settings") { SettingsScreen() }

    @Test fun onboarding() = shot("onboarding") { OnboardingScreen() }

    @Test fun connectWatchFound() = shot("connect_watch_found") {
        com.heartline.phone.ui.onboarding.ConnectWatchScreen(WatchLinkUi(PeerProbe.REACHABLE, "Galaxy Watch8"))
    }

    @Test fun connectWatchMissingApp() = shot("connect_watch_app_missing") {
        com.heartline.phone.ui.onboarding.ConnectWatchScreen(WatchLinkUi(PeerProbe.APP_MISSING))
    }

    @Test fun homeNoWatch() = shot("home_no_watch") { HomeScreen(SampleData.home, watchLink = WatchLinkUi(PeerProbe.NO_DEVICE)) }

    @Test fun devModeHelp() = shot("dev_mode_help") { com.heartline.phone.ui.help.DevModeHelpScreen(onBack = {}) }
}
