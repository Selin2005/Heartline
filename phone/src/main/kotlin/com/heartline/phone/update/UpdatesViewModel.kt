// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.update

import android.content.Context
import android.content.Intent
import com.heartline.phone.notify.PhoneNotifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.heartline.shared.update.AppVersion
import com.heartline.shared.update.Release
import com.heartline.shared.update.Releases
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the Updates screen shows. */
data class UpdatesUi(
    val installed: String,
    val enabled: Boolean,
    val prefs: UpdateRepository.Prefs = UpdateRepository.Prefs(),
    val checking: Boolean = false,
    val available: Release? = null,
    /** Newest release of the chosen channels, once a check has run. */
    val latest: Release? = null,
    val upToDate: Boolean = false,
    val error: String? = null,
    /** The download running in the background (UpdateDownloadWorker), if any. */
    val download: UpdateDownloadWorker.State? = null,
    /** Every published release from the last check (when the watch's version was published). */
    val releases: List<Release> = emptyList(),
) {
    /**
     * Stable builds don't show the update channel setting; betas and dev builds do. Only the row
     * is hidden: the chosen channel (sticky, see UpdateRepository) keeps working underneath.
     */
    val showsChannel: Boolean get() = AppVersion.parse(installed)?.channel.let { it != null && it != AppVersion.Channel.STABLE }

    val watchBehind: Boolean get() = Releases.watchBehind(prefs.watchVersion, available ?: latest, releases)

    /** The downloaded update, unless a newer one is available. */
    val ready: String? get() = prefs.ready?.version?.takeIf { available == null || it == available.version.toString() }

    /** The download of the available version (a finished or another version's doesn't count). */
    val downloading: UpdateDownloadWorker.State? get() = download?.takeIf { ready == null && (available == null || it.version == available.version.toString()) }
}

/** Starts, follows and cancels the background download ([UpdateDownloadWorker]). */
interface UpdateDownloads {
    val state: Flow<UpdateDownloadWorker.State?>

    suspend fun start(release: Release)

    fun cancel()
}

class WorkManagerDownloads(private val context: Context) : UpdateDownloads {
    override val state: Flow<UpdateDownloadWorker.State?> = UpdateDownloadWorker.state(context)

    override suspend fun start(release: Release) = UpdateDownloadWorker.start(context, release)

    override fun cancel() = UpdateDownloadWorker.cancel(context)
}

class UpdatesViewModel(
    private val updater: Updater,
    private val repository: UpdateRepository,
    installed: String,
    private val downloads: UpdateDownloads,
    private val notifier: PhoneNotifier? = null,
) : ViewModel() {
    private val state = MutableStateFlow(UpdatesUi(installed, updater.enabled))
    val ui: StateFlow<UpdatesUi> = combine(state, repository.prefs, downloads.state) { s, p, d -> s.copy(prefs = p, download = d) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), state.value)

    private val intents = MutableSharedFlow<Intent>(extraBufferCapacity = 1)

    /** Activities to start (the "Install unknown apps" setting). */
    val startActivity: SharedFlow<Intent> = intents.asSharedFlow()

    private var job: Job? = null

    fun check() {
        if (job?.isActive == true || !updater.enabled) return
        job = viewModelScope.launch {
            state.update { it.copy(checking = true, error = null) }
            state.update {
                when (val result = updater.check()) {
                    is Updater.Check.Available -> it.copy(checking = false, available = result.release, latest = result.release, upToDate = false, releases = result.releases)
                    is Updater.Check.UpToDate -> it.copy(checking = false, available = null, latest = result.latest, upToDate = true, releases = result.releases)
                    is Updater.Check.Failed -> it.copy(checking = false, error = result.message)
                }
            }
        }
    }

    fun setAutoCheck(on: Boolean) = viewModelScope.launch { repository.setAutoCheck(on) }

    fun setTrack(track: AppVersion.Channel) = viewModelScope.launch {
        repository.setTrack(track)
        state.update { it.copy(available = null, upToDate = false) }
        check()
    }

    /**
     * Installs the available update: the downloaded file when there is one, or else starts the
     * background download, which installs (or notifies) when it's done.
     */
    fun install() {
        val current = ui.value
        if (current.ready != null) {
            installReady()
            return
        }
        val release = current.available ?: return
        if (!askedToAllow()) return
        viewModelScope.launch {
            state.update { it.copy(error = null) }
            runCatching { downloads.start(release) }.onFailure { e -> state.update { it.copy(error = e.message ?: e.javaClass.simpleName) } }
        }
    }

    /** Hands the downloaded update to the installer (also from the "ready" notification). */
    fun installReady() {
        if (!askedToAllow()) return
        viewModelScope.launch {
            val file = updater.readyFile()
            if (file == null) {
                // Damaged or deleted: download it again.
                ui.value.available?.let { downloads.start(it) } ?: check()
                return@launch
            }
            notifier?.cancelUpdateReady()
            runCatching { updater.install(file) }.onFailure { e -> state.update { it.copy(error = e.message ?: e.javaClass.simpleName) } }
        }
    }

    fun cancelDownload() = downloads.cancel()

    /** Set while the user is in "Install unknown apps": coming back with it allowed carries on. */
    private var waitingForPermission = false

    /**
     * Android asks once whether Heartline may install apps; it's asked before downloading, so the
     * finished download isn't stopped by it. Returns whether installing is allowed now.
     */
    private fun askedToAllow(): Boolean {
        if (updater.canInstall()) return true
        waitingForPermission = true
        intents.tryEmit(updater.allowInstallIntent())
        return false
    }

    /** The screen is shown again (back from the system setting). */
    fun onResume() {
        if (waitingForPermission && updater.canInstall()) {
            waitingForPermission = false
            install()
        }
    }
}
