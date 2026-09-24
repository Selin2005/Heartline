package com.heartline.phone.ui.share

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.heartline.phone.ui.components.Chip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.heartline.phone.R
import com.heartline.phone.share.AiTarget
import com.heartline.phone.ui.components.PillButton
import com.heartline.phone.ui.components.TonalPillButton
import com.heartline.phone.ui.settings.OneUiSwitch
import com.heartline.phone.ui.theme.HeartlineTheme

/** One file format the sheet offers, e.g. PDF or image. */
data class FormatOption(val label: String, val extension: String)

/**
 * Bottom-sheet content for sharing a result: the file format (when there is more than one), an
 * editable file name, save and share buttons, and one icon tile per installed AI assistant that
 * accepts shares.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShareSheetContent(
    defaultName: String?,
    aiTargets: List<AiTarget>,
    formats: List<FormatOption> = emptyList(),
    onSave: ((name: String, format: Int) -> Unit)? = null,
    onShare: ((name: String, format: Int) -> Unit)? = null,
    onAi: (AiTarget, Boolean) -> Unit = { _, _ -> },
    includeNameDefault: Boolean = true,
    showAi: Boolean = true,
) {
    val colors = HeartlineTheme.colors
    var name by remember(defaultName) { mutableStateOf(defaultName.orEmpty()) }
    var format by remember { mutableIntStateOf(0) }
    var includeName by remember(includeNameDefault) { mutableStateOf(includeNameDefault) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
        Text(stringResource(R.string.share_title), style = MaterialTheme.typography.titleLarge, color = colors.onBackground)
        Spacer(Modifier.height(16.dp))
        if (defaultName != null) {
            if (formats.size > 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    formats.forEachIndexed { i, option -> Chip(option.label, format == i) { format = i } }
                }
                Spacer(Modifier.height(12.dp))
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(80) },
                label = { Text(stringResource(R.string.share_file_name)) },
                suffix = formats.getOrNull(format)?.let { { Text(".${it.extension}", color = colors.onSurfaceVariant) } },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                onSave?.let { save -> TonalPillButton(stringResource(R.string.share_save), onClick = { save(name, format) }, modifier = Modifier.weight(1f)) }
                onShare?.let { share -> PillButton(stringResource(R.string.share_send), onClick = { share(name, format) }, modifier = Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(24.dp))
        }
        if (!showAi) return@Column
        Text(stringResource(R.string.share_ai_title), style = MaterialTheme.typography.titleMedium, color = colors.onBackground)
        Spacer(Modifier.height(12.dp))
        if (aiTargets.isEmpty()) {
            Text(stringResource(R.string.share_ai_none), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                maxItemsInEachRow = 4,
                modifier = Modifier.fillMaxWidth(),
            ) {
                aiTargets.forEach { target -> AiTile(target, Modifier.weight(1f)) { onAi(target, includeName) } }
                // Keep tiles the same width when the last row is not full.
                repeat((4 - aiTargets.size % 4) % 4) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.share_include_name), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
                OneUiSwitch(includeName) { includeName = it }
            }
        }
    }
}

/** An assistant as its own app icon on a soft tile, with its name below. */
@Composable
private fun AiTile(target: AiTarget, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = HeartlineTheme.colors
    val description = stringResource(R.string.share_with, target.label)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surfaceVariant)
            .clickable(onClickLabel = description, onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 6.dp)
            .semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        val icon = target.icon
        if (icon != null) {
            val bitmap = remember(icon) { icon.toBitmap(144, 144).asImageBitmap() }
            Image(bitmap, contentDescription = null, modifier = Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)))
        } else {
            // No icon available (never for an installed app; used in previews): a monogram in the app's colour.
            val (background, foreground) = monogramColors(target.packageName)
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(background), contentAlignment = Alignment.Center) {
                Text(target.label.take(1), style = MaterialTheme.typography.titleMedium, color = foreground)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(target.label, style = MaterialTheme.typography.labelMedium, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun monogramColors(packageName: String): Pair<Color, Color> = when (packageName) {
    "com.anthropic.claude" -> Color(0xFFD97757) to Color.White
    "com.openai.chatgpt" -> Color(0xFF10A37F) to Color.White
    "com.google.android.apps.bard" -> Color(0xFF4285F4) to Color.White
    "ai.x.grok" -> Color(0xFF111114) to Color.White
    else -> Color(0xFF3E7BFA) to Color.White
}

/** Shown before the first share to an AI app. */
@Composable
fun AiConsentDialog(appLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ai_consent_title, appLabel)) },
        text = { Text(stringResource(R.string.ai_consent_body, appLabel)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.ai_consent_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
