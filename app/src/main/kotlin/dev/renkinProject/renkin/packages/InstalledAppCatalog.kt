package dev.renkinProject.renkin.packages

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.os.UserManager
import dev.renkinProject.renkin.BuildConfig
import dev.renkinProject.renkin.data.InstalledApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

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
    private companion object { val revisions = AtomicLong() }

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
                val iconId = launcherActivityIconId(context, app)
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
        val started = if (BuildConfig.DEBUG) SystemClock.elapsedRealtimeNanos() else 0L
        val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        val revision = revisions.incrementAndGet()
        val configuration = Configuration(context.resources.configuration)

        // Labels and localized resources require binder/resource calls. Resolve
        // launcher entries in parallel, while awaitAll preserves their original order.
        val structs = userManager.userProfiles
            .flatMap { user -> launcherApps.getActivityList(null, user) }
            .map { app ->
                async(Dispatchers.Default) {
                    val appName = app.applicationInfo.loadLabel(packageManager).toString()
                    val originalName = loadEnglishLabel(app.applicationInfo, appName)
                    val iconId = launcherActivityIconId(context, app)

                    PackageInfoStruct(
                        appName = appName,
                        packageName = app.componentName.packageName,
                        activityName = app.componentName.className,
                        iconSource = ApplicationIconSource.Installed(app.componentName, app.user,
                            app.applicationInfo.sourceDir, app.applicationInfo.splitSourceDirs.orEmpty().toList(),
                            revision, configuration),
                        iconID = iconId,
                        originalName = originalName
                    )
                }
            }
            .awaitAll()

        val result = linkedSetOf<PackageInfoStruct>().apply { addAll(structs) }.toTypedArray()
        if (BuildConfig.DEBUG) {
            dev.renkinProject.renkin.util.Log.debug("InstalledIconSources", "entries=${result.size}, eagerDrawables=0")
            reportInstalledIconMemory(result.mapNotNull { it.iconSource.retainedDrawableOrNull() },
                (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0)
        }
        result
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
