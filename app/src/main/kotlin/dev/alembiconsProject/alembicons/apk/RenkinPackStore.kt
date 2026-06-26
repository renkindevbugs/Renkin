package dev.alembiconsProject.alembicons.apk

import android.content.Context
import androidx.compose.ui.graphics.Color
import dev.alembiconsProject.alembicons.data.DbApplication
import dev.alembiconsProject.alembicons.data.RenkinPackRepository
import dev.alembiconsProject.alembicons.drawable.BitmapIconDrawable
import dev.alembiconsProject.alembicons.drawable.IconPackDrawable
import dev.alembiconsProject.alembicons.extension.bitmapFromBase64
import dev.alembiconsProject.alembicons.icon.parser.XmlNodeParser
import dev.alembiconsProject.alembicons.packages.PackageInfoStruct
import dev.alembiconsProject.alembicons.xml.XmlDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Persists the generated icons (the "Renkin pack") and handles the serialization between
 * the stored [DbApplication] rows and live [IconPackDrawable]s. [ApplicationProvider]
 * applies the loaded icons to its app list; this class owns the encode/decode and DB I/O.
 */
class RenkinPackStore(private val context: Context) {
    private val repo = RenkinPackRepository(context)

    data class SavedEntry(val icon: IconPackDrawable?, val calendarEnabled: Boolean, val calendarPrefix: String?, val calendarPackName: String?)

    /** Loads the saved icons + calendar flags, keyed by "package/activity". */
    suspend fun load(defaultColor: Color): Map<String, SavedEntry> = withContext(Dispatchers.Default) {
        repo.getAll().associate { dbApp ->
            val icon: IconPackDrawable? = when {
                dbApp.drawable.isEmpty() -> null
                dbApp.isXml -> {
                    val nodes = XmlDecoder.fromBase64(dbApp.drawable)
                    XmlNodeParser.parse(context.resources, nodes, defaultColor)
                }
                else -> BitmapIconDrawable(bitmapFromBase64(dbApp.drawable), dbApp.isAdaptiveIcon)
            }
            "${dbApp.packageName}/${dbApp.activityName}" to SavedEntry(
                icon,
                dbApp.calendarEnabled,
                dbApp.calendarPrefix.ifEmpty { null },
                dbApp.calendarPackName.ifEmpty { null }
            )
        }
    }

    /** Replaces the stored set with the created icons and calendar flags of [apps]. */
    suspend fun save(apps: List<PackageInfoStruct>) = withContext(Dispatchers.Default) {
        val dbApps = apps.mapNotNull { app ->
            val icon = app.createdIcon
            if (icon == null && !app.calendarEnabled) return@mapNotNull null
            DbApplication(
                app.packageName,
                app.activityName,
                icon?.isAdaptiveIcon() ?: false,
                icon != null && icon !is BitmapIconDrawable,
                icon?.toDbString() ?: "",
                app.calendarEnabled,
                app.calendarPrefix ?: "",
                app.calendarPackName ?: ""
            )
        }
        repo.replaceAll(dbApps)
    }

    /** Keys ("package/activity") of the apps in the last built/saved pack. */
    suspend fun savedKeys(): Set<String> = withContext(Dispatchers.Default) {
        repo.getAll().map { "${it.packageName}/${it.activityName}" }.toSet()
    }
}
