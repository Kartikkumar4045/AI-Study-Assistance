package com.kartik.aistudyassistant.data.local

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

object AppSettingsPrefs {

    private const val PREF_FILE = "app_settings"
    private const val KEY_SENSOR_ENABLED = "sensor_enabled"
    private const val KEY_DARK_THEME_ENABLED = "dark_theme_enabled"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    fun isSensorEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_SENSOR_ENABLED, false)
    }

    fun setSensorEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SENSOR_ENABLED, enabled).apply()
    }

    fun isDarkThemeEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_DARK_THEME_ENABLED, false)
    }

    fun setDarkThemeEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DARK_THEME_ENABLED, enabled).apply()
    }

    fun applyTheme(context: Context) {
        val mode = if (isDarkThemeEnabled(context)) {
            AppCompatDelegate.MODE_NIGHT_YES
        } else {
            AppCompatDelegate.MODE_NIGHT_NO
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }
}

