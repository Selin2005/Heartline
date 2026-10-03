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
import com.heartline.shared.update.AppVersion.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.updateStore by preferencesDataStore("updates")

/**
 * Update preferences, what was last announced, and the version the watch reported.
 *
 * The update track is sticky: it starts at the channel of the installed build and only goes up by
 * itself (installing a dev build puts you on the dev track; updating from dev to a beta or stable
 * release keeps you there). The user can pick another track in Settings → Updates.
 */
class UpdateRepository(private val context: Context, private val installedVersion: String) {
    data class Prefs(
        val autoCheck: Boolean = true,
        /** Which releases are offered: stable → stable; beta → beta + stable; dev → everything. */
        val track: Channel = Channel.STABLE,
        val lastCheckMs: Long = 0,
        val notifiedVersion: String? = null,
        val lastSeenVersion: String? = null,
        val watchVersion: String? = null,
        /** A downloaded and verified update waiting to be installed. */
        val ready: Ready? = null,
    ) {
        val receivesBetas: Boolean get() = track != Channel.STABLE
    }

    /** A verified phone APK in `files/updates` ([file] is its name there) and its SHA-256. */
    data class Ready(val version: String, val file: String, val sha256: String)

    private object Keys {
        val AUTO = booleanPreferencesKey("auto_check")
        val LEGACY_BETA = booleanPreferencesKey("beta")
        val TRACK = stringPreferencesKey("track")
        val HIGHEST = stringPreferencesKey("highest_installed_channel")
        val LAST_CHECK = longPreferencesKey("last_check")
        val NOTIFIED = stringPreferencesKey("notified_version")
        val LAST_SEEN = stringPreferencesKey("last_seen_version")
        val WATCH = stringPreferencesKey("watch_version")
        val READY_VERSION = stringPreferencesKey("ready_version")
        val READY_FILE = stringPreferencesKey("ready_file")
        val READY_SHA = stringPreferencesKey("ready_sha256")
    }

    private val installedChannel = AppVersion.parse(installedVersion)?.channel ?: Channel.STABLE

    private fun channel(name: String?) = Channel.entries.firstOrNull { it.name == name }

    val prefs: Flow<Prefs> = context.updateStore.data.map {
        val highest = maxOf(channel(it[Keys.HIGHEST]) ?: Channel.STABLE, installedChannel)
        val legacy = if (it[Keys.LEGACY_BETA] == true) maxOf(highest, Channel.BETA) else highest
        Prefs(
            autoCheck = it[Keys.AUTO] ?: true,
            track = channel(it[Keys.TRACK]) ?: legacy,
            lastCheckMs = it[Keys.LAST_CHECK] ?: 0,
            notifiedVersion = it[Keys.NOTIFIED],
            lastSeenVersion = it[Keys.LAST_SEEN],
            watchVersion = it[Keys.WATCH],
            ready = it[Keys.READY_VERSION]?.let { v -> Ready(v, it[Keys.READY_FILE] ?: return@let null, it[Keys.READY_SHA] ?: return@let null) },
        )
    }

    suspend fun current() = prefs.first()

    suspend fun setAutoCheck(on: Boolean) = context.updateStore.edit { it[Keys.AUTO] = on }

    suspend fun setTrack(track: Channel) = context.updateStore.edit { it[Keys.TRACK] = track.name }

    /**
     * Called on every start. Installing a build of a higher channel than any before (a beta or dev
     * build installed by hand) moves the track up to it, replacing an earlier choice.
     */
    suspend fun recordInstalled() = context.updateStore.edit {
        val highest = channel(it[Keys.HIGHEST]) ?: Channel.STABLE
        if (installedChannel > highest) {
            it[Keys.HIGHEST] = installedChannel.name
            it.remove(Keys.TRACK)
        } else if (it[Keys.HIGHEST] == null) {
            it[Keys.HIGHEST] = highest.name
        }
    }

    suspend fun markChecked(nowMs: Long) = context.updateStore.edit { it[Keys.LAST_CHECK] = nowMs }

    suspend fun markNotified(version: String) = context.updateStore.edit { it[Keys.NOTIFIED] = version }

    suspend fun setReady(ready: Ready) = context.updateStore.edit {
        it[Keys.READY_VERSION] = ready.version
        it[Keys.READY_FILE] = ready.file
        it[Keys.READY_SHA] = ready.sha256
    }

    suspend fun clearReady() = context.updateStore.edit {
        it.remove(Keys.READY_VERSION)
        it.remove(Keys.READY_FILE)
        it.remove(Keys.READY_SHA)
    }

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
