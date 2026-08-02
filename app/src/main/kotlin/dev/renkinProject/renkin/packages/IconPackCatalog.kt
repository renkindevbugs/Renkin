package dev.renkinProject.renkin.packages

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.ResolveInfoFlags
import android.content.pm.ResolveInfo
import androidx.core.content.pm.PackageInfoCompat
import dev.renkinProject.renkin.data.IconPack
import dev.renkinProject.renkin.util.Log

internal const val ICON_PACK_ACTION = "org.adw.launcher.THEMES"
internal const val CHANGES_WITH_MATERIAL_YOU_COLORS =
    "org.icontheme.CHANGES_WITH_MATERIAL_YOU_COLORS"

internal fun iconPackQueryIntent(
    materialYouColorsOnly: Boolean = false,
    packageName: String? = null
): Intent = Intent(ICON_PACK_ACTION, null).apply {
    if (materialYouColorsOnly) addCategory(CHANGES_WITH_MATERIAL_YOU_COLORS)
    if (packageName != null) setPackage(packageName)
}

/**
 * Discovers installed icon packs and reads only their package metadata. Resource and appfilter
 * parsing deliberately stay in [ApplicationManager], so metadata-only consumers do not create
 * the much broader resource reader.
 */
class IconPackCatalog internal constructor(context: Context) {
    private val packageManager = context.packageManager

    fun installedIconPacks(): List<IconPack> {
        val materialYouPacks = queryActivities(
            iconPackQueryIntent(materialYouColorsOnly = true)
        ).mapTo(mutableSetOf()) { it.activityInfo.packageName }
        val iconPacks = mutableListOf<IconPack>()

        for (resolve in queryActivities(iconPackQueryIntent())) {
            try {
                val packageName = resolve.activityInfo.packageName
                val pack = packageInfo(packageName) ?: continue
                iconPacks.add(
                    IconPack(
                        packageName = packageName,
                        applicationName = resolve.activityInfo.applicationInfo
                            .loadLabel(packageManager)
                            .toString(),
                        versionCode = versionCode(pack),
                        versionName = pack.versionName ?: "",
                        iconID = resolve.activityInfo.applicationInfo.icon,
                        changesWithMaterialYouColors = packageName in materialYouPacks
                    )
                )
            } catch (error: Exception) {
                // Broken metadata in one advertised theme must not hide every healthy pack.
                Log.error("IconPackCatalog", "Skipping malformed icon pack activity", error)
            }
        }

        // Several THEMES activities from one package still represent one source pack.
        return iconPacks.distinctBy { it.packageName }
    }

    /** True when the source pack explicitly advertises the shared dynamic-colour contract. */
    fun changesWithMaterialYouColors(packageName: String): Boolean {
        if (packageName.isEmpty()) return false
        return queryActivities(
            iconPackQueryIntent(
                materialYouColorsOnly = true,
                packageName = packageName
            )
        ).any { it.activityInfo.packageName == packageName }
    }

    fun packageInfo(packageName: String): PackageInfo? = try {
        packageManager.getPackageInfo(packageName, 0)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    fun versionCode(packageInfo: PackageInfo): Long =
        PackageInfoCompat.getLongVersionCode(packageInfo)

    private fun queryActivities(intent: Intent): List<ResolveInfo> =
        if (PackageVersion.is33OrMore()) {
            packageManager.queryIntentActivities(intent, ResolveInfoFlags.of(0))
        } else {
            packageManager.queryIntentActivities(intent, 0)
        }
}
