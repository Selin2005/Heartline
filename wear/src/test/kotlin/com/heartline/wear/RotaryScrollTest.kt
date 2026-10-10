// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performRotaryScrollInput
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.heartline.wear.ui.components.ActionScreen
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Long screens (an ECG result's details) scroll with the crown and the rotating bezel. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w227dp-h227dp-round-xhdpi")
class RotaryScrollTest {
    @get:Rule
    val compose = createComposeRule()

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun theCrownScrollsAnActionScreen() {
        compose.setContent {
            MaterialTheme {
                ActionScreen("Done", onAction = {}) {
                    repeat(30) { Text("Line $it") }
                }
            }
        }
        val before = compose.onNodeWithText("Line 0").getUnclippedBoundsInRoot().top
        compose.onRoot().performRotaryScrollInput { rotateToScrollVertically(300f) }
        compose.waitForIdle()
        val after = compose.onNodeWithText("Line 0").getUnclippedBoundsInRoot().top
        assertTrue("moved from $before to $after", after < before)
    }
}
