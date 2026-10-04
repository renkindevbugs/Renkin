package dev.renkinProject.renkin.data.transfer

import dev.renkinProject.renkin.data.ActiveProfileIdKey
import dev.renkinProject.renkin.data.DEFAULT_PROFILE_ID
import dev.renkinProject.renkin.data.ProfilePrefKeys
import dev.renkinProject.renkin.data.isValidPersistedPreference
import dev.renkinProject.renkin.data.watch.AppComponent
import dev.renkinProject.renkin.icon.creator.ColorizerStyle
import dev.renkinProject.renkin.icon.creator.MODIFIER_PRESET_SCHEMA_VERSION
import dev.renkinProject.renkin.icon.creator.decodeColorizerStyle
import dev.renkinProject.renkin.icon.creator.decodeModifierPreset
import dev.renkinProject.renkin.util.Log
import java.io.IOException

internal object BackupValidator {
    fun prepare(data: BackupData, kind: BackupManager.ImportKind): BackupData {
        val prepared = data.copy(profiles = data.profiles.map { profile ->
            profile.copy(watchRules = profile.watchRules.mapNotNull(::cleanRule))
        })
        if (prepared != data) logDiscardedWatchData(data, prepared)
        validate(prepared, kind)
        return prepared
    }

    private fun logDiscardedWatchData(before: BackupData, after: BackupData) {
        val oldRules = before.profiles.flatMap { it.watchRules }
        val newRules = after.profiles.flatMap { it.watchRules }
        fun discarded(count: (BackupWatchRule) -> Int): Int = oldRules.sumOf(count) - newRules.sumOf(count)
        Log.info("BackupValidator", "Normalized watch data; discarded " +
            "rules=${discarded { 1 }}, completedRules=${discarded { if (it.completed) 1 else 0 }}, " +
            "apps=${discarded { it.apps.size }}, packs=${discarded { it.packs.size }}, " +
            "baselines=${discarded { it.baselines.size }}, suggestions=${discarded { it.suggestions.size }}, " +
            "candidates=${discarded { rule -> rule.suggestions.sumOf { it.candidates.size } }}")
    }

    private fun cleanRule(rule: BackupWatchRule): BackupWatchRule? {
        val apps = rule.apps.filter(::validComponent).distinct()
        val appSet = apps.toSet()
        val packs = rule.packs.filter { it.isNotBlank() }.distinct()
        val packSet = packs.toSet()
        val baselines = if (rule.completed) emptyList() else rule.baselines.filter {
            AppComponent(it.packageName, it.activityName) in appSet && it.iconPackPackage.isNotBlank() &&
                (rule.watchAllPacks || it.iconPackPackage in packSet)
        }.sortedByDescending { it.lastCheckedAt }
            .distinctBy { Triple(it.packageName, it.activityName, it.iconPackPackage) }
        val suggestions = rule.suggestions.filter {
            AppComponent(it.packageName, it.activityName) in appSet
        }.mapNotNull { suggestion ->
            val candidates = suggestion.candidates.filter {
                it.iconPackPackage.isNotBlank() && it.drawableName.isNotBlank() && it.iconHash.isNotBlank() &&
                    (rule.watchAllPacks || it.iconPackPackage in packSet)
            }.distinctBy { it.iconPackPackage }
            suggestion.copy(candidates = candidates).takeIf { candidates.isNotEmpty() }
        }.sortedByDescending { it.createdAt }.distinctBy { AppComponent(it.packageName, it.activityName) }
        return rule.copy(apps = apps, packs = packs, baselines = baselines, suggestions = suggestions)
            .takeUnless { it.completed && suggestions.isEmpty() }
    }

