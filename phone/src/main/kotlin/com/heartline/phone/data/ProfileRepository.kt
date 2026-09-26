// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.heartline.shared.model.RecordMeta
import com.heartline.shared.model.RecordSummary
import com.heartline.shared.profile.UserProfile
import com.heartline.shared.sync.PhoneSyncEngine
import com.heartline.shared.sync.Protocol
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString

private val Context.profileStore by preferencesDataStore("profile")

/** The user's profile; needed by body composition on the watch. */
class ProfileRepository(private val context: Context, private val sync: () -> PhoneSyncEngine) {
    private val key = stringPreferencesKey("profile")

    val profile: Flow<UserProfile?> = context.profileStore.data.map { p ->
        p[key]?.let { runCatching { Protocol.json.decodeFromString<UserProfile>(it) }.getOrNull() }
    }

    suspend fun save(profile: UserProfile) {
        context.profileStore.edit { it[key] = Protocol.json.encodeToString(profile) }
        sync().sendProfile(profile)
    }

    /**
     * A body composition result carries the weight entered on the watch: when it is newer than
     * the profile's, it becomes the profile weight (and goes back to the watch with the profile).
     */
    suspend fun onRecordSaved(meta: RecordMeta) {
        val body = meta.summary as? RecordSummary.BodyComposition ?: return
        val weight = body.weightKg?.takeIf { it > 0 } ?: return
        val current = profile.first() ?: return
        if ((current.weightUpdatedAtMs ?: 0L) >= meta.startedAtMs || current.weightKg == weight) return
        save(current.copy(weightKg = weight, weightUpdatedAtMs = meta.startedAtMs))
    }

    suspend fun resend() {
        profile.first()?.let { sync().sendProfile(it) }
    }
}
