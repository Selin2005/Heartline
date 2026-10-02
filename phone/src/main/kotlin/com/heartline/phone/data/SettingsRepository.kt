// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.heartline.shared.AppInfo
import com.heartline.shared.hr.HeartLimits
import com.heartline.shared.vitals.VitalsLimits
import com.heartline.shared.hr.MonitorSettings
import com.heartline.shared.sync.Protocol
import kotlinx.serialization.encodeToString
import com.heartline.shared.profile.ReportName
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore("settings")

/** Monitoring settings (source of truth on the phone; mirrored to the watch). */
class SettingsRepository(private val context: Context, private val now: () -> Long = System::currentTimeMillis) {
    private object Keys {
        val IRN = booleanPreferencesKey("irn")
        val HR_ALERTS = booleanPreferencesKey("hr_alerts")
        val HIGH = intPreferencesKey("high_bpm")
        val LOW = intPreferencesKey("low_bpm")
        val ONBOARDED = booleanPreferencesKey("onboarded")
        val DEMO_SEEDED = booleanPreferencesKey("demo_seeded")
        val DEMO_PURGED = booleanPreferencesKey("demo_purged")
        val MONITOR_JSON = stringPreferencesKey("monitor_json")
        val AI_CONSENT = booleanPreferencesKey("ai_consent")
        val AI_PROMPT = stringPreferencesKey("ai_prompt")
        val AI_ATTACH_PDF = booleanPreferencesKey("ai_attach_pdf")
        val REPORT_NAME = stringPreferencesKey("report_name")
        val TERMS_VERSION = intPreferencesKey("accepted_terms_version")
        val HEART_LIMITS = stringPreferencesKey("heart_limits")
        val VITALS_LIMITS = stringPreferencesKey("vitals_limits")
    }

    /**
     * Earlier debug builds always seeded demo data, including a synthetic BP calibration that made
     * every watch reading come out the same. Returns true once, for installs that were seeded.
     */
    suspend fun claimDemoPurge(): Boolean {
        var purge = false
        context.settingsStore.edit {
            purge = it[Keys.DEMO_SEEDED] == true && it[Keys.DEMO_PURGED] != true
            it[Keys.DEMO_PURGED] = true
        }
        return purge
    }

    /** Debug builds seed demo data once; after "Delete all data" it must not come back. */
    suspend fun claimDemoSeed(): Boolean {
        var first = false
        context.settingsStore.edit {
            first = it[Keys.DEMO_SEEDED] != true
            it[Keys.DEMO_SEEDED] = true
        }
        return first
    }

    /** Phone-only sharing preferences: "Share with AI", and the name printed on exports (nickname by default). */
    data class SharingPrefs(
        val consent: Boolean = false,
        val prompt: String? = null,
        val attachPdf: Boolean = true,
        val reportName: ReportName = ReportName.PREFERRED_NAME,
    )

    val sharing: Flow<SharingPrefs> = context.settingsStore.data.map {
        SharingPrefs(
            it[Keys.AI_CONSENT] ?: false,
            it[Keys.AI_PROMPT],
            it[Keys.AI_ATTACH_PDF] ?: true,
            ReportName.entries.firstOrNull { name -> name.name == it[Keys.REPORT_NAME] } ?: ReportName.PREFERRED_NAME,
        )
    }

    suspend fun setReportName(choice: ReportName) {
        context.settingsStore.edit { it[Keys.REPORT_NAME] = choice.name }
    }

    suspend fun setAiConsent() {
        context.settingsStore.edit { it[Keys.AI_CONSENT] = true }
    }

    suspend fun setAiPrompt(prompt: String?) {
        context.settingsStore.edit { if (prompt.isNullOrBlank()) it.remove(Keys.AI_PROMPT) else it[Keys.AI_PROMPT] = prompt }
    }

    suspend fun setAiAttachPdf(on: Boolean) {
        context.settingsStore.edit { it[Keys.AI_ATTACH_PDF] = on }
    }

    val onboarded: Flow<Boolean> = context.settingsStore.data.map { it[Keys.ONBOARDED] ?: false }

    suspend fun setOnboarded() {
        context.settingsStore.edit { it[Keys.ONBOARDED] = true }
    }

    /** Version of the Terms of Use / Privacy Policy the user accepted; 0 = never. */
    val acceptedTermsVersion: Flow<Int> = context.settingsStore.data.map { it[Keys.TERMS_VERSION] ?: 0 }

    /** The current terms ([AppInfo.TERMS_VERSION]) are accepted. */
    val termsAccepted: Flow<Boolean> = acceptedTermsVersion.map { it >= AppInfo.TERMS_VERSION }

    suspend fun acceptTerms(version: Int = AppInfo.TERMS_VERSION) {
        context.settingsStore.edit { it[Keys.TERMS_VERSION] = version }
    }

    /**
     * All app and watch settings, stored as one JSON document. Installs from before the full
     * settings model still have the four legacy keys; they seed the first value.
     */
    val monitor: Flow<MonitorSettings> = context.settingsStore.data.map { p ->
        p[Keys.MONITOR_JSON]?.let { runCatching { Protocol.json.decodeFromString<MonitorSettings>(it) }.getOrNull() }
            ?: MonitorSettings().let { d ->
                d.copy(
                    irregularRhythmEnabled = p[Keys.IRN] ?: d.irregularRhythmEnabled,
                    heartRateAlertsEnabled = p[Keys.HR_ALERTS] ?: d.heartRateAlertsEnabled,
                    highBpm = p[Keys.HIGH] ?: d.highBpm,
                    lowBpm = p[Keys.LOW] ?: d.lowBpm,
                )
            }
    }

    suspend fun current() = monitor.first()

    /** The personal heart-rate limits the watch uses now (sent with its heart-rate batches). */
    val heartLimits: Flow<HeartLimits?> = context.settingsStore.data.map { p ->
        p[Keys.HEART_LIMITS]?.let { runCatching { Protocol.json.decodeFromString<HeartLimits>(it) }.getOrNull() }
    }

    /** Blood-oxygen and temperature normals and limits from the watch. */
    val vitalsLimits: Flow<VitalsLimits?> = context.settingsStore.data.map { p ->
        p[Keys.VITALS_LIMITS]?.let { runCatching { Protocol.json.decodeFromString<VitalsLimits>(it) }.getOrNull() }
    }

    suspend fun saveVitalsLimits(limits: VitalsLimits) {
        context.settingsStore.edit { it[Keys.VITALS_LIMITS] = Protocol.json.encodeToString(limits) }
    }

    suspend fun saveHeartLimits(limits: HeartLimits) {
        context.settingsStore.edit { it[Keys.HEART_LIMITS] = Protocol.json.encodeToString(limits) }
    }

    /** A change made on this phone: stamped now, so it wins over older copies on the watch. */
    suspend fun update(transform: (MonitorSettings) -> MonitorSettings): MonitorSettings {
        val next = transform(current()).copy(updatedAtMs = now())
        save(next)
        return next
    }

    /** A copy from the watch: kept only if it is newer. @return true when it replaced ours. */
    suspend fun applyRemote(incoming: MonitorSettings): Boolean {
        if (!incoming.isNewerThan(current())) return false
        save(incoming)
        return true
    }

    private suspend fun save(settings: MonitorSettings) {
        context.settingsStore.edit { it[Keys.MONITOR_JSON] = Protocol.json.encodeToString(settings) }
    }
}
