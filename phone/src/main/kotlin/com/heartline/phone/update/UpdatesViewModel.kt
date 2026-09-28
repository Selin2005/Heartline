// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.update

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.heartline.shared.update.AppVersion
import com.heartline.shared.update.Release
import com.heartline.shared.update.Releases
import kotlinx.coroutines.Job
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
    /** 0..1 while downloading, null otherwise. */
    val progress: Float? = null,
    /** Every published release from the last check (when the watch's version was published). */
    val releases: List<Release> = emptyList(),
) {
    /**
     * Stable builds don't show the update channel setting; betas and dev builds do. Only the row
     * is hidden: the chosen channel (sticky, see UpdateRepository) keeps working underneath.
     */
    val showsChannel: Boolean get() = AppVersion.parse(installed)?.channel.let { it != null && it != AppVersion.Channel.STABLE }

    val watchBehind: Boolean get() = Releases.watchBehind(prefs.watchVersion, available ?: latest, releases)
}

class UpdatesViewModel(private val updater: Updater, private val repository: UpdateRepository, installed: String) : ViewModel() {
    private val state = MutableStateFlow(UpdatesUi(installed, updater.enabled))
    val ui: StateFlow<UpdatesUi> = combine(state, repository.prefs) { s, p -> s.copy(prefs = p) }
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

    fun install() {
        val release = state.value.available ?: return
        if (job?.isActive == true) return
        if (!updater.canInstall()) {
            intents.tryEmit(updater.allowInstallIntent())
            return
        }
        job = viewModelScope.launch {
            state.update { it.copy(progress = 0f, error = null) }
            runCatching { updater.download(release) { p -> state.update { it.copy(progress = p) } } }
                .onSuccess { file ->
                    state.update { it.copy(progress = null) }
                    updater.install(file)
                }
                .onFailure { e -> state.update { it.copy(progress = null, error = e.message ?: e.javaClass.simpleName) } }
        }
    }
}
