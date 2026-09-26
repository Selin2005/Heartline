// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.update

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.heartline.shared.update.AppVersion
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.updateStore by preferencesDataStore("updates")

/** Update preferences, what was last announced, and the version the watch reported. */
class UpdateRepository(private val context: Context, private val installedVersion: String) {
    data class Prefs(
        val autoCheck: Boolean = true,
        /** Betas are offered by default when a beta is installed. */
        val beta: Boolean = false,
        val lastCheckMs: Long = 0,
        val notifiedVersion: String? = null,
        val lastSeenVersion: String? = null,
        val watchVersion: String? = null,
    )

    private object Keys {
        val AUTO = booleanPreferencesKey("auto_check")
        val BETA = booleanPreferencesKey("beta")
        val LAST_CHECK = longPreferencesKey("last_check")
        val NOTIFIED = stringPreferencesKey("notified_version")
        val LAST_SEEN = stringPreferencesKey("last_seen_version")
        val WATCH = stringPreferencesKey("watch_version")
    }

    private val installedIsBeta = AppVersion.parse(installedVersion)?.channel == AppVersion.Channel.BETA

    val prefs: Flow<Prefs> = context.updateStore.data.map {
        Prefs(
            autoCheck = it[Keys.AUTO] ?: true,
            beta = it[Keys.BETA] ?: installedIsBeta,
            lastCheckMs = it[Keys.LAST_CHECK] ?: 0,
            notifiedVersion = it[Keys.NOTIFIED],
            lastSeenVersion = it[Keys.LAST_SEEN],
            watchVersion = it[Keys.WATCH],
        )
    }

    suspend fun current() = prefs.first()

    suspend fun setAutoCheck(on: Boolean) = context.updateStore.edit { it[Keys.AUTO] = on }

    suspend fun setBeta(on: Boolean) = context.updateStore.edit { it[Keys.BETA] = on }

    suspend fun markChecked(nowMs: Long) = context.updateStore.edit { it[Keys.LAST_CHECK] = nowMs }

    suspend fun markNotified(version: String) = context.updateStore.edit { it[Keys.NOTIFIED] = version }

    suspend fun setWatchVersion(version: String) = context.updateStore.edit { it[Keys.WATCH] = version }

    /**
     * Records that this version has started, and returns the version that ran before it, or
     * null on the first launch after a fresh install (nothing to announce then).
     */
    suspend fun claimVersionChange(): String? {
        var previous: String? = null
        context.updateStore.edit {
            val seen = it[Keys.LAST_SEEN]
            previous = seen?.takeIf { s -> s != installedVersion }
            it[Keys.LAST_SEEN] = installedVersion
        }
        return previous
    }
}
