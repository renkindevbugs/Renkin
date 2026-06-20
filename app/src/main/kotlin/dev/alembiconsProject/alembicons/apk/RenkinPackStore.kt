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

    /** Loads the saved icons, decoded to drawables, keyed by "package/activity". A value
     * can be null if a stored XML icon fails to parse. */
    suspend fun load(defaultColor: Color): Map<String, IconPackDrawable?> = withContext(Dispatchers.Default) {
        repo.getAll().associate { dbApp ->
            val icon: IconPackDrawable? = if (dbApp.isXml) {
                val nodes = XmlDecoder.fromBase64(dbApp.drawable)
                XmlNodeParser.parse(context.resources, nodes, defaultColor)
            } else {
                BitmapIconDrawable(bitmapFromBase64(dbApp.drawable), dbApp.isAdaptiveIcon)
            }
            "${dbApp.packageName}/${dbApp.activityName}" to icon
        }
    }

    /** Replaces the stored set with the created icons of [apps]. */
    suspend fun save(apps: List<PackageInfoStruct>) = withContext(Dispatchers.Default) {
        val dbApps = apps.mapNotNull { app ->
            val icon = app.createdIcon ?: return@mapNotNull null
            DbApplication(
                app.packageName,
                app.activityName,
                icon.isAdaptiveIcon(),
                icon !is BitmapIconDrawable,
                icon.toDbString()
            )
        }
        repo.replaceAll(dbApps)
    }

    /** Keys ("package/activity") of the apps in the last built/saved pack. */
    suspend fun savedKeys(): Set<String> = withContext(Dispatchers.Default) {
        repo.getAll().map { "${it.packageName}/${it.activityName}" }.toSet()
    }
}
