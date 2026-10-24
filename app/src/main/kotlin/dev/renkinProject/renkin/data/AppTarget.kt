package dev.renkinProject.renkin.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * A launcher component the user added to a profile by hand because the device does not list it:
 * a disabled app, one in a hidden profile (Private Space) or one not installed at all. Launchers
 * match icon packs by exact component, so the activity is part of the identity, not a detail.
 */
data class AppTarget(
    val packageName: String,
    val activityName: String,
    val label: String
) {
    val key: String get() = "$packageName/$activityName"
}

/** How an added target relates to the device right now; decides its badge and icon source. */
enum class AppTargetState { DISABLED, NOT_INSTALLED, NOT_IN_LAUNCHER }

/**
 * Stored in [Profile.appTargets] as a JSON array. Decoding is lenient: one malformed entry is
 * dropped instead of taking the profile's other targets with it.
 */
object AppTargetCodec {
    const val MAX_TARGETS = 500
    const val MAX_LABEL_LENGTH = 100

    fun encode(targets: List<AppTarget>): String {
        if (targets.isEmpty()) return ""
        val array = JSONArray()
        for (target in targets.take(MAX_TARGETS)) {
            array.put(
                JSONObject()
                    .put("packageName", target.packageName)
                    .put("activityName", target.activityName)
                    .put("label", target.label)
            )
        }
        return array.toString()
    }

    fun decode(document: String): List<AppTarget> {
        if (document.isBlank()) return emptyList()
        val array = runCatching { JSONArray(document) }.getOrNull() ?: return emptyList()
        val targets = linkedMapOf<String, AppTarget>()
        for (index in 0 until array.length()) {
            if (targets.size >= MAX_TARGETS) break
            val entry = array.optJSONObject(index) ?: continue
            val target = validTarget(
                entry.optString("packageName"),
                entry.optString("activityName"),
                entry.optString("label")
            ) ?: continue
            targets.putIfAbsent(target.key, target)
        }
        return targets.values.toList()
    }

    /** Re-encodes only the valid part of an imported document. */
    fun normalize(document: String): String = encode(decode(document))

    fun validTarget(packageName: String, activityName: String, label: String): AppTarget? {
        val pkg = packageName.trim()
        if (!isValidPackageName(pkg)) return null
        val activity = expandActivityName(pkg, activityName.trim())
        if (!isValidClassName(activity)) return null
        val name = label.trim().take(MAX_LABEL_LENGTH).ifEmpty { pkg }
        return AppTarget(pkg, activity, name)
    }

    /** Manifest shorthand: ".Main" and "Main" both mean a class inside the package. */
    fun expandActivityName(packageName: String, activityName: String): String = when {
        activityName.startsWith(".") -> packageName + activityName
        activityName.isNotEmpty() && '.' !in activityName -> "$packageName.$activityName"
        else -> activityName
    }

    private val PACKAGE_SEGMENT = Regex("[A-Za-z][A-Za-z0-9_]*")
    private val CLASS_SEGMENT = Regex("[A-Za-z_$][A-Za-z0-9_$]*")

    fun isValidPackageName(name: String): Boolean {
        val segments = name.split('.')
        return name.length <= 255 && segments.size >= 2 && segments.all { PACKAGE_SEGMENT.matches(it) }
    }

    fun isValidClassName(name: String): Boolean {
        val segments = name.split('.')
        return name.length <= 255 && segments.size >= 2 && segments.all { CLASS_SEGMENT.matches(it) }
    }
}
