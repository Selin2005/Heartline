// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.cash.paparazzi.Snapshot
import app.cash.paparazzi.SnapshotHandler
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import com.heartline.phone.ui.theme.Inter
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Renders the app icon and the promotional images in docs/brand from the launcher icon's own
 * vector layers and the published screenshots, at exact pixel sizes (1 dp = 1 px).
 * Run with HEARTLINE_BRAND_ASSETS=1 ./gradlew :phone:testDebugUnitTest --tests '*BrandAssets*',
 * then python3 tools/screenshots/sync.py to optimise the PNGs.
 */
class BrandAssetsTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
    private val out = File(root, "docs/brand")

    /** Keeps the rendered frame (Paparazzi's own goldens are scaled to at most 1000 px). */
    private class Capture : SnapshotHandler {
        var image: BufferedImage? = null

        override fun newFrameHandler(snapshot: Snapshot, frameCount: Int, fps: Int) = object : SnapshotHandler.FrameHandler {
            override fun handle(image: BufferedImage) {
                this@Capture.image = image
            }

            override fun close() = Unit
        }

        override fun close() = Unit
    }

    private fun paparazzi(width: Int, height: Int, capture: Capture) = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6.copy(
            screenWidth = width,
            screenHeight = height,
            density = Density.MEDIUM,
            xdpi = 160,
            ydpi = 160,
            orientation = if (width > height) ScreenOrientation.LANDSCAPE else ScreenOrientation.PORTRAIT,
        ),
        theme = "android:Theme.Translucent.NoTitleBar",
        snapshotHandler = capture,
    )

    /**
     * Renders [content] at [width] × [height] px into docs/brand/[file].png. Paparazzi scales frames
     * above 1000 px down, so larger images are rendered in tiles and put together.
     */
    private fun render(file: String, width: Int, height: Int, content: @Composable () -> Unit) {
        assumeTrue(System.getenv("HEARTLINE_BRAND_ASSETS") != null)
        val result = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val tile = 640
        for (y in 0 until height step tile) {
            for (x in 0 until width step tile) {
                val w = minOf(tile, width - x)
                val h = minOf(tile, height - y)
                val capture = Capture()
                val p = paparazzi(w, h, capture)
                val body = object : Statement() {
                    override fun evaluate() = p.snapshot {
                        Box(Modifier.fillMaxSize().wrapContentSize(Alignment.TopStart, unbounded = true)) {
                            Box(Modifier.requiredSize(width.dp, height.dp).offset((-x).dp, (-y).dp)) { content() }
                        }
                    }
                }
                p.apply(body, Description.createTestDescription(javaClass, "$file-$x-$y")).evaluate()
                val image = checkNotNull(capture.image)
                check(image.width == w && image.height == h) { "tile ${image.width}x${image.height}, expected ${w}x$h" }
                result.createGraphics().apply { drawImage(image, x, y, null) }.dispose()
            }
        }
        out.mkdirs()
        ImageIO.write(result, "png", File(out, "$file.png"))
    }

    /** The adaptive icon's layers; the launcher shows their middle 72/108. */
    @Composable
    private fun AppIcon(size: Dp, shape: Shape?) {
        val clip = if (shape != null) Modifier.clip(shape) else Modifier
        Box(Modifier.size(size).then(clip)) {
            val layer = Modifier.fillMaxSize().graphicsLayer(scaleX = 1.5f, scaleY = 1.5f)
            Image(painterResource(R.drawable.ic_launcher_background), null, layer)
            Image(painterResource(R.drawable.ic_launcher_foreground), null, layer)
        }
    }

    /** A published screenshot (layoutlib's BitmapFactory can't read files, so through ImageIO). */
    private fun screenshot(path: String): ImageBitmap {
        val image = ImageIO.read(File(root, path))
        val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        return Bitmap.createBitmap(pixels, image.width, image.height, Bitmap.Config.ARGB_8888).asImageBitmap()
    }

    /** Name, one line about the app, and the phone and watch side by side. [s] scales a 1280 × 640 layout. */
    @Composable
    private fun Poster(s: Float) {
        val d = { v: Int -> (v * s).dp }
        val t = { v: Int -> (v * s).sp }
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.linearGradient(listOf(Color(0xFF121217), Color(0xFF1C1C22), Color(0xFF26151B)), Offset.Zero, Offset.Infinite)),
        ) {
            // Rose glow behind the devices.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            listOf(Color(0x66F5405C), Color(0x22E0223F), Color.Transparent),
                            center = Offset(930f * s, 330f * s),
                            radius = 460f * s,
                        ),
                    ),
            )
            Row(Modifier.fillMaxSize().padding(start = d(84), end = d(56)), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(d(560))) {
                    AppIcon(d(120), RoundedCornerShape(36))
                    Spacer(Modifier.height(d(32)))
                    Text("Heartline", color = Color.White, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = t(88), letterSpacing = t(-2))
                    Spacer(Modifier.height(d(8)))
                    Text(
                        "Heart health on your Galaxy Watch",
                        color = Color(0xFFF4F4F7),
                        fontFamily = Inter,
                        fontWeight = FontWeight.Medium,
                        fontSize = t(32),
                        lineHeight = t(40),
                    )
                    Spacer(Modifier.height(d(20)))
                    Text(
                        "ECG · blood pressure estimates · rhythm checks\nwith your history on your phone",
                        color = Color(0xFFB4B4C2),
                        fontFamily = Inter,
                        fontSize = t(21),
                        lineHeight = t(31),
                    )
                    Spacer(Modifier.height(d(32)))
                    Row(horizontalArrangement = Arrangement.spacedBy(d(10))) {
                        listOf("Galaxy Watch4+", "Android", "Open source").forEach { Chip(it, s) }
                    }
                }
                Box(Modifier.fillMaxSize()) {
                    // Phone.
                    Box(
                        Modifier
                            .align(Alignment.CenterEnd)
                            .offset(x = d(-40))
                            .size(d(256), d(560))
                            .clip(RoundedCornerShape(d(34)))
                            .background(Color(0xFF0B0B0E))
                            .border(d(6), Color(0xFF34343E), RoundedCornerShape(d(34)))
                            .padding(d(6)),
                    ) {
                        Image(
                            screenshot("docs/screenshots/phone/home/home_dark.png"),
                            null,
                            Modifier.fillMaxSize().clip(RoundedCornerShape(d(28))),
                            contentScale = ContentScale.Crop,
                            alignment = Alignment.TopCenter,
                        )
                    }
                    // Watch, in front of the phone.
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .offset(x = d(-10), y = d(-44))
                            .size(d(250))
                            .clip(CircleShape)
                            .background(Color(0xFF2A2A31))
                            .border(d(12), Brush.linearGradient(listOf(Color(0xFF55555F), Color(0xFF1E1E24))), CircleShape)
                            .padding(d(14)),
                    ) {
                        Image(
                            screenshot("docs/screenshots/wear/ecg/ecg_result_sinus_large.png"),
                            null,
                            Modifier.fillMaxSize().clip(CircleShape).background(Color.Black),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun Chip(text: String, s: Float) {
        Text(
            text,
            color = Color(0xFFFFD6DC),
            fontFamily = Inter,
            fontWeight = FontWeight.Medium,
            fontSize = (16 * s).sp,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Color(0x33F5405C))
                .border((1 * s).dp, Color(0x66F5405C), RoundedCornerShape(50))
                .padding(horizontal = (14 * s).dp, vertical = (7 * s).dp),
        )
    }

    /** Google Play store icon: full square, Play applies the mask. */
    @Test
    fun icon512() = render("heartline-icon-512", 512, 512) { AppIcon(512.dp, null) }

    @Test
    fun icon1024() = render("heartline-icon-1024", 1024, 1024) { AppIcon(1024.dp, null) }

    /** Rounded like on a phone, transparent corners, for web pages and documents. */
    @Test
    fun iconRounded() = render("heartline-icon-rounded", 512, 512) { AppIcon(512.dp, RoundedCornerShape(36)) }

    /** README header and GitHub social preview (1280 × 640). */
    @Test
    fun poster() = render("heartline-poster", 1280, 640) { Poster(1f) }

    /** Google Play feature graphic (1024 × 500). */
    @Test
    fun featureGraphic() = render("play-feature-graphic", 1024, 500) { Poster(0.8f) }
}
