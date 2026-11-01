package dev.renkinProject.renkin.packages

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.os.Process
import android.os.SystemClock
import android.os.UserManager
import dev.renkinProject.renkin.BuildConfig
import dev.renkinProject.renkin.data.AppTarget
import dev.renkinProject.renkin.data.AppTargetState
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

    /**
     * Launcher activities of this user that LauncherApps hides because the app or the activity
     * is disabled. Other profiles are out of reach without cross-user permissions.
     */
    fun disabledLauncherActivities(): List<AppTarget> {
        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        val listed = launcherApps.getActivityList(null, Process.myUserHandle())
            .mapTo(hashSetOf()) { it.componentName.flattenToString() }
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val resolved = packageManager.queryIntentActivities(intent, DISABLED_COMPONENT_FLAGS)
        return resolved.mapNotNull { info ->
            val activity = info.activityInfo ?: return@mapNotNull null
            val component = ComponentName(activity.packageName, activity.name)
            if (component.flattenToString() in listed) return@mapNotNull null
            val label = runCatching { info.loadLabel(packageManager).toString() }.getOrDefault(activity.packageName)
            AppTarget(activity.packageName, activity.name, label)
        }.distinctBy { it.key }.sortedBy { it.label.lowercase() }
    }

    /** A list entry for an added target that the launcher list does not contain. */
    fun targetApplication(target: AppTarget): PackageInfoStruct {
        val component = ComponentName(target.packageName, target.activityName)
        val activity = packageManager.disabledAwareActivityInfo(component)
        val state = when {
            activity == null -> AppTargetState.NOT_INSTALLED
            isDisabled(activity, component) -> AppTargetState.DISABLED
            else -> AppTargetState.NOT_IN_LAUNCHER
        }
        val iconSource = if (activity == null) ApplicationIconSource.Unavailable
        else ApplicationIconSource.PackageActivity(component, Configuration(context.resources.configuration))
        return PackageInfoStruct(
            appName = target.label,
            packageName = target.packageName,
            activityName = target.activityName,
            iconSource = iconSource,
            // Pack indexes key every profile's targets with id 0 (InstalledApplication equality);
            // the loader reports the real resource id when it reads the icon.
            iconID = 0,
            targetState = state
        )
    }

    // ActivityInfo.enabled is only the manifest default; the runtime choice lives in the
    // component setting, and ApplicationInfo.enabled carries the package-wide one.
    private fun isDisabled(activity: android.content.pm.ActivityInfo, component: ComponentName): Boolean {
        if (!activity.applicationInfo.enabled) return true
        val setting = runCatching { packageManager.getComponentEnabledSetting(component) }
            .getOrDefault(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
        return when (setting) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> false
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> !activity.enabled
            else -> true
        }
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
