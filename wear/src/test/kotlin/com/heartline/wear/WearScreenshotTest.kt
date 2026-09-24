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
import com.heartline.wear.ui.screens.DevModeGuideScreen
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

    @Test fun ecgMeasuring() = shot("ecg_measuring") { EcgMeasuringScreen(0.4f, 18, WearSample.liveEcg, leadOff = false) }

    @Test fun ecgLeadOff() = shot("ecg_lead_off") { EcgMeasuringScreen(0.55f, 14, WearSample.liveEcg.copyOf(900), leadOff = true) }

    @Test fun ecgAnalyzing() = shot("ecg_analyzing") { EcgAnalyzingScreen() }

    @Test fun ecgResultSinus() = shot("ecg_result_sinus") { EcgResultScreen(EcgResult.SINUS_RHYTHM, 72) }

    @Test fun ecgResultAfib() = shot("ecg_result_afib") { EcgResultScreen(EcgResult.AFIB_SIGNS, 94) }

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
        HeartRateScreen(66, listOf(64, 65, 66, 68, 70, 69, 67, 66, 65, 64, 66, 67, 69, 71, 70, 68, 66, 65, 66, 67), onBody = true)
    }

    @Test fun heartRateOffBody() = shot("heart_rate_off_body") { HeartRateScreen(null, emptyList(), onBody = false) }

    @Test fun bpInstruction() = shot("bp_instruction") { BpInstructionScreen() }

    @Test fun bpCalibrationRound() = shot("bp_calibration_round") { BpInstructionScreen(calibrationRound = 2) }

    @Test fun bpMeasuring() = shot("bp_measuring") {
        MeasuringScreen("Blood pressure", WearColors.metric(Metric.BLOOD_PRESSURE), 0.35f, 13, WearSample.livePpg, "Keep still and don't talk", false, null)
    }

    @Test fun bpResult() = shot("bp_result") { BpResultScreen(118, 76, 66, BpCategory.NORMAL) }

    @Test fun bpNeedsCalibration() = shot("bp_needs_calibration") { BpNeedsCalibrationScreen() }

    @Test fun bpCalibrationRecorded() = shot("bp_calibration_recorded") { BpCalibrationRecordedScreen(2) }

    @Test fun spo2Instruction() = shot("spo2_instruction") { QuickInstructionScreen(Metric.SPO2) }

    @Test fun bodyInstruction() = shot("body_instruction") { QuickInstructionScreen(Metric.BODY_COMPOSITION) }

    @Test fun spo2Measuring() = shot("spo2_measuring") { QuickMeasuringScreen(Metric.SPO2, 0.45f, 17, QuickHint.HOLD_STILL) }

    @Test fun spo2Result() = shot("spo2_result") { QuickResultScreen(Metric.SPO2, RecordSummary.Spo2(97, 64, false)) }

    @Test fun tempResult() = shot("temp_result") { QuickResultScreen(Metric.SKIN_TEMPERATURE, RecordSummary.SkinTemperature(33.6f, 24.5f)) }

    @Test fun bodyResult() = shot("body_result") { QuickResultScreen(Metric.BODY_COMPOSITION, RecordSummary.BodyComposition(21.4f, 29.8f, 38.6f, 1540)) }

    @Test fun stressResult() = shot("stress_result") { QuickResultScreen(Metric.STRESS, RecordSummary.Stress(38, 34.0)) }

    @Test fun profileNeeded() = shot("profile_needed") { ProfileNeededScreen() }

    private val settingsUi = WatchSettingsUi(true, true, "1.6.5.021", listOf("ECG_ON_DEMAND", "HEART_RATE_CONTINUOUS", "PPG_ON_DEMAND", "SPO2_ON_DEMAND"), "0.1.0")

    @Test fun settings() = shot("settings") { WatchSettingsScreen(settingsUi) }

    @Test fun diagnostics() = shot("diagnostics") { DiagnosticsScreen(settingsUi) }

    @Test fun devMode() = shot("dev_mode") { DevModeGuideScreen() }

    @Test fun errorPolicy() = shot("error_sdk_policy") { SensorErrorScreen(SensorProblem.SDK_POLICY) }

    @Test fun errorNotSupported() = shot("error_not_supported") { SensorErrorScreen(SensorProblem.NOT_SUPPORTED) }
}
