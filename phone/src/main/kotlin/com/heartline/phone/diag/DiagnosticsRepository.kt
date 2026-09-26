// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.diag

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.heartline.datalayer.diag.HLog
import com.heartline.phone.data.SettingsRepository
import com.heartline.phone.update.UpdateRepository
import com.heartline.shared.diag.DiagnosticsPolicy
import com.heartline.shared.diag.RemoteLogs
import com.heartline.shared.hr.MonitorSettings
import com.heartline.shared.update.AppVersion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

private val Context.diagnosticsStore by preferencesDataStore("diagnostics")

/**
 * Decides whether both apps keep a diagnostic log: beta users by default, stable users only when
 * they agree (asked once). The phone writes the result into the synced settings, so the watch
 * follows it.
 */
class DiagnosticsRepository(
    private val context: Context,
    private val settings: SettingsRepository,
    updates: UpdateRepository,
    installedVersion: String,
    private val remote: RemoteLogs,
    private val sendSettings: suspend (MonitorSettings) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    data class State(val choice: Boolean?, val betaUser: Boolean, val folder: String?) {
        val enabled: Boolean get() = DiagnosticsPolicy.enabled(choice, betaUser)
        val shouldAsk: Boolean get() = DiagnosticsPolicy.shouldAsk(choice, betaUser)
    }

    private object Keys {
        val CHOICE = booleanPreferencesKey("choice")
        val FOLDER = stringPreferencesKey("export_folder")
    }

    private val installedBeta = AppVersion.parse(installedVersion)?.channel != AppVersion.Channel.STABLE

    val state: Flow<State> = combine(context.diagnosticsStore.data, updates.prefs) { p, u ->
        State(p[Keys.CHOICE], installedBeta || u.beta, p[Keys.FOLDER])
    }

    suspend fun current(): State = state.first()

    /** Keeps the synced settings and this phone's logger in step with the decision. */
    fun start(scope: CoroutineScope) {
        state.map { it.enabled }.distinctUntilChanged().onEach { enabled ->
            val current = settings.current()
            if (current.diagnosticLogs != enabled) {
                sendSettings(settings.update { it.copy(diagnosticLogs = enabled, detailedLogsUntilMs = if (enabled) it.detailedLogsUntilMs else 0) })
            }
        }.launchIn(scope)
        settings.monitor.onEach { HLog.configure(it.diagnosticLogs, it.detailedLogsUntilMs) }.launchIn(scope)
    }

    /** The user's answer (Settings switch or the one-time question). */
    suspend fun setChoice(on: Boolean) {
        context.diagnosticsStore.edit { it[Keys.CHOICE] = on }
    }

    suspend fun setDetailed(on: Boolean) {
        sendSettings(settings.update { it.copy(detailedLogsUntilMs = if (on) now() + DiagnosticsPolicy.DETAILED_DURATION_MS else 0) })
    }

    suspend fun rememberFolder(uri: String) {
        context.diagnosticsStore.edit { it[Keys.FOLDER] = uri }
    }

    /** Erases the kept logs on both devices (logging continues if it's on). */
    suspend fun deleteLogs() {
        HLog.clear()
        remote.delete()
    }
}
