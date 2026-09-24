package com.heartline.phone

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test

/** The adaptive launcher icon under the masks it meets: One UI squircle, watch circle, themed. */
class IconScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(screenHeight = 900), maxPercentDifference = 0.1)

    /** Adaptive icons show the middle 72/108 of their layers; scale to mimic the launcher. */
    @Composable
    private fun Icon(size: Dp, shape: Shape, themed: Boolean = false) {
        Box(Modifier.size(size).clip(shape)) {
            val layer = Modifier.fillMaxSize().graphicsLayer(scaleX = 1.5f, scaleY = 1.5f)
            if (themed) {
                Box(Modifier.fillMaxSize().background(Color(0xFFE7E0F4)))
                Image(painterResource(R.drawable.ic_launcher_monochrome), null, layer, colorFilter = ColorFilter.tint(Color(0xFF4A3F6B)))
            } else {
                Image(painterResource(R.drawable.ic_launcher_background), null, layer)
                Image(painterResource(R.drawable.ic_launcher_foreground), null, layer)
            }
        }
    }

    @Composable
    private fun Sheet(background: Color) {
        Column(
            Modifier.background(background).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Icon(120.dp, RoundedCornerShape(36))
                Icon(120.dp, CircleShape)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(56.dp, RoundedCornerShape(36))
                Icon(44.dp, CircleShape)
                Icon(56.dp, RoundedCornerShape(36), themed = true)
            }
        }
    }

    @Test
    fun launcherIcon() = paparazzi.snapshot {
        Column(Modifier.fillMaxSize().background(Color(0xFF1C1C22)), horizontalAlignment = Alignment.CenterHorizontally) {
            Sheet(Color(0xFF1C1C22))
            Sheet(Color(0xFFF4F4F7))
        }
    }
}
