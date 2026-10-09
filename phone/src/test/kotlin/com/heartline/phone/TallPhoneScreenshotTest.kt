// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.heartline.phone.ui.OneUiBottomBar
import com.heartline.phone.ui.Routes
import com.heartline.phone.ui.home.HomeScreen
import com.heartline.phone.ui.profile.ProfileScreen
import com.heartline.phone.ui.settings.SettingsScreen
import com.heartline.phone.ui.theme.HeartlineTheme
import com.heartline.shared.profile.Gender
import com.heartline.shared.profile.UserProfile
import org.junit.Rule
import org.junit.Test

/** A tall 20:9 phone (1200 × 2608 px), where the reachability header must not grow without bound. */
class TallPhoneScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6.copy(screenWidth = 1200, screenHeight = 2608, density = Density.XXHIGH, xdpi = 480, ydpi = 480),
        maxPercentDifference = 0.1,
    )

    private fun shot(content: @Composable () -> Unit) {
        paparazzi.snapshot { HeartlineTheme(darkTheme = false) { content() } }
    }

    @Test fun tallSettings() = shot {
        Scaffold(bottomBar = { OneUiBottomBar(Routes.SETTINGS) {} }) { p ->
            Box(Modifier.padding(p)) { SettingsScreen(watchConnected = true) }
        }
    }

    @Test fun tallHome() = shot {
        Scaffold(bottomBar = { OneUiBottomBar(Routes.HOME) {} }) { p ->
            Box(Modifier.padding(p)) { HomeScreen(SampleData.home) }
        }
    }

    @Test fun tallProfile() = shot {
        ProfileScreen(
            UserProfile("Sara", "Karimi", "Sara", "1990-06-12", Gender.WOMAN, heightCm = 168f, weightKg = 61.5f),
            onBack = {},
            today = java.time.LocalDate.of(2026, 9, 24),
        )
    }
}
