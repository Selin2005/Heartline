// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

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
import com.heartline.phone.ui.body.BodyCompositionScreen
import com.heartline.phone.ui.metric.MetricDetailScreen
import com.heartline.phone.ui.profile.ProfileScreen
import com.heartline.shared.model.Metric
import com.heartline.shared.profile.Gender
import com.heartline.phone.link.WatchLinkUi
import com.heartline.shared.sync.PeerProbe
import com.heartline.shared.profile.UserProfile
import com.heartline.phone.ui.model.BpHomeUi
import com.heartline.phone.ui.model.CalibrationUi
import com.heartline.phone.ui.heart.HeartRateScreen
import com.heartline.phone.ui.model.EcgListState
import com.heartline.phone.ui.model.HeartRateUi
import com.heartline.phone.ui.model.HomeState
import com.heartline.phone.ui.settings.SettingsScreen
import com.heartline.phone.legal.BundledDoc
import com.heartline.phone.ui.legal.LegalDocumentScreen
import com.heartline.phone.ui.legal.TermsScreen
import com.heartline.phone.ui.theme.HeartlineTheme
import androidx.compose.foundation.background
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Every phone screen in light and dark. */
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

    @Test fun ecgDetailRecordingDetails() = shot("ecg_detail_recording") {
        EcgDetailScreen(SampleData.ecgRecords[0], onBack = {}, listState = rememberLazyListState(initialFirstVisibleItemIndex = 3))
    }

    @Test fun homeEmpty() = shot("home_empty") { HomeScreen(HomeState(irregularRhythmNotifications = false)) }

    @Test fun ecgHomeEmpty() = shot("ecg_home_empty") { EcgHomeScreen(EcgListState(loading = false), onBack = {}) }

    @Test fun heartRate() = shot("heart_rate") { HeartRateScreen(SampleData.heartRate, onBack = {}) }

    @Test fun heartRateEmpty() = shot("heart_rate_empty") { HeartRateScreen(HeartRateUi(), onBack = {}) }

    @Test fun alerts() = shot("alerts") { AlertsScreen(SampleData.alerts, onBack = {}) }

    @Test fun bloodPressure() = shot("bp_home") { BpHomeScreen(SampleData.bpHome, onBack = {}) }

    @Test fun bloodPressureAccuracy() = shot("bp_home_accuracy") {
        BpHomeScreen(SampleData.bpHome, onBack = {}, listState = rememberLazyListState(initialFirstVisibleItemIndex = 3))
    }

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

    @Test fun bodyComposition() = shot("body_composition") { BodyCompositionScreen(SampleData.body, onBack = {}, animate = false) }

    @Test fun bodyCompositionMeasures() = shot("body_composition_measures") {
        BodyCompositionScreen(SampleData.body, onBack = {}, animate = false, listState = rememberLazyListState(initialFirstVisibleItemIndex = 3))
    }

    @Test fun bodyCompositionMore() = shot("body_composition_more") {
        BodyCompositionScreen(SampleData.body, onBack = {}, animate = false, listState = rememberLazyListState(initialFirstVisibleItemIndex = 8), advancedOpen = true)
    }

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

    @Test fun profilePreferNotToSay() = shot("profile_prefer_not_to_say") {
        ProfileScreen(
            UserProfile("Alex", "Moradi", "Lex", "1995-01-30", Gender.PREFER_NOT_TO_SAY, heightCm = 172f, weightKg = 66f),
            onBack = {},
            today = profileToday,
            listState = rememberLazyListState(initialFirstVisibleItemIndex = 3)
        )
    }

    @Test fun about() = shot("about") { AboutScreen("0.1.0", onBack = {}) }

    @Test fun settings() = shot("settings") { SettingsScreen(watchConnected = true) }

    @Test fun settingsMonitoring() = shot("settings_monitoring") {
        SettingsScreen(
            com.heartline.shared.hr.MonitorSettings(dailyReminder = true),
            watchConnected = true,
            listState = rememberLazyListState(initialFirstVisibleItemIndex = 3),
        )
    }

    @Test fun settingsSharing() = shot("settings_sharing") {
        SettingsScreen(watchConnected = true, listState = rememberLazyListState(initialFirstVisibleItemIndex = 7))
    }

    @Test fun onboarding() = shot("onboarding") { OnboardingScreen() }

    @Test fun terms() = shot("terms") { TermsScreen(updated = false, initiallyChecked = true) }

    @Test fun legalDocument() = shot("legal") {
        val root = generateSequence(java.io.File("").absoluteFile) { it.parentFile }.first { java.io.File(it, "settings.gradle.kts").exists() }
        LegalDocumentScreen(BundledDoc.TERMS, onBack = {}, text = java.io.File(root, "legal/TERMS_OF_USE.md").readText())
    }

    @Test fun shareSheet() = shot("share_sheet") {
        Box(Modifier.background(HeartlineTheme.colors.surface).padding(top = 24.dp)) {
            com.heartline.phone.ui.share.ShareSheetContent(
                defaultName = "Heartline_ECG_Sara-Karimi_2026-09-24_09-41",
                aiTargets = listOf(
                    com.heartline.phone.share.AiTarget("com.anthropic.claude", "Claude", null, acceptsPdf = true),
                    com.heartline.phone.share.AiTarget("com.openai.chatgpt", "ChatGPT", null, acceptsPdf = true),
                    com.heartline.phone.share.AiTarget("com.google.android.apps.bard", "Gemini", null, acceptsPdf = false),
                    com.heartline.phone.share.AiTarget("ai.x.grok", "Grok", null, acceptsPdf = false),
                ),
                formats = listOf(com.heartline.phone.ui.share.FormatOption("PDF", "pdf"), com.heartline.phone.ui.share.FormatOption("Image", "png")),
                onSave = { _, _ -> },
                onShare = { _, _ -> },
            )
        }
    }

    @Test fun shareSheetNoAi() = shot("share_sheet_no_ai") {
        Box(Modifier.background(HeartlineTheme.colors.surface).padding(top = 24.dp)) {
            com.heartline.phone.ui.share.ShareSheetContent(defaultName = null, aiTargets = emptyList())
        }
    }

    @Test fun hrChartSelected() = shot("hr_chart_selected") {
        Box(Modifier.background(HeartlineTheme.colors.surface).padding(20.dp)) {
            com.heartline.phone.ui.components.RangeBarChart(
                SampleData.heartRate.day,
                slots = 48,
                color = HeartlineTheme.colors.heartRate,
                xLabels = listOf("00", "06", "12", "18", "24"),
                label = { i -> "%02d:%02d".format(i * 30 / 60, i * 30 % 60) },
                resting = 56,
                initiallySelected = 17,
            )
        }
    }

    @Test fun hrChartWeek() = shot("hr_chart_week") {
        Box(Modifier.background(HeartlineTheme.colors.surface).padding(20.dp)) {
            com.heartline.phone.ui.components.RangeBarChart(
                SampleData.heartRate.week,
                slots = 7,
                color = HeartlineTheme.colors.heartRate,
                xLabels = SampleData.heartRate.weekLabels,
                label = { i -> SampleData.heartRate.weekDates[i] },
                resting = 56,
            )
        }
    }

    @Test fun connectWatchFound() = shot("connect_watch_found") {
        com.heartline.phone.ui.onboarding.ConnectWatchScreen(WatchLinkUi(PeerProbe.REACHABLE, "Galaxy Watch8"))
    }

    @Test fun connectWatchMissingApp() = shot("connect_watch_app_missing") {
        com.heartline.phone.ui.onboarding.ConnectWatchScreen(WatchLinkUi(PeerProbe.APP_MISSING))
    }

    @Test fun homeNoWatch() = shot("home_no_watch") { HomeScreen(SampleData.home, watchLink = WatchLinkUi(PeerProbe.NO_DEVICE)) }

    @Test fun devModeHelp() = shot("dev_mode_help") { com.heartline.phone.ui.help.DevModeHelpScreen(onBack = {}) }
}
