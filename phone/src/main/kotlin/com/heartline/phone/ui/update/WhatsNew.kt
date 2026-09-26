// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.ui.update

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.heartline.phone.R
import com.heartline.phone.legal.BundledDoc
import com.heartline.phone.ui.components.MarkdownBlocks
import com.heartline.phone.update.UpdateRepository
import com.heartline.shared.text.Changelog
import com.heartline.shared.text.ChangelogEntry
import com.heartline.shared.text.Markdown
import org.koin.compose.koinInject

/** After an update, shows that version's notes from the bundled CHANGELOG.md once. */
@Composable
fun WhatsNewAfterUpdate(versionName: String, onAllNotes: () -> Unit) {
    val context = LocalContext.current
    val updates: UpdateRepository = koinInject()
    var entry by remember { mutableStateOf<ChangelogEntry?>(null) }
    LaunchedEffect(versionName) {
        if (updates.claimVersionChange() != null) {
            entry = Changelog.entryFor(BundledDoc.CHANGELOG.read(context), versionName)?.takeIf { it.notes.isNotBlank() }
        }
    }
    entry?.let { WhatsNewDialog(versionName, it, onDismiss = { entry = null }, onAllNotes = { entry = null; onAllNotes() }) }
}

@Composable
fun WhatsNewDialog(versionName: String, entry: ChangelogEntry, onDismiss: () -> Unit = {}, onAllNotes: () -> Unit = {}) {
    val uri = LocalUriHandler.current
    val blocks = remember(entry) { Markdown.parse(entry.notes) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.whats_new_after_update, versionName)) },
        text = {
            MarkdownBlocks(
                blocks,
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                onLink = { runCatching { uri.openUri(Markdown.resolve(it, "")) } },
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) } },
        dismissButton = { TextButton(onClick = onAllNotes) { Text(stringResource(R.string.whats_new_all)) } },
    )
}
