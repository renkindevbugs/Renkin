package dev.renkinProject.renkin.data.transfer

import dev.renkinProject.renkin.data.AppTargetCodec
import dev.renkinProject.renkin.data.DbApplication
import dev.renkinProject.renkin.data.Profile
import dev.renkinProject.renkin.data.ProfilePackIconCodec
import dev.renkinProject.renkin.data.watch.AppComponent
import dev.renkinProject.renkin.icon.creator.StoredIconModifierInput
import dev.renkinProject.renkin.icon.creator.StoredIconModifierRecipe
import org.json.JSONArray
import org.json.JSONObject

/**
 * The device-independent payload of a `.renkin` backup file (its `data.json` entry):
 * every profile with its stored icons and watch rules, plus the DataStore preferences.
 * Icon image data stays exactly as persisted (base64 in [DbApplication.drawable]), so a
 * backup needs nothing from the installed icon packs to restore.
 */
data class BackupData(
    val profiles: List<BackupProfile>,
    val prefs: Map<String, BackupPref>,
    // Human-readable names of the packs referenced by any icon, captured on the exporting
    // device — the importer can name a missing pack it has never seen.
    val packLabels: Map<String, String> = emptyMap(),
    // The saved colour/gradient library. Not tied to any profile, so it rides the full backup
    // rather than a shared profile — and older archives simply carry none.
    val colorPresets: List<BackupColorPreset> = emptyList(),
    // Modifier recipes are device-wide too. Optional keeps archives from older builds readable.
    val modifierPresets: List<BackupModifierPreset> = emptyList()
)

/** A saved colour. Ids are not carried: import inserts fresh rows. */
data class BackupColorPreset(
    val name: String,
    val style: String,
    val createdAt: Long
)

data class BackupModifierPreset(
    val name: String,
    val payload: String,
    val schemaVersion: Int,
    val createdAt: Long,
    val lastUsedAt: Long
)

data class BackupProfile(
    val profile: Profile,
    val icons: List<DbApplication>,
    val watchRules: List<BackupWatchRule>
)

/** One watch rule with its child rows. Rule ids are not carried — import regenerates them. */
data class BackupWatchRule(
    val watchAllPacks: Boolean,
    val completed: Boolean,
    val createdAt: Long,
    val completedAt: Long?,
    val apps: List<AppComponent>,
    val packs: List<String>,
    val baselines: List<BackupWatchState> = emptyList(),
    val suggestions: List<BackupWatchSuggestion> = emptyList()
)

data class BackupWatchState(
    val packageName: String,
    val activityName: String,
    val iconPackPackage: String,
    val lastPackVersionCode: Long,
    val lastIconName: String?,
    val lastIconHash: String?,
    val lastCheckedAt: Long
)

data class BackupWatchSuggestion(
    val packageName: String,
    val activityName: String,
    val createdAt: Long,
    val candidates: List<BackupWatchCandidate>
)

data class BackupWatchCandidate(
    val iconPackPackage: String,
    val drawableName: String,
    val iconHash: String
)

/**
 * One DataStore preference with an explicit type tag, so import recreates the key with the
 * exact type it was written with (Int vs Long etc. — DataStore keys are type-strict).
 */
data class BackupPref(val tag: String, val value: Any) {
    companion object {
        const val BOOL = "bool"
        const val INT = "int"
        const val LONG = "long"
        const val FLOAT = "float"
        const val DOUBLE = "double"
        const val STRING = "string"
        const val STRING_SET = "stringSet"

        /** Wraps a raw DataStore value; null for types a backup can't represent. */
        fun of(value: Any): BackupPref? = when (value) {
            is Boolean -> BackupPref(BOOL, value)
            is Int -> BackupPref(INT, value)
            is Long -> BackupPref(LONG, value)
            is Float -> BackupPref(FLOAT, value)
            is Double -> BackupPref(DOUBLE, value)
            is String -> BackupPref(STRING, value)
            is Set<*> -> BackupPref(STRING_SET, value.filterIsInstance<String>().toSet())
            else -> null
        }
    }
}

