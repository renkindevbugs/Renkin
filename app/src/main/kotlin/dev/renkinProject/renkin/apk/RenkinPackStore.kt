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

    data class SavedEntry(
        val icon: IconPackDrawable?,
        val baseIcon: IconPackDrawable?,
        val calendarEnabled: Boolean,
        val calendarPrefix: String?,
        val calendarPackName: String?,
        val sourcePackName: String?,
        val isCustom: Boolean,
        val isLegacy: Boolean,
        val sourceUrl: String?,
        // The raw row, so held-back entries (locked packs, reference icons, absent apps)
        // can be written back verbatim on the next save instead of being dropped.
        val row: DbApplication
    )

    /** Loads [profileId]'s saved icons + calendar flags, keyed by "package/activity". */
    suspend fun load(profileId: Long, defaultColor: Color): Map<String, SavedEntry> = withContext(Dispatchers.Default) {
        repo.getAll(profileId).associate { dbApp ->
            "${dbApp.packageName}/${dbApp.activityName}" to decodeRow(dbApp, defaultColor)
        }
    }

    /** Decodes one stored row — used by [load] and by late unlocks (a missing pack arriving). */
    fun decodeRow(dbApp: DbApplication, defaultColor: Color): SavedEntry {
        // Per-row guard: one corrupt base64/XML record loses that single icon (the row's
        // calendar flags survive) instead of crashing the whole profile load.
        fun decode(drawable: String, isXml: Boolean, isAdaptive: Boolean): IconPackDrawable? = runCatching {
            when {
                drawable.isEmpty() -> null
                isXml -> {
                    val nodes = XmlDecoder.fromBase64(drawable)
                    XmlNodeParser.parse(context.resources, nodes, defaultColor)
                }
                else -> BitmapIconDrawable(bitmapFromBase64(drawable), isAdaptive)
            }
        }.getOrNull()
        val icon = decode(dbApp.drawable, dbApp.isXml, dbApp.isAdaptiveIcon)
        val baseIcon = if (dbApp.baseDrawable.isNotEmpty()) {
            decode(dbApp.baseDrawable, dbApp.baseIsXml, dbApp.baseIsAdaptiveIcon)
        } else icon
        return SavedEntry(
            icon,
            baseIcon,
            dbApp.calendarEnabled,
            dbApp.calendarPrefix.ifEmpty { null },
            dbApp.calendarPackName.ifEmpty { null },
            dbApp.sourcePackName.ifEmpty { null },
            dbApp.isCustomIcon,
            dbApp.isLegacyIcon,
            dbApp.sourceUrl.ifEmpty { null },
            dbApp
        )
    }

    /**
     * Replaces [profileId]'s stored set with the created icons and calendar flags of [apps],
     * plus [preservedRows] — rows held back from the in-memory list (locked behind a missing
     * paid pack, or belonging to apps not installed here) that must survive the save. A live
     * app entry always wins over a preserved row with the same key.
     */
    suspend fun save(
        profileId: Long,
        apps: List<PackageInfoStruct>,
        preservedRows: Collection<DbApplication> = emptyList()
    ) = withContext(Dispatchers.Default) {
        val dbApps = apps.mapNotNull { app ->
            val rendered = app.createdIcon
            val base = app.baseIcon ?: rendered
            if (rendered == null && !app.calendarEnabled) return@mapNotNull null
            DbApplication(
                app.packageName,
                app.activityName,
                rendered?.isAdaptiveIcon() ?: false,
                rendered != null && rendered !is BitmapIconDrawable,
                rendered?.toDbString() ?: "",
                app.calendarEnabled,
                app.calendarPrefix ?: "",
                app.calendarPackName ?: "",
                app.sourcePackName ?: "",
                profileId,
                isCustomIcon = app.isCustom,
                isLegacyIcon = app.isLegacy,
                baseDrawable = base?.toDbString() ?: "",
                baseIsAdaptiveIcon = base?.isAdaptiveIcon() ?: false,
                baseIsXml = base != null && base !is BitmapIconDrawable,
                sourceUrl = app.sourceUrl ?: ""
            )
        }
        val liveKeys = dbApps.map { "${it.packageName}/${it.activityName}" }.toSet()
        val kept = preservedRows.filter { "${it.packageName}/${it.activityName}" !in liveKeys }
        repo.replaceAll(profileId, dbApps + kept)
    }

    /** Keys ("package/activity") of the apps in [profileId]'s last built/saved pack. */
    suspend fun savedKeys(profileId: Long): Set<String> = withContext(Dispatchers.Default) {
        repo.getAll(profileId).map { "${it.packageName}/${it.activityName}" }.toSet()
    }
}
