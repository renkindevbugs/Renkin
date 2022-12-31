package com.kaanelloed.iconeration

import android.content.Context
import android.graphics.Color
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.PreferenceManager

class PreferencesHelper(private val ctx: Context) {
    private val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)

    fun getIconColor(): Int {
        val prefValue = prefs.getString(
            ctx.getString(R.string.settings_iconColor_key),
            ctx.getString(R.string.settings_iconColor_def_value)
        )!!

        return Color.parseColor(prefValue)
    }

    fun getNightMode(): Int {
        val prefValue = prefs.getString(
            ctx.getString(R.string.settings_darkMode_key),
            ctx.getString(R.string.settings_darkMode_def_value)
        )!!

        return when (prefValue) {
            "MODE_NIGHT_FOLLOW_SYSTEM" -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            "MODE_NIGHT_NO" -> AppCompatDelegate.MODE_NIGHT_NO
            "MODE_NIGHT_YES" -> AppCompatDelegate.MODE_NIGHT_YES
            "MODE_NIGHT_AUTO_BATTERY" -> AppCompatDelegate.MODE_NIGHT_AUTO_BATTERY
            else -> 0
        }
    }

    fun getIncludeAvailableIcon(): Boolean {
        return prefs.getBoolean(
            ctx.getString(R.string.settings_includeAvailable_key),
            ctx.getString(R.string.settings_includeAvailable_def_value).toBoolean()
        )
    }
}