/**
 * JSON (de)serialization of [BackupData]. Kept free of Android/file concerns so the
 * round-trip is unit-testable; [BackupManager] owns the surrounding ZIP and stores.
 */
object BackupCodec {
    // Version 4 carries authoritative profile styles alongside their legacy projections.
    const val FORMAT_VERSION = 4
    internal const val LEGACY_FORMAT_VERSION = 3

    fun encode(data: BackupData, formatVersion: Int): String = encodeJson(data, formatVersion).toString()

    internal fun encodeJson(data: BackupData, formatVersion: Int): JSONObject {
        ProfileStyleBackup.requireSupportedFormat(formatVersion)
        val root = JSONObject()

        val prefs = JSONObject()
        for ((name, pref) in data.prefs) {
            val value = if (pref.tag == BackupPref.STRING_SET) {
                JSONArray((pref.value as Set<*>).toList())
            } else pref.value
            prefs.put(name, JSONObject().put("t", pref.tag).put("v", value))
        }
        root.put("prefs", prefs)

        val profiles = JSONArray()
        for (bp in data.profiles) {
            val p = JSONObject()
                .put("id", bp.profile.id)
                .put("name", bp.profile.name)
                .put("description", bp.profile.description)
                .put("packLabel", bp.profile.packLabel)
                .put("customPackageName", bp.profile.customPackageName)
                .put("packIcon", ProfilePackIconCodec.normalize(bp.profile.packIcon))
                .put("prefsSnapshot", bp.profile.prefsSnapshot)
                .put("hasUnbuiltChanges", bp.profile.hasUnbuiltChanges)
                .put("hideMissingPackWarning", bp.profile.hideMissingPackWarning)
                // Optional within format 4: older readers ignore it and keep the icons as orphans.
                .put("appTargets", AppTargetCodec.normalize(bp.profile.appTargets))
            if (formatVersion > LEGACY_FORMAT_VERSION) {
                ProfileStyleBackup.validatedStyle(bp.profile)
                p.put("profileStyle", bp.profile.profileStyle)
            }

            val icons = JSONArray()
            for (icon in bp.icons) icons.put(encodeIcon(icon))
            p.put("icons", icons)

            val rules = JSONArray()
            for (rule in bp.watchRules) {
                val r = JSONObject()
                    .put("watchAllPacks", rule.watchAllPacks)
                    .put("completed", rule.completed)
                    .put("createdAt", rule.createdAt)
                rule.completedAt?.let { r.put("completedAt", it) }
                val apps = JSONArray()
                for (app in rule.apps) {
                    apps.put(JSONObject().put("packageName", app.packageName).put("activityName", app.activityName))
                }
                r.put("apps", apps)
                r.put("packs", JSONArray(rule.packs))
                val baselines = JSONArray()
                for (state in rule.baselines) {
                    baselines.put(
                        JSONObject()
                            .put("packageName", state.packageName)
                            .put("activityName", state.activityName)
                            .put("iconPackPackage", state.iconPackPackage)
                            .put("lastPackVersionCode", state.lastPackVersionCode)
                            .put("lastIconName", state.lastIconName)
                            .put("lastIconHash", state.lastIconHash)
                            .put("lastCheckedAt", state.lastCheckedAt)
                    )
                }
                r.put("baselines", baselines)
                val suggestions = JSONArray()
                for (suggestion in rule.suggestions) {
                    val candidates = JSONArray()
                    for (candidate in suggestion.candidates) {
                        candidates.put(
                            JSONObject()
                                .put("iconPackPackage", candidate.iconPackPackage)
                                .put("drawableName", candidate.drawableName)
                                .put("iconHash", candidate.iconHash)
                        )
                    }
                    suggestions.put(
                        JSONObject()
                            .put("packageName", suggestion.packageName)
                            .put("activityName", suggestion.activityName)
                            .put("createdAt", suggestion.createdAt)
                            .put("candidates", candidates)
                    )
                }
                r.put("suggestions", suggestions)
                rules.put(r)
            }
            p.put("watchRules", rules)

            profiles.put(p)
        }
        root.put("profiles", profiles)

        val packs = JSONObject()
        for ((pack, label) in data.packLabels) packs.put(pack, label)
        root.put("packs", packs)

        val presets = JSONArray()
        for (preset in data.colorPresets) {
            presets.put(
                JSONObject()
                    .put("name", preset.name)
                    .put("style", preset.style)
                    .put("createdAt", preset.createdAt)
            )
        }
        root.put("colorPresets", presets)

        val modifierPresets = JSONArray()
        for (preset in data.modifierPresets) {
            modifierPresets.put(
                JSONObject()
                    .put("name", preset.name)
                    .put("payload", preset.payload)
                    .put("schemaVersion", preset.schemaVersion)
                    .put("createdAt", preset.createdAt)
                    .put("lastUsedAt", preset.lastUsedAt)
            )
        }
        root.put("modifierPresets", modifierPresets)

        return root
    }

