package com.heartline.wear.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.DeveloperMode
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.heartline.wear.R
import com.heartline.wear.sensor.SensorProblem
import com.heartline.wear.ui.components.ActionScreen
import com.heartline.wear.ui.theme.WearColors

private data class ProblemUi(val icon: ImageVector, val title: Int, val body: Int, val action: Int?)

private val SensorProblem.ui: ProblemUi
    get() = when (this) {
        SensorProblem.NOT_SUPPORTED -> ProblemUi(Icons.Rounded.Block, R.string.error_not_supported_title, R.string.error_not_supported_body, null)
        SensorProblem.PERMISSION -> ProblemUi(Icons.Rounded.Lock, R.string.error_permission_title, R.string.error_permission_body, R.string.action_allow)
        SensorProblem.SERVICE_MISSING ->
            ProblemUi(Icons.Rounded.SystemUpdate, R.string.error_service_missing_title, R.string.error_service_missing_body, R.string.action_install)
        SensorProblem.SERVICE_OUTDATED ->
            ProblemUi(Icons.Rounded.SystemUpdate, R.string.error_service_title, R.string.error_service_body, R.string.action_update)
        SensorProblem.SDK_POLICY -> ProblemUi(Icons.Rounded.DeveloperMode, R.string.error_policy_title, R.string.error_policy_body, R.string.action_how_to)
        SensorProblem.OFF_BODY -> ProblemUi(Icons.Rounded.Watch, R.string.error_off_body_title, R.string.error_off_body_body, R.string.action_retry)
    }

@Composable
fun SensorErrorScreen(problem: SensorProblem, onAction: () -> Unit = {}) {
    val ui = problem.ui
    ActionScreen(stringResource(ui.action ?: R.string.action_done), onAction) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(WearColors.warn.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(ui.icon, contentDescription = null, tint = WearColors.warn, modifier = Modifier.size(22.dp))
        }
        Text(
            stringResource(ui.title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            stringResource(ui.body),
            style = MaterialTheme.typography.bodySmall,
            color = WearColors.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
