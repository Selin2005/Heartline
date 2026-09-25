package com.heartline.phone.widget

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

enum class WidgetTheme { SYSTEM, LIGHT, DARK }

/** Per-widget style chosen on the configure screen (One UI widgets offer the same two settings). */
data class WidgetStyle(val opacity: Int = 100, val theme: WidgetTheme = WidgetTheme.SYSTEM)

private val Context.widgetStore by preferencesDataStore("widgets")

object WidgetPrefs {
    private fun opacityKey(id: Int) = intPreferencesKey("opacity_$id")

    private fun themeKey(id: Int) = stringPreferencesKey("theme_$id")

    suspend fun load(context: Context, appWidgetId: Int): WidgetStyle {
        val prefs = context.widgetStore.data.first()
        return WidgetStyle(
            opacity = prefs[opacityKey(appWidgetId)] ?: 100,
            theme = WidgetTheme.entries.firstOrNull { it.name == prefs[themeKey(appWidgetId)] } ?: WidgetTheme.SYSTEM,
        )
    }

    suspend fun save(context: Context, appWidgetId: Int, style: WidgetStyle) {
        context.widgetStore.edit {
            it[opacityKey(appWidgetId)] = style.opacity.coerceIn(0, 100)
            it[themeKey(appWidgetId)] = style.theme.name
        }
    }

    suspend fun delete(context: Context, appWidgetIds: IntArray) {
        context.widgetStore.edit { prefs ->
            appWidgetIds.forEach {
                prefs.remove(opacityKey(it))
                prefs.remove(themeKey(it))
            }
        }
    }
}
