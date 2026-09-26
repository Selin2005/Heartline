// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.ui.help

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.heartline.phone.R
import com.heartline.phone.ui.components.PillButton
import com.heartline.phone.ui.components.ReachabilityScaffold
import com.heartline.phone.ui.components.RoundedCard
import com.heartline.phone.ui.components.gutter
import com.heartline.phone.ui.theme.HeartlineTheme

/** Numbered steps shared by the phone help page (the watch has its own compact version). */
val devModeSteps = listOf(
    R.string.devmode_step_1,
    R.string.devmode_step_2,
    R.string.devmode_step_3,
    R.string.devmode_step_4,
    R.string.devmode_step_5,
    R.string.devmode_step_6,
)

@Composable
fun DevModeHelpScreen(onBack: (() -> Unit)? = null, onCheckOnWatch: () -> Unit = {}) {
    val colors = HeartlineTheme.colors
    ReachabilityScaffold(title = stringResource(R.string.devmode_title), onBack = onBack) {
        item {
            RoundedCard(Modifier.gutter()) {
                Text(stringResource(R.string.devmode_why), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
        }
        item {
            RoundedCard(Modifier.gutter()) {
                devModeSteps.forEachIndexed { i, step ->
                    Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(bottom = if (i == devModeSteps.lastIndex) 0.dp else 16.dp)) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(28.dp).background(colors.primary, CircleShape)) {
                            Text("${i + 1}", style = MaterialTheme.typography.labelLarge, color = Color.White)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(step), style = MaterialTheme.typography.bodyLarge, color = colors.onBackground)
                        }
                    }
                }
            }
        }
        item {
            RoundedCard(Modifier.gutter()) {
                Text(stringResource(R.string.devmode_note_title), style = MaterialTheme.typography.titleSmall, color = colors.onBackground)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.devmode_note), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
        item {
            PillButton(stringResource(R.string.devmode_check_on_watch), onClick = onCheckOnWatch, modifier = Modifier.gutter())
        }
    }
}
