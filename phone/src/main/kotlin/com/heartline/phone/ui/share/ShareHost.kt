package com.heartline.phone.ui.share

import android.content.Intent
import android.widget.Toast
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

/** What one screen can share: an optional file (built on demand under the chosen name) and a text summary. */
data class ShareRequest(
    /** "ECG", "BloodPressure"… used in the default file name. */
    val kind: String,
    val extension: String?,
    val mime: String?,
    /** Writes the file under the given (sanitised) name; null when only text is shared. */
    val buildFile: (suspend (fileName: String, profile: UserProfile?) -> File)?,
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

    fun fileName(req: ShareRequest, typed: String) =
        FileNames.sanitize(typed, req.extension ?: "txt", FileNames.default(req.kind, null, java.time.LocalDateTime.now()))

    fun sendToAi(req: ShareRequest, target: AiTarget, includeName: Boolean) = scope.launch {
        val p = vm.prefs()
        val person = profile?.reportDisplayName?.takeIf { includeName && it.isNotBlank() }
        val text = req.text(ResultSummary.prompt(context.resources, p.prompt), person)
        val file = if (p.attachPdf && target.acceptsPdf && req.buildFile != null && req.mime == "application/pdf") {
            req.buildFile.invoke(fileName(req, defaultName(req, profile)), profile.takeIf { includeName })
        } else {
            null
        }
        runCatching { context.startActivity(AiShare.intent(context, target, text, file)) }
        request = null
    }

    request?.let { req ->
        ModalBottomSheet(onDismissRequest = { request = null }, containerColor = HeartlineTheme.colors.surface) {
            ShareSheetContent(
                defaultName = req.extension?.let { defaultName(req, profile) },
                aiTargets = targets,
                showAi = req.allowAi,
                onSave = req.buildFile?.let {
                    { typed ->
                        scope.launch {
                            val name = fileName(req, typed)
                            pendingSave = it(name, profile)
                            save.launch(name)
                        }
                    }
                },
                onShare = req.buildFile?.let {
                    { typed ->
                        scope.launch {
                            val file = it(fileName(req, typed), profile)
                            val intent = Intent(Intent.ACTION_SEND)
                                .setType(req.mime)
                                .putExtra(Intent.EXTRA_STREAM, AiShare.uriFor(context, file))
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_via)))
                            request = null
                        }
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

private fun defaultName(req: ShareRequest, profile: UserProfile?) =
    FileNames.default(req.kind, profile?.reportDisplayName?.takeIf { it.isNotBlank() }, java.time.LocalDateTime.now())
