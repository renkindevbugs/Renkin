package dev.renkinProject.renkin.data.transfer

import dev.renkinProject.renkin.icon.creator.StoredIconModifierInput
import dev.renkinProject.renkin.icon.creator.StoredIconModifierRecipe
import org.json.JSONObject

internal enum class BackupJsonNode {
    FULL, ROOT, PROFILES, PROFILE, ICONS, ICON;

    fun child(name: String): BackupJsonNode = when {
        this == ROOT && name == "profiles" -> PROFILES
        this == PROFILE && name == "icons" -> ICONS
        else -> FULL
    }

    fun element(): BackupJsonNode = when (this) {
        PROFILES -> PROFILE
        ICONS -> ICON
        else -> FULL
    }

    // These are decodeJson's consumed fields. Unknown fields still receive strict JSON validation.
    fun retains(name: String): Boolean = when (this) {
        ROOT -> name in ROOT_FIELDS
        PROFILE -> name in PROFILE_FIELDS
        ICON -> name in ICON_FIELDS
        else -> true
    }

    fun finish(value: JSONObject) {
        if (this != ICON) return
        if (value.has("modifierRecipe")) value.put("modifierRecipe",
            StoredIconModifierRecipe.normalize(value.optString("modifierRecipe")).orEmpty())
        if (value.has("modifierInput")) value.put("modifierInput",
            StoredIconModifierInput.normalize(value.optString("modifierInput")).orEmpty())
    }

    companion object {
        private val ROOT_FIELDS = setOf("prefs", "profiles", "packs", "colorPresets", "modifierPresets")
        private val PROFILE_FIELDS = setOf("id", "name", "description", "packLabel", "prefsSnapshot",
            "hasUnbuiltChanges", "hideMissingPackWarning", "profileStyle", "appTargets", "customPackageName", "packIcon", "icons", "watchRules")
        private val ICON_FIELDS = setOf("packageName", "activityName", "isAdaptiveIcon", "isXml", "drawable",
            "calendarEnabled", "calendarPrefix", "calendarPackName", "sourcePackName", "sourceDrawableName",
            "isCustomIcon", "isLegacyIcon", "baseDrawable", "baseIsAdaptiveIcon", "baseIsXml", "sourceUrl",
            "isFallbackIcon", "modifierRecipe", "modifierInput")
    }
}
