package com.heartline.wear.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.heartline.wear.R
import com.heartline.wear.ui.theme.WearColors

/** Live heart rate with the last minute as a bar trend (Samsung Health style). */
@Composable
fun HeartRateScreen(bpm: Int?, recent: List<Int>, onBody: Boolean) {
    val color = WearColors.metric(com.heartline.shared.model.Metric.HEART_RATE)
    Box(Modifier.fillMaxSize().background(WearColors.background), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 24.dp),
        ) {
            Icon(Icons.Rounded.Favorite, contentDescription = null, tint = color, modifier = Modifier.size(26.dp))
            when {
                !onBody -> Text(
                    stringResource(R.string.hr_off_body),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = WearColors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                bpm == null -> Text(stringResource(R.string.hr_measuring), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                else -> {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("$bpm", style = MaterialTheme.typography.displayLarge)
                        Text(
                            stringResource(R.string.unit_bpm),
                            style = MaterialTheme.typography.bodySmall,
                            color = WearColors.onSurfaceVariant,
                            modifier = Modifier.padding(start = 3.dp, bottom = 7.dp),
                        )
                    }
                    Text(stringResource(R.string.hr_now), style = MaterialTheme.typography.bodySmall, color = WearColors.onSurfaceVariant)
                }
            }
            if (recent.size >= 2) {
                val min = (recent.min() - 5).toFloat()
                val max = (recent.max() + 5).toFloat()
                Canvas(Modifier.fillMaxWidth().padding(top = 10.dp, start = 8.dp, end = 8.dp).height(30.dp)) {
                    val step = size.width / recent.size
                    recent.forEachIndexed { i, v ->
                        val h = (v - min) / (max - min) * size.height
                        val x = i * step + step / 2
                        drawLine(color, Offset(x, size.height), Offset(x, size.height - h), strokeWidth = step * 0.55f, cap = StrokeCap.Round)
                    }
                }
            }
        }
    }
}
