package com.heartline.wear

import androidx.compose.runtime.Composable
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.ScreenRound
import com.heartline.shared.model.EcgResult
import com.heartline.wear.ui.WearSample
import com.heartline.wear.ui.screens.EcgAnalyzingScreen
import com.heartline.wear.ui.screens.EcgInstructionScreen
import com.heartline.wear.ui.screens.EcgMeasuringScreen
import com.heartline.wear.ui.screens.EcgResultScreen
import com.heartline.shared.model.Metric
import com.heartline.shared.bp.BpCategory
import com.heartline.wear.ui.screens.BpCalibrationRecordedScreen
import com.heartline.wear.ui.screens.BpInstructionScreen
import com.heartline.wear.ui.screens.BpNeedsCalibrationScreen
import com.heartline.wear.ui.screens.BpResultScreen
import com.heartline.shared.model.RecordSummary
import com.heartline.wear.sensor.QuickHint
import com.heartline.wear.ui.setup.DevModeGuideScreen
import com.heartline.wear.ui.setup.CheckingScreen
import com.heartline.wear.ui.setup.CheckingSensorsScreen
import com.heartline.wear.ui.setup.PermissionsScreen
import com.heartline.wear.ui.setup.PhoneProblemScreen
import com.heartline.wear.ui.setup.SetupIncompleteScreen
import com.heartline.shared.sync.LinkStage
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnState
import com.heartline.wear.ui.screens.DiagnosticsScreen
import com.heartline.wear.ui.screens.HeartRateScreen
import com.heartline.wear.ui.screens.WatchSettingsScreen
import com.heartline.wear.ui.screens.WatchSettingsUi
import com.heartline.wear.ui.screens.ProfileNeededScreen
import com.heartline.wear.ui.screens.QuickInstructionScreen
import com.heartline.wear.ui.screens.QuickMeasuringScreen
import com.heartline.wear.ui.screens.QuickResultScreen
import com.heartline.wear.ui.screens.MeasuringScreen
import com.heartline.wear.ui.theme.WearColors
import com.heartline.wear.ui.screens.HistoryItem
import com.heartline.wear.ui.screens.HistoryScreen
import com.heartline.wear.ui.screens.LauncherScreen
import com.heartline.wear.ui.screens.SensorErrorScreen
import com.heartline.wear.sensor.SensorProblem
import com.heartline.wear.ui.theme.HeartlineWearTheme
import androidx.wear.compose.material3.AppScaffold
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Watch screens on small (192dp) and large (227dp, Watch8 Classic-class) round screens. */
@RunWith(Parameterized::class)
class WearScreenshotTest(private val size: String) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun params() = listOf("small", "large")

        private val LARGE_ROUND = DeviceConfig.WEAR_OS_SMALL_ROUND.copy(
            screenHeight = 454,
            screenWidth = 454,
            xdpi = 320,
            ydpi = 320,
            density = com.android.resources.Density.XHIGH,
            screenRound = ScreenRound.ROUND,
        )
    }

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = if (size == "small") DeviceConfig.WEAR_OS_SMALL_ROUND else LARGE_ROUND,
        maxPercentDifference = 0.1,
    )

    private fun shot(name: String, content: @Composable () -> Unit) {
        paparazzi.snapshot(name = name) {
            HeartlineWearTheme { AppScaffold(timeText = {}) { content() } }
        }
    }

    @Test fun launcher() = shot("launcher") { LauncherScreen(WearSample.launcher) }

    @Test fun ecgInstruction() = shot("ecg_instruction") { EcgInstructionScreen() }

    @Test fun ecgMeasuring() = shot("ecg_measuring") {
        EcgMeasuringScreen(0.4f, 18, WearSample.liveEcg, leadOff = false, bpm = 72, endIndex = 1500L * 3 + 900)
    }

    @Test fun ecgWaitingForTouch() = shot("ecg_waiting") { EcgMeasuringScreen(0f, 30, FloatArray(0), leadOff = true, waitingForTouch = true) }

    @Test fun ecgLeadOff() = shot("ecg_lead_off") { EcgMeasuringScreen(0.55f, 14, WearSample.liveEcg.copyOf(900), leadOff = true) }

    @Test fun ecgAnalyzing() = shot("ecg_analyzing") { EcgAnalyzingScreen() }

    @Test fun ecgArming() = shot("ecg_arming") { EcgMeasuringScreen(0f, 30, WearSample.liveEcg.copyOf(900), leadOff = true, arming = true) }

    @Test fun ecgStruggling() = shot("ecg_struggling") { EcgMeasuringScreen(0f, 30, WearSample.liveEcg.copyOf(900), leadOff = true, arming = true, struggling = true) }

    @Test fun ecgResultInconclusiveWithNote() = shot("ecg_result_inconclusive_note") {
        EcgResultScreen(
            EcgResult.INCONCLUSIVE,
            78,
            com.heartline.shared.model.EcgMetrics(
                0, 31_000, 30f, 29f, 1f, 0f, 1f, 0f, 78, 70, 84, 38, 770, 60, 70, 92,
                com.heartline.shared.model.EcgPoorReason.NONE, sampleRateHz = 500f, ectopicBeats = 9, ventricularLikeBeats = 9,
                note = com.heartline.shared.model.EcgNote.FREQUENT_EXTRA_BEATS,
            ),
        )
    }

    @Test fun ecgResultSinus() = shot("ecg_result_sinus") { EcgResultScreen(EcgResult.SINUS_RHYTHM, 72) }

    @Test fun ecgResultAfib() = shot("ecg_result_afib") { EcgResultScreen(EcgResult.AFIB_SIGNS, 94) }

    @Test fun ecgResultPoorWithReason() = shot("ecg_result_poor") {
        EcgResultScreen(
            EcgResult.POOR_RECORDING,
            null,
            com.heartline.shared.model.EcgMetrics(
                0, 31_000, 30f, 9f, 21f, 4f, 17f, 1f, null, null, null, 31, null, null, null, 34,
                com.heartline.shared.model.EcgPoorReason.MUSCLE_NOISE, sampleRateHz = 500f,
            ),
        )
    }

    @Test fun history() = shot("history") {
        HistoryScreen(
            listOf(
                HistoryItem("1", Metric.ECG, EcgResult.SINUS_RHYTHM, "72 bpm", "5 min. ago", synced = false),
                HistoryItem("2", Metric.BLOOD_PRESSURE, null, "118/76", "2 hr. ago", synced = true),
                HistoryItem("3", Metric.ECG, EcgResult.AFIB_SIGNS, "94 bpm", "Sep 21", synced = true),
            ),
        )
    }

    @Test fun heartRate() = shot("heart_rate") {
        HeartRateScreen(66, onBody = true, animate = false)
    }

    @Test fun heartRateOffBody() = shot("heart_rate_off_body") { HeartRateScreen(null, onBody = false) }

    @Test fun bpInstruction() = shot("bp_instruction") { BpInstructionScreen() }

    @Test fun bpCalibrationRound() = shot("bp_calibration_round") { BpInstructionScreen(calibrationRound = 2) }

    @Test fun bpMeasuring() = shot("bp_measuring") {
        MeasuringScreen("Blood pressure", WearColors.metric(Metric.BLOOD_PRESSURE), 0.35f, 13, WearSample.livePpg, "Keep still and don't talk", false, null)
    }

    @Test fun bpResult() = shot("bp_result") { BpResultScreen(118, 76, 66, BpCategory.NORMAL, uncertainty = 7) }

    @Test fun bpOutOfRange() = shot("bp_out_of_range") { com.heartline.wear.ui.screens.BpOutOfRangeScreen() }

    @Test fun bpMoving() = shot("bp_moving") { com.heartline.wear.ui.screens.BpOutOfRangeScreen(moving = true) }

    @Test fun bpResultBeyondCalibration() = shot("bp_result_beyond") {
        BpResultScreen(158, 96, 84, BpCategory.HIGH_STAGE_2, uncertainty = 13, beyondCalibration = true)
    }

    @Test fun bpResultVeryHigh() = shot("bp_result_very_high") {
        BpResultScreen(186, 112, 90, BpCategory.CRISIS, uncertainty = 15, beyondCalibration = true, confirmed = true, safety = com.heartline.shared.bp.BpSafety.VERY_HIGH)
    }

    @Test fun bpNeedsCalibration() = shot("bp_needs_calibration") { BpNeedsCalibrationScreen() }

    @Test fun bpNeedsCalibrationOpened() = shot("bp_needs_calibration_opened") { BpNeedsCalibrationScreen(opened = true) }

    @Test fun bpCalibrationRecorded() = shot("bp_calibration_recorded") { BpCalibrationRecordedScreen(2) }

    @Test fun spo2Instruction() = shot("spo2_instruction") { QuickInstructionScreen(Metric.SPO2) }

    @Test fun bodyInstruction() = shot("body_instruction") { QuickInstructionScreen(Metric.BODY_COMPOSITION) }

    @Test fun spo2Measuring() = shot("spo2_measuring") { QuickMeasuringScreen(Metric.SPO2, 0.45f, 17, QuickHint.HOLD_STILL, animate = false) }

    @Test fun spo2MeasuringLive() = shot("spo2_measuring_live") { QuickMeasuringScreen(Metric.SPO2, 0.6f, 12, null, bpm = 71, animate = false) }

    @Test fun stressMeasuring() = shot("stress_measuring") { QuickMeasuringScreen(Metric.STRESS, 0.4f, 36, null, bpm = 68, hrvMs = 42.0, animate = false) }

    @Test fun temperatureMeasuring() = shot("temperature_measuring") { QuickMeasuringScreen(Metric.SKIN_TEMPERATURE, 0.5f, 5, null, animate = false) }

    @Test fun bodyMeasuring() = shot("body_measuring") { QuickMeasuringScreen(Metric.BODY_COMPOSITION, 0.3f, 11, null, animate = false) }

    @Test fun bodyTopKey() = shot("body_top_key") { QuickMeasuringScreen(Metric.BODY_COMPOSITION, 0f, 15, QuickHint.TOP_KEY, animate = false) }

    @Test fun bpMeasuringLive() = shot("bp_measuring_live") {
        val ppg = WearSample.livePpg
        com.heartline.wear.ui.screens.BpMeasuringScreen(0.45f, 11, ppg.copyOfRange(0, 300), contact = true, bpm = 68, endIndex = 300L * 4 + 180, animate = false)
    }

    @Test fun spo2Result() = shot("spo2_result") { QuickResultScreen(Metric.SPO2, RecordSummary.Spo2(97, 64, false)) }

    @Test fun tempResult() = shot("temp_result") { QuickResultScreen(Metric.SKIN_TEMPERATURE, RecordSummary.SkinTemperature(33.6f, 24.5f)) }

    @Test fun bodyResult() = shot("body_result") { QuickResultScreen(Metric.BODY_COMPOSITION, RecordSummary.BodyComposition(21.4f, 29.8f, 38.6f, 1540)) }

    @Test fun stressResult() = shot("stress_result") { QuickResultScreen(Metric.STRESS, RecordSummary.Stress(38, 34.0)) }

    @Test fun profileNeeded() = shot("profile_needed") { ProfileNeededScreen() }

    private val settingsUi = WatchSettingsUi(true, true, "1.6.5.021", listOf("ECG_ON_DEMAND", "HEART_RATE_CONTINUOUS", "PPG_ON_DEMAND", "SPO2_ON_DEMAND"), "0.1.0")

    @Test fun settings() = shot("settings") { WatchSettingsScreen(settingsUi) }

    @Test fun settingsToggles() = shot("settings_toggles") {
        WatchSettingsScreen(settingsUi.copy(heartRateAlerts = false), listState = TransformingLazyColumnState(initialAnchorItemIndex = 3))
    }

    @Test fun diagnostics() = shot("diagnostics") { DiagnosticsScreen(settingsUi) }

    @Test fun devMode() = shot("dev_mode") { DevModeGuideScreen() }

    @Test fun devModeEnd() = shot("dev_mode_end") { DevModeGuideScreen(state = TransformingLazyColumnState(initialAnchorItemIndex = 9)) }

    @Test fun setupCheckingPhone() = shot("setup_checking_phone") {
        CheckingScreen(Icons.Rounded.PhoneAndroid, "Connecting to phone", "Making sure Heartline on your phone and watch know each other.", animate = false)
    }

    @Test fun setupNoPhone() = shot("setup_no_phone") { PhoneProblemScreen(LinkStage.NO_DEVICE) }

    @Test fun setupAppMissing() = shot("setup_app_missing") { PhoneProblemScreen(LinkStage.APP_MISSING) }

    @Test fun setupNoResponse() = shot("setup_no_response") { PhoneProblemScreen(LinkStage.NO_RESPONSE, opened = true) }

    @Test fun setupIncomplete() = shot("setup_incomplete") { SetupIncompleteScreen("Sara") }

    @Test fun setupPermissions() = shot("setup_permissions") { PermissionsScreen() }

    @Test fun setupCheckingSensors() = shot("setup_checking_sensors") { CheckingSensorsScreen(animate = false) }

    @Test fun errorPolicy() = shot("error_sdk_policy") { SensorErrorScreen(SensorProblem.SDK_POLICY) }

    @Test fun errorNotSupported() = shot("error_not_supported") { SensorErrorScreen(SensorProblem.NOT_SUPPORTED) }
}
