package dev.renkinProject.renkin.apk

import android.content.Context
import dev.renkinProject.renkin.packages.PackageInfoStruct
import org.json.JSONObject

/**
 * Provenance carried inside every pack Renkin builds: a JSON asset mapping each themed
 * component ("package/activity") to the REAL icon pack its icon was taken from. When one
 * of our own packs is later used as an icon source (hero pack in another profile, per-app
 * pick, or a shared pack APK on someone else's device), the origin is translated back —
 * so usage stats stay truthful and the paid-pack protection can't be laundered by routing
 * icons through a Renkin-built pack.
 */
object PackProvenance {
    const val ASSET_NAME = "renkin_sources.json"

    /**
     * The [ASSET_NAME] content for the apps being built. Only real foreign-pack sources are
     * recorded — uploads, text icons and hand-edited vectors are Renkin's own output, and an
     * (untranslated legacy) own-pack source can't attest a genuine origin.
     */
    fun encode(apps: List<PackageInfoStruct>): String {
        val json = JSONObject()
        for (app in apps) {
            val source = app.sourcePackName
            if (app.createdIcon != null && !source.isNullOrEmpty() && !IconPackBuilder.isOwnPack(source)) {
                json.put(app.key, source)
            }
        }
        return json.toString()
    }

    /** Parses [ASSET_NAME] content. Tolerant: a broken file reads as "no provenance". */
    fun parse(json: String): Map<String, String> = runCatching {
        val parsed = JSONObject(json)
        buildMap {
            for (key in parsed.keys()) put(key, parsed.getString(key))
        }
    }.getOrDefault(emptyMap())

    /** Reads the provenance an installed Renkin-built pack carries (empty for foreign packs). */
    fun read(context: Context, packPackage: String): Map<String, String> = runCatching {
        context.createPackageContext(packPackage, 0).assets.open(ASSET_NAME)
            .bufferedReader().use { it.readText() }
    }.map { parse(it) }.getOrDefault(emptyMap())
}
