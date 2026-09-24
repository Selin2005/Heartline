package com.heartline.phone.ui.share

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.heartline.phone.R
import com.heartline.phone.share.AiTarget
import com.heartline.phone.ui.components.OutlinedPill
import com.heartline.phone.ui.components.PillButton
import com.heartline.phone.ui.components.TonalPillButton
import com.heartline.phone.ui.settings.OneUiSwitch
import com.heartline.phone.ui.theme.HeartlineTheme

/**
 * Bottom-sheet content for sharing a result: an editable file name (when there is a file), save
 * and share buttons, and one button per installed AI assistant that accepts shares.
 */
@Composable
fun ShareSheetContent(
    defaultName: String?,
    aiTargets: List<AiTarget>,
    onSave: ((String) -> Unit)? = null,
    onShare: ((String) -> Unit)? = null,
    onAi: (AiTarget, Boolean) -> Unit = { _, _ -> },
    includeNameDefault: Boolean = true,
    showAi: Boolean = true,
) {
    val colors = HeartlineTheme.colors
    var name by remember(defaultName) { mutableStateOf(defaultName.orEmpty()) }
    var includeName by remember { mutableStateOf(includeNameDefault) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
        Text(stringResource(R.string.share_title), style = MaterialTheme.typography.titleLarge, color = colors.onBackground)
        Spacer(Modifier.height(16.dp))
        if (defaultName != null) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(80) },
                label = { Text(stringResource(R.string.share_file_name)) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                onSave?.let { save -> TonalPillButton(stringResource(R.string.share_save), onClick = { save(name) }, modifier = Modifier.weight(1f)) }
                onShare?.let { share -> PillButton(stringResource(R.string.share_send), onClick = { share(name) }, modifier = Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(20.dp))
        }
        if (!showAi) return@Column
        Text(stringResource(R.string.share_ai_title), style = MaterialTheme.typography.titleMedium, color = colors.onBackground)
        Spacer(Modifier.height(8.dp))
        if (aiTargets.isEmpty()) {
            Text(stringResource(R.string.share_ai_none), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.share_include_name), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
                OneUiSwitch(includeName) { includeName = it }
            }
            Spacer(Modifier.height(8.dp))
            aiTargets.forEach { target ->
                OutlinedPill(
                    text = stringResource(R.string.share_with, target.label),
                    onClick = { onAi(target, includeName) },
                    leading = target.icon?.let { icon ->
                        {
                            val bitmap = remember(icon) { icon.toBitmap(64, 64).asImageBitmap() }
                            Image(bitmap, contentDescription = null, modifier = Modifier.size(24.dp).clip(RoundedCornerShape(6.dp)))
                            Spacer(Modifier.width(10.dp))
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
            }
        }
    }
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
