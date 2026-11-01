package dev.renkinProject.renkin.data

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import org.json.JSONObject

/** Legacy Room/backup snapshots retain stored values, including absent and inactive settings. */
internal class LegacyProfilePreferences(
    private val booleanKeys: List<Preferences.Key<Boolean>>,
    private val intKeys: List<Preferences.Key<Int>>,
    private val stringKeys: List<Preferences.Key<String>>
) {
    private val keys: List<Preferences.Key<*>> = booleanKeys + intKeys + stringKeys

    fun encode(preferences: Preferences): String {
        val json = JSONObject()
        for (key in keys) {
            preferences[key]?.let { json.put(key.name, it) }
        }
        return json.toString()
    }

    fun replace(target: MutablePreferences, snapshot: String) {
        // Invalid or missing fields must clear the leaving profile's values, not inherit them.
        val json = parse(snapshot)
        for (key in booleanKeys) {
            val value = json.opt(key.name)
            if (value is Boolean) target[key] = value else target.remove(key)
        }
        for (key in intKeys) {
            val value = when (val raw = json.opt(key.name)) {
                is Int -> raw
                is Long -> raw.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
                else -> null
            }
            if (value != null) target[key] = value else target.remove(key)
        }
        for (key in stringKeys) {
            val value = json.opt(key.name)
            if (value is String) target[key] = value else target.remove(key)
        }
    }

    fun withBuiltPrimary(snapshot: String, source: Preferences): String {
        // This is a patch to an inactive profile, not a restore: retain every other JSON field.
        return parse(snapshot)
            .put(BuiltPrimarySourceKey.name, source.getIntValue(PrimarySourceKey, SOURCE_DEFAULT.ordinal))
            .put(BuiltPrimaryIconPackKey.name, source.getStringValue(PrimaryIconPackKey))
            .toString()
    }

    private fun parse(snapshot: String): JSONObject =
        runCatching { JSONObject(snapshot) }.getOrDefault(JSONObject())
}
