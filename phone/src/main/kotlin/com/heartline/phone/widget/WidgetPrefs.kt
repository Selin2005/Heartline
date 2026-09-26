// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.widget

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.heartline.shared.model.Metric
import kotlinx.coroutines.flow.first

/** WALLPAPER: One UI / Material You colours taken from the wallpaper (Android 12+), light or dark with the phone. */
enum class WidgetTheme { SYSTEM, LIGHT, DARK, WALLPAPER }

/**
 * Per-widget style chosen on the configure screen (One UI widgets offer the same settings).
 * [metric]: what a single-metric tile shows or measures (null: the widget's default).
 */
data class WidgetStyle(val opacity: Int = 100, val theme: WidgetTheme = WidgetTheme.SYSTEM, val metric: Metric? = null)

private val Context.widgetStore by preferencesDataStore("widgets")

object WidgetPrefs {
    private fun opacityKey(id: Int) = intPreferencesKey("opacity_$id")

    private fun themeKey(id: Int) = stringPreferencesKey("theme_$id")

    private fun metricKey(id: Int) = stringPreferencesKey("metric_$id")

    suspend fun load(context: Context, appWidgetId: Int): WidgetStyle {
        val prefs = context.widgetStore.data.first()
        return WidgetStyle(
            opacity = prefs[opacityKey(appWidgetId)] ?: 100,
            theme = WidgetTheme.entries.firstOrNull { it.name == prefs[themeKey(appWidgetId)] } ?: WidgetTheme.SYSTEM,
            metric = Metric.entries.firstOrNull { it.name == prefs[metricKey(appWidgetId)] },
        )
    }

    suspend fun save(context: Context, appWidgetId: Int, style: WidgetStyle) {
        context.widgetStore.edit {
            it[opacityKey(appWidgetId)] = style.opacity.coerceIn(0, 100)
            it[themeKey(appWidgetId)] = style.theme.name
            style.metric?.let { m -> it[metricKey(appWidgetId)] = m.name }
        }
    }

    suspend fun delete(context: Context, appWidgetIds: IntArray) {
        context.widgetStore.edit { prefs ->
            appWidgetIds.forEach {
                prefs.remove(opacityKey(it))
                prefs.remove(themeKey(it))
                prefs.remove(metricKey(it))
            }
        }
    }
}
