package com.heartline.phone.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.heartline.shared.hr.MonitorSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore("settings")

/** Monitoring settings (source of truth on the phone; mirrored to the watch). */
class SettingsRepository(private val context: Context) {
    private object Keys {
        val IRN = booleanPreferencesKey("irn")
        val HR_ALERTS = booleanPreferencesKey("hr_alerts")
        val HIGH = intPreferencesKey("high_bpm")
        val LOW = intPreferencesKey("low_bpm")
        val ONBOARDED = booleanPreferencesKey("onboarded")
        val DEMO_SEEDED = booleanPreferencesKey("demo_seeded")
        val DEMO_PURGED = booleanPreferencesKey("demo_purged")
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

    val onboarded: Flow<Boolean> = context.settingsStore.data.map { it[Keys.ONBOARDED] ?: false }

    suspend fun setOnboarded() {
        context.settingsStore.edit { it[Keys.ONBOARDED] = true }
    }

    val monitor: Flow<MonitorSettings> = context.settingsStore.data.map { p ->
        val defaults = MonitorSettings()
        MonitorSettings(
            irregularRhythmEnabled = p[Keys.IRN] ?: defaults.irregularRhythmEnabled,
            heartRateAlertsEnabled = p[Keys.HR_ALERTS] ?: defaults.heartRateAlertsEnabled,
            highBpm = p[Keys.HIGH] ?: defaults.highBpm,
            lowBpm = p[Keys.LOW] ?: defaults.lowBpm,
        )
    }

    suspend fun current() = monitor.first()

    suspend fun update(transform: (MonitorSettings) -> MonitorSettings): MonitorSettings {
        val next = transform(current())
        context.settingsStore.edit {
            it[Keys.IRN] = next.irregularRhythmEnabled
            it[Keys.HR_ALERTS] = next.heartRateAlertsEnabled
            it[Keys.HIGH] = next.highBpm
            it[Keys.LOW] = next.lowBpm
        }
        return next
    }
}