    internal fun encodeIcon(icon: DbApplication): JSONObject =
        JSONObject()
            .put("packageName", icon.packageName)
            .put("activityName", icon.activityName)
            .put("isAdaptiveIcon", icon.isAdaptiveIcon)
            .put("isXml", icon.isXml)
            .put("drawable", icon.drawable)
            .put("calendarEnabled", icon.calendarEnabled)
            .put("calendarPrefix", icon.calendarPrefix)
            .put("calendarPackName", icon.calendarPackName)
            .put("sourcePackName", icon.sourcePackName)
            .put("sourceDrawableName", icon.sourceDrawableName)
            .put("isCustomIcon", icon.isCustomIcon)
            .put("isLegacyIcon", icon.isLegacyIcon)
            .put("baseDrawable", icon.baseDrawable)
            .put("baseIsAdaptiveIcon", icon.baseIsAdaptiveIcon)
            .put("baseIsXml", icon.baseIsXml)
            .put("sourceUrl", icon.sourceUrl)
            .put("isFallbackIcon", icon.isFallbackIcon)
            .put("modifierRecipe", StoredIconModifierRecipe.normalize(icon.modifierRecipe).orEmpty())
            .put("modifierInput", StoredIconModifierInput.normalize(icon.modifierInput).orEmpty())

    internal fun decodeIcon(icon: JSONObject, profileId: Long): DbApplication = DbApplication(
        packageName = icon.getString("packageName"),
        activityName = icon.getString("activityName"),
        isAdaptiveIcon = icon.getBoolean("isAdaptiveIcon"),
        isXml = icon.getBoolean("isXml"),
        drawable = icon.getString("drawable"),
        calendarEnabled = icon.optBoolean("calendarEnabled"),
        calendarPrefix = icon.optString("calendarPrefix"),
        calendarPackName = icon.optString("calendarPackName"),
        sourcePackName = icon.optString("sourcePackName"),
        profileId = profileId,
        sourceDrawableName = icon.optString("sourceDrawableName"),
        // The separate legacy flag below protects pre-classification files.
        isCustomIcon = icon.optBoolean("isCustomIcon"),
        isLegacyIcon = if (icon.has("isLegacyIcon")) {
            icon.optBoolean("isLegacyIcon")
        } else {
            // Files created before classification cannot be inferred safely.
            !icon.has("isCustomIcon")
        },
        baseDrawable = icon.optString("baseDrawable", icon.getString("drawable")),
        baseIsAdaptiveIcon = if (icon.has("baseIsAdaptiveIcon")) {
            icon.optBoolean("baseIsAdaptiveIcon")
        } else icon.getBoolean("isAdaptiveIcon"),
        baseIsXml = if (icon.has("baseIsXml")) {
            icon.optBoolean("baseIsXml")
        } else icon.getBoolean("isXml"),
        // Absent in pre-v13 files — no online attribution to carry.
        sourceUrl = icon.optString("sourceUrl"),
        // Absent in pre-v14 files — safe default: not fallback-styled.
        isFallbackIcon = icon.optBoolean("isFallbackIcon"),
        // Optional metadata must never prevent restoration of valid artwork.
        modifierRecipe = StoredIconModifierRecipe.normalize(icon.optString("modifierRecipe")).orEmpty(),
        modifierInput = StoredIconModifierInput.normalize(icon.optString("modifierInput")).orEmpty()
    )


