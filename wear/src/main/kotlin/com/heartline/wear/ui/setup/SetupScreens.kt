package com.heartline.wear.ui.setup

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeveloperMode
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PersonOutline
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PhonelinkErase
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnState
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.heartline.shared.sync.LinkStage
import com.heartline.wear.R
import com.heartline.wear.ui.components.ActionScreen
import com.heartline.wear.ui.theme.WearColors

/** Pulsing rings around an icon while something is being checked. */
@Composable
fun CheckingScreen(icon: ImageVector, title: String, body: String, animate: Boolean = true) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val phase by if (animate) {
        transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1_600), RepeatMode.Restart), label = "phase")
    } else {
        remember { mutableFloatStateOf(0.45f) }
    }
    val primary = WearColors.primary
    ScreenScaffold {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(76.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val r = size.minDimension / 2
                    for (k in 0..1) {
                        val p = (phase + k * 0.5f) % 1f
                        drawCircle(primary.copy(alpha = (1f - p) * 0.55f), radius = r * (0.45f + 0.55f * p), style = Stroke(2.dp.toPx()))
                    }
                    drawCircle(primary.copy(alpha = 0.18f), radius = r * 0.42f, center = Offset(size.width / 2, size.height / 2))
                }
                Icon(icon, contentDescription = null, tint = primary, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Text(body, style = MaterialTheme.typography.bodySmall, color = WearColors.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun InfoScreen(
    icon: ImageVector,
    tint: Color,
    title: String,
    body: String,
    action: String,
    onAction: () -> Unit,
    secondary: String? = null,
    onSecondary: () -> Unit = {},
    note: String? = null,
) {
    val openLabel = stringResource(R.string.action_open_on_phone)
    ActionScreen(action, onAction, wideAction = action.length > 9, compactLabel = if (action == openLabel) stringResource(R.string.action_open_short) else null) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(tint.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(21.dp))
        }
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        Text(body, style = MaterialTheme.typography.bodySmall, color = WearColors.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
        note?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = WearColors.primary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        }
        secondary?.let {
            FilledTonalButton(onClick = onSecondary, modifier = Modifier.padding(top = 8.dp)) { Text(it, maxLines = 1) }
        }
    }
}

@Composable
fun PhoneProblemScreen(stage: LinkStage, onRetry: () -> Unit = {}, onOpenOnPhone: () -> Unit = {}, opened: Boolean? = null) {
    val (title, body) = when (stage) {
        LinkStage.APP_MISSING -> R.string.link_app_missing_title to R.string.link_app_missing_body
        LinkStage.NO_RESPONSE -> R.string.link_no_response_title to R.string.link_no_response_body
        LinkStage.INCOMPATIBLE -> R.string.link_incompatible_title to R.string.link_incompatible_body
        else -> R.string.link_no_phone_title to R.string.link_no_phone_body
    }
    InfoScreen(
        icon = if (stage == LinkStage.NO_DEVICE) Icons.Rounded.PhonelinkErase else Icons.Rounded.PhoneAndroid,
        tint = WearColors.warn,
        title = stringResource(title),
        body = stringResource(body),
        // The phone app is installed but silent: opening it is the fix (the watch re-checks by itself).
        action = stringResource(if (stage == LinkStage.NO_RESPONSE) R.string.action_open_on_phone else R.string.action_try_again),
        onAction = if (stage == LinkStage.NO_RESPONSE) onOpenOnPhone else onRetry,
        note = openedNote(opened),
    )
}

@Composable
fun SetupIncompleteScreen(name: String?, onOpenOnPhone: () -> Unit = {}, opened: Boolean? = null) {
    InfoScreen(
        icon = Icons.Rounded.PersonOutline,
        tint = WearColors.primary,
        title = stringResource(R.string.setup_incomplete_title),
        body = stringResource(R.string.setup_incomplete_body),
        action = stringResource(R.string.action_open_on_phone),
        onAction = onOpenOnPhone,
        note = openedNote(opened) ?: stringResource(R.string.setup_continues_automatically),
    )
}

@Composable
fun PermissionsScreen(onAllow: () -> Unit = {}) {
    InfoScreen(
        icon = Icons.Rounded.Lock,
        tint = WearColors.primary,
        title = stringResource(R.string.setup_permissions_title),
        body = stringResource(R.string.setup_permissions_body),
        action = stringResource(R.string.action_allow),
        onAction = onAllow,
    )
}

@Composable
fun ServiceProblemScreen(outdated: Boolean, onAction: () -> Unit = {}) {
    InfoScreen(
        icon = Icons.Rounded.SystemUpdate,
        tint = WearColors.warn,
        title = stringResource(if (outdated) R.string.error_service_title else R.string.error_service_missing_title),
        body = stringResource(if (outdated) R.string.error_service_body else R.string.error_service_missing_body),
        action = stringResource(if (outdated) R.string.action_update else R.string.action_install),
        onAction = onAction,
    )
}

@Composable
private fun openedNote(opened: Boolean?): String? = when (opened) {
    true -> stringResource(R.string.opened_on_phone)
    false -> stringResource(R.string.open_on_phone_failed)
    null -> null
}

private val devModeSteps = listOf(
    R.string.dev_step_1,
    R.string.dev_step_2,
    R.string.dev_step_3,
    R.string.dev_step_4,
    R.string.dev_step_5,
    R.string.dev_step_6,
)

/**
 * Health Platform developer mode, step by step, ending with "Check again" (re-runs the probe).
 * [problemFound]: opened because the probe found developer mode off (vs. from Settings).
 */
@Composable
fun DevModeGuideScreen(
    problemFound: Boolean = true,
    checking: Boolean = false,
    onCheckAgain: () -> Unit = {},
    onShowOnPhone: () -> Unit = {},
    state: TransformingLazyColumnState = rememberTransformingLazyColumnState(),
) {
    val list = state
    ScreenScaffold(scrollState = list) { padding ->
        TransformingLazyColumn(state = list, contentPadding = padding) {
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Box(Modifier.size(34.dp).clip(CircleShape).background(WearColors.warn.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.DeveloperMode, contentDescription = null, tint = WearColors.warn, modifier = Modifier.size(19.dp))
                    }
                    ListHeader { Text(stringResource(R.string.dev_mode_title), textAlign = TextAlign.Center) }
                }
            }
            item {
                Text(
                    stringResource(if (problemFound) R.string.dev_mode_intro_off else R.string.dev_mode_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = WearColors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                )
            }
            devModeSteps.forEachIndexed { i, step ->
                item { StepCard(i + 1, stringResource(step)) }
            }
            item {
                Text(
                    stringResource(R.string.dev_mode_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = WearColors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            item {
                FilledTonalButton(onClick = onShowOnPhone, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.PhoneAndroid, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.dev_mode_show_on_phone), maxLines = 1)
                }
            }
            item {
                Button(
                    onClick = onCheckAgain,
                    enabled = !checking,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = WearColors.primary),
                ) {
                    Text(stringResource(if (checking) R.string.checking else R.string.action_check_again), maxLines = 1, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                }
            }
        }
    }
}

@Composable
private fun StepCard(number: Int, text: String) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(WearColors.surface)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(WearColors.primary), contentAlignment = Alignment.Center) {
            Text("$number", style = MaterialTheme.typography.labelSmall, color = Color.Black)
        }
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = WearColors.onSurface)
    }
}

/** Shown while the Health Platform probe runs. */
@Composable
fun CheckingSensorsScreen(animate: Boolean = true) =
    CheckingScreen(Icons.Rounded.Watch, stringResource(R.string.setup_checking_sensors_title), stringResource(R.string.setup_checking_sensors_body), animate)
