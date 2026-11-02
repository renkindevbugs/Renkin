package dev.renkinProject.renkin.packages

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Process
import android.os.Looper
import android.os.UserHandle
import androidx.annotation.WorkerThread
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.renkinProject.renkin.drawable.hasValidDimensions
import dev.renkinProject.renkin.drawable.toSafeBitmapOrNull
import dev.renkinProject.renkin.extension.toDrawable
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

sealed class ApplicationIconSource {
    internal abstract val previewIdentity: Any

    internal class Inline(val drawable: Drawable, val resourceId: Int) : ApplicationIconSource() {
        override val previewIdentity = Any()
    }

    internal class Prepared(val icon: LoadedApplicationIcon) : ApplicationIconSource() {
        override val previewIdentity = Any()
    }

    internal class Installed(
        val component: ComponentName,
        val user: UserHandle,
        val sourcePath: String,
        val splitPaths: List<String>,
        val catalogRevision: Long,
        configuration: Configuration
    ) : ApplicationIconSource() {
        private val configuration = Configuration(configuration)
        fun configuration() = Configuration(configuration)
        override val previewIdentity = Any()
    }

    /**
     * An added target this user's PackageManager still resolves although LauncherApps does not
     * list it, typically a disabled app. Only PackageManager loads disabled components.
     */
    internal class PackageActivity(
        val component: ComponentName,
        configuration: Configuration
    ) : ApplicationIconSource() {
        private val configuration = Configuration(configuration)
        fun configuration() = Configuration(configuration)
        override val previewIdentity = Any()
    }

    /** An added target with nothing installed to read its own icon from. */
    internal object Unavailable : ApplicationIconSource() {
        override val previewIdentity = Any()
    }
}

internal fun ApplicationIconSource.retainedDrawableOrNull(): Drawable? = when (this) {
    is ApplicationIconSource.Inline -> drawable
    is ApplicationIconSource.Prepared -> icon.drawable
    is ApplicationIconSource.Installed -> null
    is ApplicationIconSource.PackageActivity -> null
    ApplicationIconSource.Unavailable -> null
}

internal const val DISABLED_COMPONENT_FLAGS =
    PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS

@Suppress("DEPRECATION")
internal fun PackageManager.disabledAwareActivityInfo(component: ComponentName): android.content.pm.ActivityInfo? =
    runCatching { getActivityInfo(component, DISABLED_COMPONENT_FLAGS) }.getOrNull()

class LoadedApplicationIcon internal constructor(
    val drawable: Drawable,
    val resourceId: Int,
    internal val applicationInfo: ApplicationInfo? = null,
    internal val configuration: Configuration? = null
)

internal fun launcherActivityIconId(context: Context, app: LauncherActivityInfo): Int =
    launcherIconIdOrFallback(runCatching {
        if (Build.VERSION.SDK_INT >= 31) app.activityInfo.iconResource
        else if (app.user == Process.myUserHandle()) {
            @Suppress("DEPRECATION")
            context.packageManager.getActivityInfo(app.componentName, 0).iconResource
        } else {
            // Older public LauncherActivityInfo APIs do not expose ActivityInfo. Read this
            // user's APK metadata rather than asking for a same-name personal-profile activity.
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(app.applicationInfo.sourceDir, PackageManager.GET_ACTIVITIES)
                ?.activities?.firstOrNull { it.name == app.componentName.className }?.iconResource
        }
    }.getOrNull(), app.applicationInfo.icon)

@Singleton
class InstalledIconLoader @Inject constructor(@param:ApplicationContext private val context: Context) {
    private companion object {
        val cache = AppBitmapMemoryCache()
        val pending = mutableMapOf<AppBitmapCacheKey, CompletableDeferred<Bitmap?>>()
        val previewPermits = Semaphore(2)
        val sourcePermits = java.util.concurrent.Semaphore(2, true)
    }

    private fun key(source: ApplicationIconSource, size: Int) = AppBitmapCacheKey(
        when (source) {
            is ApplicationIconSource.Installed -> source.component.flattenToString()
            is ApplicationIconSource.PackageActivity -> source.component.flattenToString()
            else -> ""
        },
        0, source.previewIdentity, size.coerceAtLeast(1))

