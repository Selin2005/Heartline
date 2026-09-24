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
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text

/**
 * Centered, scrollable content with a single primary action pinned to the bottom edge
 * (SHM's "Start"/"Done" pattern). The button is laid out statically, so it is visible from the
 * first frame and never overlaps the content.
 */
@Composable
fun ActionScreen(actionLabel: String, onAction: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
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
                    modifier = Modifier.verticalScroll(scroll),
                    content = content,
                )
            }
            EdgeButton(onClick = onAction, buttonSize = if (isSmallRound()) EdgeButtonSize.ExtraSmall else EdgeButtonSize.Small) {
                Text(actionLabel, maxLines = 1)
            }
        }
    }
}

/** True on ~40 mm class screens (< 210 dp), where content must be more compact. */
@Composable
fun isSmallRound(): Boolean = LocalConfiguration.current.screenHeightDp < 210