    /** Archive callers pass the manifest version; malformed input is surfaced as a failed import. */
    fun decode(json: String, formatVersion: Int): BackupData = decodeJson(parseBackupJson(json), formatVersion)

    internal fun decodeJson(root: JSONObject, formatVersion: Int): BackupData {
        ProfileStyleBackup.requireSupportedFormat(formatVersion)

        val prefs = mutableMapOf<String, BackupPref>()
        val prefsJson = root.getJSONObject("prefs")
        for (name in prefsJson.keys()) {
            val entry = prefsJson.getJSONObject(name)
            if (formatVersion > LEGACY_FORMAT_VERSION) ProfileStyleBackup.validateRawPreference(name, entry)
            // Unknown tags (a future format extension) are skipped, not fatal.
            val pref = when (entry.getString("t")) {
                BackupPref.BOOL -> BackupPref(BackupPref.BOOL, entry.getBoolean("v"))
                BackupPref.INT -> BackupPref(BackupPref.INT, entry.getInt("v"))
                BackupPref.LONG -> BackupPref(BackupPref.LONG, entry.getLong("v"))
                BackupPref.FLOAT -> BackupPref(BackupPref.FLOAT, entry.getDouble("v").toFloat())
                BackupPref.DOUBLE -> BackupPref(BackupPref.DOUBLE, entry.getDouble("v"))
                BackupPref.STRING -> BackupPref(BackupPref.STRING, entry.getString("v"))
                BackupPref.STRING_SET -> {
                    val arr = entry.getJSONArray("v")
                    BackupPref(BackupPref.STRING_SET, (0 until arr.length()).map { arr.getString(it) }.toSet())
                }
                else -> null
            }
            pref?.let { prefs[name] = it }
        }

        val profiles = mutableListOf<BackupProfile>()
        val profilesJson = root.getJSONArray("profiles")
        for (i in 0 until profilesJson.length()) {
            val p = profilesJson.getJSONObject(i)
            val profileId = p.getLong("id")
            val profile = Profile(
                id = profileId,
                name = p.getString("name"),
                description = p.optString("description"),
                packLabel = p.optString("packLabel"),
                prefsSnapshot = p.optString("prefsSnapshot"),
                hasUnbuiltChanges = p.optBoolean("hasUnbuiltChanges"),
                hideMissingPackWarning = p.optBoolean("hideMissingPackWarning"),
                profileStyle = ProfileStyleBackup.readDocument(p, formatVersion),
                appTargets = AppTargetCodec.normalize(p.optString("appTargets")),
                customPackageName = p.optString("customPackageName"),
                packIcon = ProfilePackIconCodec.normalize(p.optString("packIcon"))
            )

            val icons = mutableListOf<DbApplication>()
            val iconsJson = p.getJSONArray("icons")
            for (j in 0 until iconsJson.length()) {
                val icon = iconsJson.getJSONObject(j)
                icons.add(decodeIcon(icon, profileId))
            }

            val rules = mutableListOf<BackupWatchRule>()
            val rulesJson = p.getJSONArray("watchRules")
            for (j in 0 until rulesJson.length()) {
                val r = rulesJson.getJSONObject(j)
                val apps = mutableListOf<AppComponent>()
                val appsJson = r.getJSONArray("apps")
                for (k in 0 until appsJson.length()) {
                    val a = appsJson.getJSONObject(k)
                    apps.add(AppComponent(a.getString("packageName"), a.getString("activityName")))
                }
                val packsJson = r.getJSONArray("packs")
                val packs = (0 until packsJson.length()).map { packsJson.getString(it) }
                val baselinesJson = r.optionalArray("baselines") ?: JSONArray()
                val baselines = (0 until baselinesJson.length()).map { index ->
                    val state = baselinesJson.getJSONObject(index)
                    BackupWatchState(
                        packageName = state.getString("packageName"),
                        activityName = state.getString("activityName"),
                        iconPackPackage = state.getString("iconPackPackage"),
                        lastPackVersionCode = state.getLong("lastPackVersionCode"),
                        lastIconName = state.optString("lastIconName").takeIf { !state.isNull("lastIconName") },
                        lastIconHash = state.optString("lastIconHash").takeIf { !state.isNull("lastIconHash") },
                        lastCheckedAt = state.getLong("lastCheckedAt")
                    )
                }
                val suggestionsJson = r.optionalArray("suggestions") ?: JSONArray()
                val suggestions = (0 until suggestionsJson.length()).map { index ->
                    val suggestion = suggestionsJson.getJSONObject(index)
                    val candidatesJson = suggestion.getJSONArray("candidates")
                    BackupWatchSuggestion(
                        packageName = suggestion.getString("packageName"),
                        activityName = suggestion.getString("activityName"),
                        createdAt = suggestion.getLong("createdAt"),
                        candidates = (0 until candidatesJson.length()).map { candidateIndex ->
                            val candidate = candidatesJson.getJSONObject(candidateIndex)
                            BackupWatchCandidate(
                                iconPackPackage = candidate.getString("iconPackPackage"),
                                drawableName = candidate.getString("drawableName"),
                                iconHash = candidate.getString("iconHash")
                            )
                        }
                    )
                }
                rules.add(
                    BackupWatchRule(
                        watchAllPacks = r.getBoolean("watchAllPacks"),
                        completed = r.getBoolean("completed"),
                        createdAt = r.getLong("createdAt"),
                        completedAt = if (r.has("completedAt")) r.getLong("completedAt") else null,
                        apps = apps,
                        packs = packs,
                        baselines = baselines,
                        suggestions = suggestions
                    )
                )
            }

            profiles.add(BackupProfile(profile, icons, rules))
        }

        val packLabels = mutableMapOf<String, String>()
        (if (root.has("packs")) root.getJSONObject("packs") else null)?.let { packs ->
            for (name in packs.keys()) packLabels[name] = packs.getString(name)
        }

        val colorPresets = mutableListOf<BackupColorPreset>()
        root.optionalArray("colorPresets")?.let { presets ->
            for (i in 0 until presets.length()) {
                val preset = presets.getJSONObject(i)
                colorPresets.add(
                    BackupColorPreset(
                        name = preset.getString("name"),
                        style = preset.getString("style"),
                        createdAt = preset.optLong("createdAt")
                    )
                )
            }
        }

        val modifierPresets = mutableListOf<BackupModifierPreset>()
        root.optionalArray("modifierPresets")?.let { presets ->
            for (i in 0 until presets.length()) {
                val preset = presets.getJSONObject(i)
                modifierPresets.add(
                    BackupModifierPreset(
                        name = preset.getString("name"),
                        payload = preset.getString("payload"),
                        schemaVersion = preset.getInt("schemaVersion"),
                        createdAt = preset.optLong("createdAt"),
                        lastUsedAt = preset.optLong("lastUsedAt")
                    )
                )
            }
        }

        return BackupData(profiles, prefs, packLabels, colorPresets, modifierPresets)
    }
}

private fun JSONObject.optionalArray(name: String): JSONArray? =
    if (has(name)) getJSONArray(name) else null
