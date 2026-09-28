// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.ui.share

import android.content.Intent
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.heartline.phone.R
import com.heartline.phone.data.ProfileRepository
import com.heartline.phone.data.SettingsRepository
import com.heartline.phone.share.AiShare
import com.heartline.phone.share.AiTarget
import com.heartline.phone.share.FileNames
import com.heartline.phone.share.ResultSummary
import com.heartline.phone.ui.theme.HeartlineTheme
import com.heartline.shared.profile.UserProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel
import java.io.File

/** One file a screen can produce, built on demand under the chosen name. */
data class ShareFormat(
    val extension: String,
    val mime: String,
    @param:StringRes val label: Int,
    /** Writes the file; [name] is the name to print (the user's Settings choice) or null. */
    val build: suspend (fileName: String, name: String?, profile: UserProfile?) -> File,
)

/** What one screen can share: files in one or more formats (optional) and a text summary. */
data class ShareRequest(
    /** "ECG", "BloodPressure"… used in the default file name. */
    val kind: String,
    val formats: List<ShareFormat> = emptyList(),
    /** The summary for AI apps: prompt + values; name included only when allowed. */
    val text: (prompt: String, person: String?) -> String,
    /** Offer AI apps (not for raw data exports). */
    val allowAi: Boolean = true,
)

class ShareViewModel(private val settings: SettingsRepository, profiles: ProfileRepository) : ViewModel() {
    val sharing: StateFlow<SettingsRepository.SharingPrefs> = settings.sharing.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsRepository.SharingPrefs())
    val profile: StateFlow<UserProfile?> = profiles.profile.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun consent() = viewModelScope.launch { settings.setAiConsent() }

    suspend fun prefs() = settings.sharing.first()
}

/**
 * Hosts the share sheet for a screen. Call the returned function to open it for a request.
 * Handles: file name → build → save (system "create document") or share; AI apps (first-time
 * consent, text + attachment when the app takes it).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberShareSheet(vm: ShareViewModel = koinViewModel()): (ShareRequest) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val prefs by vm.sharing.collectAsStateWithLifecycle()
    var request by remember { mutableStateOf<ShareRequest?>(null) }
    var targets by remember { mutableStateOf(emptyList<AiTarget>()) }
    var pendingAi by remember { mutableStateOf<Pair<AiTarget, Boolean>?>(null) }
    var pendingSave by remember { mutableStateOf<File?>(null) }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val file = pendingSave
        pendingSave = null
        if (uri != null && file != null) {
            scope.launch {
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } }
                Toast.makeText(context, R.string.share_saved, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val name = profile?.reportName(prefs.reportName)

    fun fileName(req: ShareRequest, format: ShareFormat, typed: String) =
        FileNames.sanitize(typed, format.extension, FileNames.default(req.kind, null, java.time.LocalDateTime.now()))

    suspend fun build(req: ShareRequest, format: ShareFormat, typed: String) =
        withContext(Dispatchers.IO) { format.build(fileName(req, format, typed), name, profile) }

    fun sendToAi(req: ShareRequest, target: AiTarget, includeName: Boolean) = scope.launch {
        val p = vm.prefs()
        val person = name.takeIf { includeName }
        val text = req.text(ResultSummary.prompt(context.resources, p.prompt), person)
        // The report goes along when the app takes it: PDF first, else the image.
        val format = if (p.attachPdf) req.formats.firstOrNull { target.accepts(it.mime) } else null
        val file = format?.let { withContext(Dispatchers.IO) { it.build(fileName(req, it, defaultName(req, person)), person, profile) } }
        runCatching { context.startActivity(AiShare.intent(context, target, text, file, format?.mime)) }
        request = null
    }

    request?.let { req ->
        val formats = req.formats
        ModalBottomSheet(onDismissRequest = { request = null }, containerColor = HeartlineTheme.colors.surface) {
            ShareSheetContent(
                defaultName = if (formats.isNotEmpty()) defaultName(req, name) else null,
                aiTargets = targets,
                formats = formats.map { FormatOption(context.getString(it.label), it.extension) },
                showAi = req.allowAi,
                includeNameDefault = name != null,
                onSave = { typed, i ->
                    scope.launch {
                        val file = build(req, formats[i], typed)
                        pendingSave = file
                        save.launch(file.name)
                    }
                },
                onShare = { typed, i ->
                    scope.launch {
                        val format = formats[i]
                        val file = build(req, format, typed)
                        val intent = Intent(Intent.ACTION_SEND)
                            .setType(format.mime)
                            .putExtra(Intent.EXTRA_STREAM, AiShare.uriFor(context, file))
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_via)))
                        request = null
                    }
                },
                onAi = { target, includeName ->
                    if (prefs.consent) sendToAi(req, target, includeName) else pendingAi = target to includeName
                },
            )
        }
    }
    pendingAi?.let { (target, includeName) ->
        AiConsentDialog(
            target.label,
            onConfirm = {
                vm.consent()
                pendingAi = null
                request?.let { sendToAi(it, target, includeName) }
            },
            onDismiss = { pendingAi = null },
        )
    }
    return { req ->
        targets = AiShare.available(context)
        request = req
    }
}

private fun defaultName(req: ShareRequest, name: String?) = FileNames.default(req.kind, name, java.time.LocalDateTime.now())
