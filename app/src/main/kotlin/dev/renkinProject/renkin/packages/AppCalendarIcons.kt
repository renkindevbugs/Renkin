package dev.renkinProject.renkin.packages

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import android.os.Bundle
import android.util.TypedValue
import androidx.annotation.WorkerThread
import dev.renkinProject.renkin.drawable.ResourceDrawable

internal data class AppCalendarSource(val packageName: String) {
    companion object {
        const val PREFIX = "app:dynamic_icons"

        fun decode(packageName: String, calendarPack: String?, prefix: String?): AppCalendarSource? =
            if (packageName.isNotEmpty() && calendarPack == packageName && prefix == PREFIX) AppCalendarSource(packageName) else null
    }
}

internal class AppCalendarIcons(private val context: Context) {
    private val resolver = PackageResourceResolver(context)

    @WorkerThread
    fun detect(app: PackageInfoStruct): AppCalendarSource? {
        when (val source = app.iconSource) {
            is ApplicationIconSource.Installed -> if (source.user != Process.myUserHandle()) return null
            ApplicationIconSource.Unavailable -> return null
            else -> Unit
        }
        return AppCalendarSource(app.packageName).takeIf { resourceIds(it, app.activityName).any { id -> id != 0 } }
    }

    @WorkerThread
    fun days(source: AppCalendarSource): Map<Int, ResourceDrawable> {
        val resources = resolver.getResources(source.packageName) ?: return emptyMap()
        return resourceIds(source).mapIndexedNotNull { index, id ->
            resolver.getDrawable(resources, id)?.let { index + 1 to ResourceDrawable(id, it) }
        }.toMap()
    }

    @WorkerThread
    fun dayForIcon(source: AppCalendarSource, resourceId: Int): Int? {
        val resources = resolver.getResources(source.packageName) ?: return null
        fun path(id: Int): String? = runCatching {
            TypedValue().also { resources.getValue(id, it, true) }.string?.toString()
        }.getOrNull()
        val selected = path(resourceId) ?: return null
        return resourceIds(source).indexOfFirst { it != 0 && path(it) == selected }.takeIf { it >= 0 }?.plus(1)
    }

    @Suppress("DEPRECATION")
    private fun resourceIds(source: AppCalendarSource, activity: String? = null): List<Int> = runCatching {
        val manager = context.packageManager
        val flags = PackageManager.GET_META_DATA or DISABLED_COMPONENT_FLAGS
        val key = "${source.packageName}.dynamic_icons"
        val metadata = buildList<Bundle> {
            if (!activity.isNullOrEmpty()) {
                runCatching { manager.getActivityInfo(ComponentName(source.packageName, activity), flags) }
                    .getOrNull()?.metaData?.let(::add)
            }
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(source.packageName)
            manager.queryIntentActivities(intent, flags).forEach { it.activityInfo?.metaData?.let(::add) }
            manager.getApplicationInfo(source.packageName, PackageManager.GET_META_DATA).metaData?.let(::add)
        }
        val resources = resolver.getResources(source.packageName) ?: return@runCatching emptyList()
        metadata.firstNotNullOfOrNull { data ->
            val id = data.getInt(key)
            if (id == 0) null else runCatching {
                val array = resources.obtainTypedArray(id)
                try { List(minOf(array.length(), 31)) { array.getResourceId(it, 0) } }
                finally { array.recycle() }
            }.getOrNull()?.takeIf { ids -> ids.any { it != 0 } }
        }.orEmpty()
    }.getOrDefault(emptyList())
}
