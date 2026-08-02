package dev.renkinProject.renkin.packages

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.UserManager
import dev.renkinProject.renkin.data.InstalledApplication
import dev.renkinProject.renkin.drawable.hasValidDimensions
import dev.renkinProject.renkin.extension.toDrawable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.Locale

internal fun launcherIconOrFallback(
    activityIcon: Drawable?,
    applicationIcon: () -> Drawable
): Drawable = activityIcon ?: applicationIcon()

internal fun launcherIconIdOrFallback(activityIconId: Int?, applicationIconId: Int): Int =
    activityIconId?.takeIf { it != 0 } ?: applicationIconId

/**
 * Reads launcher applications independently of icon-pack discovery and resource parsing.
 * Callers that only need the device app inventory do not need to construct ApplicationManager.
 */
internal class InstalledAppCatalog(private val context: Context) {
    private val packageManager: PackageManager = context.packageManager

    /**
     * Lightweight component references for every launcher entry. Labels and drawables are left
     * unloaded for identity-only consumers such as appfilter matching and the watch checker.
     */
    fun getAllInstalledApplications(): List<InstalledApplication> {
        val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps

        val applications = linkedSetOf<InstalledApplication>()
        for (user in userManager.userProfiles) {
            for (app in launcherApps.getActivityList(null, user)) {
                @Suppress("DEPRECATION")
                val iconId = launcherIconIdOrFallback(
                    runCatching {
                        packageManager.getActivityInfo(app.componentName, 0).iconResource
                    }.getOrNull(),
                    app.applicationInfo.icon
                )
                applications.add(
                    InstalledApplication(
                        app.componentName.packageName,
                        app.componentName.className,
                        iconId
                    )
                )
            }
        }
        return applications.toList()
    }

    suspend fun getAllInstalledApps(): Array<PackageInfoStruct> = coroutineScope {
        val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps

        // Labels, localized resources and icons each require binder/resource calls. Resolve
        // launcher entries in parallel, while awaitAll preserves their original order.
        val structs = userManager.userProfiles
            .flatMap { user -> launcherApps.getActivityList(null, user) }
            .map { app ->
                async(Dispatchers.Default) {
                    val appName = app.applicationInfo.loadLabel(packageManager).toString()
                    val originalName = loadEnglishLabel(app.applicationInfo, appName)
                    val icon = launcherIconOrFallback(
                        runCatching {
                            app.getIcon(context.resources.displayMetrics.densityDpi)
                        }.getOrNull()
                    ) { app.applicationInfo.loadIcon(packageManager) }
                    @Suppress("DEPRECATION")
                    val iconId = launcherIconIdOrFallback(
                        runCatching {
                            packageManager.getActivityInfo(app.componentName, 0).iconResource
                        }.getOrNull(),
                        app.applicationInfo.icon
                    )
                    val safeIcon = if (icon.hasValidDimensions()) {
                        icon
                    } else {
                        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                            .toDrawable(context.resources)
                    }

                    PackageInfoStruct(
                        appName = appName,
                        packageName = app.componentName.packageName,
                        activityName = app.componentName.className,
                        icon = safeIcon,
                        iconID = iconId,
                        originalName = originalName
                    )
                }
            }
            .awaitAll()

        linkedSetOf<PackageInfoStruct>().apply { addAll(structs) }.toTypedArray()
    }

    /** Reads an app's English resource label without changing the process locale. */
    private fun loadEnglishLabel(appInfo: ApplicationInfo, fallback: String): String {
        val labelRes = appInfo.labelRes
        if (labelRes == 0) return fallback
        return try {
            val resources = packageManager.getResourcesForApplication(appInfo)
            val configuration = Configuration(resources.configuration).also {
                it.setLocale(Locale.ENGLISH)
            }
            @Suppress("DEPRECATION")
            val englishResources = Resources(
                resources.assets,
                resources.displayMetrics,
                configuration
            )
            englishResources.getString(labelRes)
        } catch (_: Exception) {
            fallback
        }
    }
}