    fun validate(data: BackupData, kind: BackupManager.ImportKind) {
        for ((name, pref) in data.prefs) {
            requireValid(isValidPersistedPreference(name, pref.value), "Invalid preference type")
        }
        val ids = data.profiles.map { it.profile.id }
        requireValid(ids.isNotEmpty() && ids.all { it > 0 } && ids.distinct().size == ids.size, "Invalid profile identities")
        if (kind == BackupManager.ImportKind.PROFILE) {
            requireValid(ids.size == 1, "Shared file must contain exactly one profile")
        } else {
            requireValid(DEFAULT_PROFILE_ID in ids, "Backup has no default profile")
            data.prefs[ActiveProfileIdKey.name]?.let {
                requireValid(it.tag == BackupPref.LONG && it.value is Long && it.value in ids, "Invalid active profile")
            }
        }
        for (profile in data.profiles) {
            requireValid(profile.profile.name.isNotBlank(), "Profile has no name")
            if (profile.profile.prefsSnapshot.isNotBlank()) {
                val snapshot = parseBackupJson(profile.profile.prefsSnapshot)
                for (key in ProfilePrefKeys) {
                    if (snapshot.has(key.name)) {
                        requireValid(isValidPersistedPreference(key.name, snapshot.get(key.name)), "Invalid profile preference type")
                    }
                }
            }
            val components = profile.icons.map { AppComponent(it.packageName, it.activityName) }
            requireValid(components.distinct().size == components.size, "Duplicate profile icon")
            requireValid(components.all(::validComponent), "Invalid icon component")
            requireValid(profile.icons.all { it.profileId == profile.profile.id }, "Icon belongs to another profile")
            profile.watchRules.forEach(::validateRule)
        }
        for (preset in data.colorPresets) {
            requireValid(validStyle(decodeColorizerStyle(preset.style)), "Invalid saved colour style")
        }
        for (preset in data.modifierPresets) {
            val payload = decodeModifierPreset(preset.payload)
            requireValid(
                preset.schemaVersion in 1..MODIFIER_PRESET_SCHEMA_VERSION &&
                    payload != null && payload.schemaVersion == preset.schemaVersion,
                "Invalid saved modifier recipe"
            )
            requireValid(listOfNotNull(
                payload?.effect?.colorizerStyle, payload?.shape?.style,
                payload?.outline?.style, payload?.shadow?.style
            ).all(::validStyle), "Invalid modifier colour style")
        }
    }

    private fun validateRule(rule: BackupWatchRule) {
        val apps = rule.apps.toSet()
        val packs = rule.packs.toSet()
        requireValid(rule.apps.all(::validComponent) && apps.size == rule.apps.size, "Invalid watched components")
        requireValid(rule.packs.all { it.isNotBlank() } && packs.size == rule.packs.size, "Invalid watched packs")
        val states = rule.baselines.map { Triple(it.packageName, it.activityName, it.iconPackPackage) }
        requireValid(states.distinct().size == states.size, "Duplicate watch baseline")
        requireValid(rule.baselines.all {
            AppComponent(it.packageName, it.activityName) in apps && it.iconPackPackage.isNotBlank() &&
                (rule.watchAllPacks || it.iconPackPackage in packs)
        }, "Invalid watch baseline owner")
        val suggestions = rule.suggestions.map { AppComponent(it.packageName, it.activityName) }
        requireValid(suggestions.distinct().size == suggestions.size && suggestions.all { it in apps }, "Invalid watch suggestion owner")
        for (suggestion in rule.suggestions) {
            val candidatePacks = suggestion.candidates.map { it.iconPackPackage }
            requireValid(candidatePacks.distinct().size == candidatePacks.size && suggestion.candidates.all {
                it.iconPackPackage.isNotBlank() && it.drawableName.isNotBlank() && it.iconHash.isNotBlank()
            }, "Invalid watch candidates")
        }
    }

    private fun validComponent(component: AppComponent): Boolean =
        component.packageName.isNotBlank() && component.activityName.isNotBlank()

    private fun validStyle(style: ColorizerStyle?): Boolean =
        style != null && style.gradientAngle.isFinite() && style.gradientPositions.all { it.isFinite() }

    private fun requireValid(valid: Boolean, message: String) {
        if (!valid) throw IOException(message)
    }
}
