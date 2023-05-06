package com.kaanelloed.iconeration

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager

class PreferencesHelper(private val ctx: Context) {
    private val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)

    fun getIconColor(): Int {
        return prefs.getInt(
            ctx.getString(R.string.settings_iconColor_key),
            ContextCompat.getColor(ctx, R.color.settings_iconColor_def_value)
        )
    }

    fun colorIconSet(): Boolean {
        return prefs.contains(ctx.getString(R.string.settings_iconColor_key))
    }

    fun getIconColorForTheme(): Int {
        val defaultColor = when (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) {
            Configuration.UI_MODE_NIGHT_NO -> Color.BLACK
            Configuration.UI_MODE_NIGHT_YES -> Color.WHITE
            else -> Color.WHITE
        }

        return prefs.getInt(
            ctx.getString(R.string.settings_iconColor_key),
            defaultColor
        )
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
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
    }

    fun getGenType(): IconGenerator.GenerationType {
        val prefValue = prefs.getString(
            ctx.getString(R.string.settings_genType_key),
            ctx.getString(R.string.settings_genType_def_value)
        )!!

        return when (prefValue) {
            "EDGE_DETECTION" -> IconGenerator.GenerationType.EdgeDetection
            "ARCTICONS_FIRST_LETTER" -> IconGenerator.GenerationType.FirstLetter
            "ARCTICONS_TWO_LETTERS" -> IconGenerator.GenerationType.TwoLetters
            "ARCTICONS_APP_NAME" -> IconGenerator.GenerationType.AppName
            else -> IconGenerator.GenerationType.EdgeDetection
        }
    }

    fun getIncludeAvailableIcon(): Boolean {
        return prefs.getBoolean(
            ctx.getString(R.string.settings_includeAvailable_key),
            ctx.getString(R.string.settings_includeAvailable_def_value).toBoolean()
        )
    }

    fun getApplyColorAvailableIcon(): Boolean {
        return prefs.getBoolean(
            ctx.getString(R.string.settings_applyColorAvailable_key),
            ctx.getString(R.string.settings_applyColorAvailable_def_value).toBoolean()
        )
    }
}