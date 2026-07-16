package dev.renkinProject.renkin.data.transfer

import dev.renkinProject.renkin.data.DbApplication
import dev.renkinProject.renkin.data.Profile
import dev.renkinProject.renkin.data.watch.AppComponent
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
    val packLabels: Map<String, String> = emptyMap()
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
    val packs: List<String>
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
    /** Bump when the schema changes; import refuses files newer than it understands. */
    const val FORMAT_VERSION = 1

    fun encode(data: BackupData): String {
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
                .put("prefsSnapshot", bp.profile.prefsSnapshot)
                .put("hasUnbuiltChanges", bp.profile.hasUnbuiltChanges)
                .put("hideMissingPackWarning", bp.profile.hideMissingPackWarning)

            val icons = JSONArray()
            for (icon in bp.icons) {
                icons.put(
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
                )
            }
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
                rules.put(r)
            }
            p.put("watchRules", rules)

            profiles.put(p)
        }
        root.put("profiles", profiles)

        val packs = JSONObject()
        for ((pack, label) in data.packLabels) packs.put(pack, label)
        root.put("packs", packs)

        return root.toString()
    }

    /** Throws (JSONException) on malformed input — callers surface that as a failed import. */
    fun decode(json: String): BackupData {
        val root = JSONObject(json)

        val prefs = mutableMapOf<String, BackupPref>()
        val prefsJson = root.getJSONObject("prefs")
        for (name in prefsJson.keys()) {
            val entry = prefsJson.getJSONObject(name)
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
                hideMissingPackWarning = p.optBoolean("hideMissingPackWarning")
            )

            val icons = mutableListOf<DbApplication>()
            val iconsJson = p.getJSONArray("icons")
            for (j in 0 until iconsJson.length()) {
                val icon = iconsJson.getJSONObject(j)
                icons.add(
                    DbApplication(
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
                        // Absent in pre-v11 files — those icons load as generated.
                        isCustomIcon = icon.optBoolean("isCustomIcon")
                    )
                )
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
                rules.add(
                    BackupWatchRule(
                        watchAllPacks = r.getBoolean("watchAllPacks"),
                        completed = r.getBoolean("completed"),
                        createdAt = r.getLong("createdAt"),
                        completedAt = if (r.has("completedAt")) r.getLong("completedAt") else null,
                        apps = apps,
                        packs = packs
                    )
                )
            }

            profiles.add(BackupProfile(profile, icons, rules))
        }

        val packLabels = mutableMapOf<String, String>()
        root.optJSONObject("packs")?.let { packs ->
            for (name in packs.keys()) packLabels[name] = packs.getString(name)
        }

        return BackupData(profiles, prefs, packLabels)
    }
}
