package dev.renkinProject.renkin.feature.language

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import java.util.Locale

/**
 * The in-app language choice. An empty tag means "follow the device language".
 *
 * Android 13+ stores the choice itself, recreates running activities and shows it in the
 * system's per-app language settings. Older versions only apply a locale when a context is
 * created: [wrap] runs from every `attachBaseContext`, [select] updates the shared application
 * resources in place, and the caller recreates its activity.
 */
object AppLocales {
    private const val PREFS_NAME = "app_locale"
    private const val KEY_TAG = "tag"

    // Read once from the application's attachBaseContext so activities never touch disk there.
    @Volatile
    private var legacyTag: String = ""

    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.TIRAMISU)
    val handledBySystem: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    fun selectedTag(context: Context): String =
        if (handledBySystem) context.localeManager().applicationLocales.toLanguageTags() else legacyTag

    fun select(context: Context, tag: String) {
        if (handledBySystem) {
            context.localeManager().applicationLocales = LocaleList.forLanguageTags(tag)
            return
        }
        legacyTag = tag
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit { putString(KEY_TAG, tag) }
        updateApplicationResources(context.applicationContext.resources)
    }

    fun loadForApplication(base: Context): Context {
        if (!handledBySystem) {
            legacyTag = base.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_TAG, "").orEmpty()
        }
        return wrap(base)
    }

    fun wrap(base: Context): Context {
        if (handledBySystem || legacyTag.isEmpty()) return base
        val locale = Locale.forLanguageTag(legacyTag)
        Locale.setDefault(locale)
        val configuration = Configuration(base.resources.configuration).apply { setLocale(locale) }
        return base.createConfigurationContext(configuration)
    }

    // Before Android 13 the application context cannot be recreated, so its strings
    // (notifications, toasts) only follow the choice when its Resources change in place.
    @Suppress("DEPRECATION")
    private fun updateApplicationResources(resources: Resources) {
        val locale = if (legacyTag.isEmpty()) {
            Resources.getSystem().configuration.locale
        } else {
            Locale.forLanguageTag(legacyTag)
        }
        Locale.setDefault(locale)
        val configuration = Configuration(resources.configuration).apply { setLocale(locale) }
        resources.updateConfiguration(configuration, resources.displayMetrics)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun Context.localeManager(): LocaleManager = getSystemService(LocaleManager::class.java)
}
