package dev.renkinProject.renkin.apk

import android.content.Context
import androidx.compose.ui.graphics.Color
import dev.renkinProject.renkin.data.DbApplication
import dev.renkinProject.renkin.data.RenkinPackRepository
import dev.renkinProject.renkin.drawable.BitmapIconDrawable
import dev.renkinProject.renkin.drawable.IconPackDrawable
import dev.renkinProject.renkin.extension.bitmapFromBase64
import dev.renkinProject.renkin.icon.parser.XmlNodeParser
import dev.renkinProject.renkin.packages.PackageInfoStruct
import dev.renkinProject.renkin.xml.XmlDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Persists the generated icons (the "Renkin pack") and handles the serialization between
 * the stored [DbApplication] rows and live [IconPackDrawable]s. [ApplicationProvider]
 * applies the loaded icons to its app list; this class owns the encode/decode and DB I/O.
 */
class RenkinPackStore(private val context: Context) {
    private val repo = RenkinPackRepository(context)

    data class SavedEntry(val icon: IconPackDrawable?, val calendarEnabled: Boolean, val calendarPrefix: String?, val calendarPackName: String?, val sourcePackName: String?)

    /** Loads [profileId]'s saved icons + calendar flags, keyed by "package/activity". */
    suspend fun load(profileId: Long, defaultColor: Color): Map<String, SavedEntry> = withContext(Dispatchers.Default) {
        repo.getAll(profileId).associate { dbApp ->
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
                dbApp.calendarPackName.ifEmpty { null },
                dbApp.sourcePackName.ifEmpty { null }
            )
        }
    }

    /** Replaces [profileId]'s stored set with the created icons and calendar flags of [apps]. */
    suspend fun save(profileId: Long, apps: List<PackageInfoStruct>) = withContext(Dispatchers.Default) {
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
                app.calendarPackName ?: "",
                app.sourcePackName ?: "",
                profileId
            )
        }
        repo.replaceAll(profileId, dbApps)
    }

    /** Keys ("package/activity") of the apps in [profileId]'s last built/saved pack. */
    suspend fun savedKeys(profileId: Long): Set<String> = withContext(Dispatchers.Default) {
        repo.getAll(profileId).map { "${it.packageName}/${it.activityName}" }.toSet()
    }
}
