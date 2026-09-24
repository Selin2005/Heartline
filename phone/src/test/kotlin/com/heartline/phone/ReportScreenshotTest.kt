package com.heartline.phone

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import com.heartline.phone.report.EcgReportBuilder
import com.heartline.phone.report.EcgReportPainter
import com.heartline.phone.ui.ecg.SymptomsSheetContent
import com.heartline.phone.ui.theme.HeartlineTheme
import com.heartline.shared.ecg.EcgFilter
import com.heartline.shared.report.EcgStripLayout
import org.junit.Rule
import org.junit.Test

/** The PDF page drawn at 2x (A4 landscape) so the printed report can be reviewed visually. */
class ReportScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6.copy(
            screenWidth = EcgStripLayout.PAGE_WIDTH_PT * 2,
            screenHeight = EcgStripLayout.PAGE_HEIGHT_PT * 2,
            density = Density.XHIGH,
            xdpi = 320,
            ydpi = 320,
            orientation = ScreenOrientation.LANDSCAPE,
        ),
        maxPercentDifference = 0.1,
    )

    @Test
    fun ecgReportPage() {
        val record = SampleData.ecgRecords[1].let { it.copy(samples = EcgFilter.clean(it.samples!!, it.sampleRateHz)) }
        val data = EcgReportBuilder(paparazzi.context).data(record)
        paparazzi.snapshot {
            Canvas(Modifier.fillMaxSize()) {
                val scale = size.width / EcgStripLayout.PAGE_WIDTH_PT
                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.save()
                    canvas.nativeCanvas.scale(scale, scale)
                    EcgReportPainter().draw(canvas.nativeCanvas, data)
                    canvas.nativeCanvas.restore()
                }
            }
        }
    }
}

class SymptomsSheetScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6, maxPercentDifference = 0.1)

    @Test
    fun symptomsSheet() = paparazzi.snapshot {
        HeartlineTheme(darkTheme = false) {
            // Rendered as the modal sheet appears: scrim over the screen, sheet at the bottom.
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)), contentAlignment = Alignment.BottomCenter) {
                Surface(color = HeartlineTheme.colors.surface, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
                    Column(Modifier.padding(top = 12.dp)) {
                        SymptomsSheetContent(SampleData.ecgRecords[1].symptoms) {}
                    }
                }
            }
        }
    }
}
