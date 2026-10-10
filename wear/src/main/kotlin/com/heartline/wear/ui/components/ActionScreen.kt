// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.rememberActiveFocusRequester
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text

/**
 * Centered, scrollable content with a single primary action pinned to the bottom edge
 * (SHM's "Start"/"Done" pattern). The button is laid out statically, so it is visible from the
 * first frame and never overlaps the content.
 */
@Composable
fun ActionScreen(
    actionLabel: String,
    onAction: () -> Unit,
    wideAction: Boolean = false,
    compactLabel: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    val screen = LocalConfiguration.current.screenHeightDp.dp
    ScreenScaffold(scrollState = scroll) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            // Content area above the button: centred when it fits, scrollable when it doesn't.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(top = screen * 0.10f, start = screen * 0.09f, end = screen * 0.09f, bottom = 4.dp),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    // The crown and the rotating bezel scroll long content (an ECG result's details).
                    modifier = Modifier
                        .rotaryScrollable(RotaryScrollableDefaults.behavior(scroll), rememberActiveFocusRequester())
                        .verticalScroll(scroll),
                    content = content,
                )
            }
            // Longer labels ("Open on phone") need the wider medium edge button to avoid an ellipsis.
            // On small screens a long label switches to [compactLabel] rather than a taller button.
            val small = isSmallRound()
            val label = if (small && compactLabel != null) compactLabel else actionLabel
            val wide = wideAction && !(small && compactLabel != null)
            val size = when {
                wide -> EdgeButtonSize.Medium
                small -> EdgeButtonSize.ExtraSmall
                else -> EdgeButtonSize.Small
            }
            EdgeButton(onClick = onAction, buttonSize = size) {
                Text(label, maxLines = 1, style = if (wide) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** True on ~40 mm class screens (< 210 dp), where content must be more compact. */
@Composable
fun isSmallRound(): Boolean = LocalConfiguration.current.screenHeightDp < 210
