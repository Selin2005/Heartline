// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Text
import com.heartline.wear.ui.theme.WearColors

/**
 * A big number with a small unit ("11 sec", "68 bpm"). The number itself sits on the centre line,
 * and the unit hangs to its right, so the number lines up with the icon and text above and below it.
 * A plain Row would centre number + unit together and push the number off to the left.
 */
@Composable
fun CenteredValue(value: String, unit: String, valueStyle: TextStyle, unitStyle: TextStyle, modifier: Modifier = Modifier, unitGap: Dp = 3.dp) {
    Layout(
        content = {
            Text(value, style = valueStyle)
            Text(unit, style = unitStyle, color = WearColors.onSurfaceVariant, modifier = Modifier.padding(start = unitGap))
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val number = measurables[0].measure(loose)
        val suffix = measurables[1].measure(loose)
        val side = suffix.width
        val width = number.width + 2 * side
        val height = number.height
        layout(width, height) {
            number.place(side, 0)
            // Unit baseline on the number's baseline.
            val numberBaseline = number[FirstBaseline].takeIf { it > 0 } ?: number.height
            val unitBaseline = suffix[FirstBaseline].takeIf { it > 0 } ?: suffix.height
            suffix.place(side + number.width, numberBaseline - unitBaseline)
        }
    }
}
