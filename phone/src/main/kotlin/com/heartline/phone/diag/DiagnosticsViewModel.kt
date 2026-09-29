// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.diag

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.heartline.datalayer.diag.HLog
import com.heartline.phone.data.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DiagnosticsUi(
    val enabled: Boolean = false,
    val shouldAsk: Boolean = false,
    /** This phone's kept log, compressed. */
    val keptBytes: Long = 0,
    val folder: String? = null,
    val progress: PhoneLogExporter.Progress? = null,
    val saved: PhoneLogExporter.Result? = null,
    val error: String? = null,
) {
    val exporting: Boolean get() = progress != null
}

class DiagnosticsViewModel(
    private val repository: DiagnosticsRepository,
    private val exporter: PhoneLogExporter,
    settings: SettingsRepository,
) : ViewModel() {
    private val export = MutableStateFlow(DiagnosticsUi())

    val ui: StateFlow<DiagnosticsUi> = combine(repository.state, settings.monitor, export) { r, _, e ->
        e.copy(
            enabled = r.enabled,
            shouldAsk = r.shouldAsk,
            keptBytes = HLog.sizeBytes(),
            folder = r.folder,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiagnosticsUi())

    fun setEnabled(on: Boolean) = viewModelScope.launch { repository.setChoice(on) }

    fun delete() = viewModelScope.launch {
        repository.deleteLogs()
        export.update { it.copy(saved = null) }
    }

    fun export(folder: Uri) = viewModelScope.launch {
        repository.rememberFolder(folder.toString())
        export.update { it.copy(progress = PhoneLogExporter.Progress(PhoneLogExporter.Step.PHONE), saved = null, error = null) }
        runCatching { exporter.export(folder) { p -> export.update { it.copy(progress = p) } } }
            .onSuccess { result -> export.update { it.copy(progress = null, saved = result) } }
            .onFailure { e -> export.update { it.copy(progress = null, error = e.message ?: e.javaClass.simpleName) } }
    }
}