    @WorkerThread
    fun load(source: ApplicationIconSource): LoadedApplicationIcon? = when (source) {
        is ApplicationIconSource.Inline -> LoadedApplicationIcon(source.drawable, source.resourceId)
        is ApplicationIconSource.Prepared -> source.icon
        is ApplicationIconSource.Installed -> {
            check(Looper.myLooper() != Looper.getMainLooper()) { "Installed icon loading must run off Main" }
            sourcePermits.acquire()
            try { loadInstalled(source) } finally { sourcePermits.release() }
        }
        is ApplicationIconSource.PackageActivity -> {
            check(Looper.myLooper() != Looper.getMainLooper()) { "Installed icon loading must run off Main" }
            sourcePermits.acquire()
            try { loadPackageActivity(source) } finally { sourcePermits.release() }
        }
        ApplicationIconSource.Unavailable -> null
    }

    private fun loadPackageActivity(source: ApplicationIconSource.PackageActivity): LoadedApplicationIcon? {
        val configured = context.createConfigurationContext(source.configuration())
        val packageManager = configured.packageManager
        val activity = packageManager.disabledAwareActivityInfo(source.component) ?: return null
        val info = activity.applicationInfo
        val icon = runCatching { activity.loadIcon(packageManager) }.getOrNull() ?: return null
        val safe = if (icon.hasValidDimensions()) icon else {
            Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).toDrawable(configured.resources)
        }
        return LoadedApplicationIcon(safe, launcherIconIdOrFallback(activity.iconResource, info.icon),
            ApplicationInfo(info), source.configuration())
    }

    private fun loadInstalled(source: ApplicationIconSource.Installed): LoadedApplicationIcon? {
        val configured = context.createConfigurationContext(source.configuration())
        val launcher = configured.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        val app = runCatching {
            launcher.resolveActivity(Intent().setComponent(source.component), source.user)
        }.getOrNull() ?: return null
        val info = app.applicationInfo
        if (info.sourceDir != source.sourcePath || info.splitSourceDirs.orEmpty().toList() != source.splitPaths) return null
        val activityIcon = runCatching { app.getIcon(configured.resources.displayMetrics.densityDpi) }.getOrNull()
        val icon = activityIcon ?: runCatching { info.loadIcon(configured.packageManager) }.getOrNull() ?: return null
        val id = if (activityIcon != null) launcherActivityIconId(configured, app) else info.icon
        val safe = if (icon.hasValidDimensions()) icon else {
            Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).toDrawable(configured.resources)
        }
        return LoadedApplicationIcon(safe, id, ApplicationInfo(info), source.configuration())
    }

    fun cachedPreview(source: ApplicationIconSource, size: Int): Bitmap? =
        cache.get(key(source, size))

    suspend fun preview(source: ApplicationIconSource, size: Int): Bitmap? {
        val key = key(source, size)
        cache.get(key)?.let { return it }
        val (request, owner) = synchronized(pending) {
            pending[key]?.let { it to false } ?: CompletableDeferred<Bitmap?>().let {
                pending[key] = it
                it to true
            }
        }
        if (!owner) {
            try { return request.await() } catch (error: CancellationException) {
                currentCoroutineContext().ensureActive()
                return preview(source, size)
            }
        }
        try {
            val bitmap = withContext(Dispatchers.Default) {
                previewPermits.withPermit {
                    currentCoroutineContext().ensureActive()
                    val loaded = load(source)?.drawable
                    val result = loaded?.let {
                        val px = if (it.intrinsicWidth in 1 until key.targetPx) it.intrinsicWidth else key.targetPx
                        synchronized(it) { it.toSafeBitmapOrNull(px, px) }
                    }
                    currentCoroutineContext().ensureActive()
                    result?.also { cache.put(key, it) }
                }
            }
            request.complete(bitmap)
            return bitmap
        } catch (error: Throwable) {
            synchronized(pending) { if (pending[key] === request) pending.remove(key) }
            if (error is OutOfMemoryError) {
                cache.clear()
                request.complete(null)
                return null
            }
            request.completeExceptionally(error)
            throw error
        } finally {
            synchronized(pending) { if (pending[key] === request) pending.remove(key) }
        }
    }
}
