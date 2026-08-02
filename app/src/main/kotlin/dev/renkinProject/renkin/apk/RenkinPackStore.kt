package dev.renkinProject.renkin.apk

import android.content.Context
import androidx.compose.ui.graphics.Color
import dev.renkinProject.renkin.data.BuiltIcon
import dev.renkinProject.renkin.data.DbApplication
import dev.renkinProject.renkin.data.RenkinPackRepository
import dev.renkinProject.renkin.drawable.ADAPTIVE_ICON_SCALE
import dev.renkinProject.renkin.drawable.BitmapIconDrawable
import dev.renkinProject.renkin.drawable.IconPackDrawable
import dev.renkinProject.renkin.extension.bitmapFromBase64
import dev.renkinProject.renkin.icon.parser.XmlNodeParser
import dev.renkinProject.renkin.packages.PackageInfoStruct
import dev.renkinProject.renkin.xml.XmlDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Persists the generated icons (the "Renkin pack") and handles the serialization between
 * the stored [DbApplication] rows and live [IconPackDrawable]s. [ApplicationProvider]
 * applies the loaded icons to its app list; this class owns the encode/decode and DB I/O.
 */
class RenkinPackStore(
    private val context: Context,
    private val repo: RenkinPackRepository
) {
    constructor(context: Context) : this(context, RenkinPackRepository(context))

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
        val isFallback: Boolean,
        // The raw row, so held-back entries (locked packs, reference icons, absent apps)
        // can be written back verbatim on the next save instead of being dropped.
        val row: DbApplication,
        // True when the row HAS stored artwork that would not decode. The icon is unusable, but
        // the row must not be quietly dropped by the next save — a future build may read it.
        val decodeFailed: Boolean = false
    )

    /** Loads [profileId]'s saved icons + calendar flags, keyed by "package/activity". */
    suspend fun load(profileId: Long, defaultColor: Color): Map<String, SavedEntry> = withContext(Dispatchers.Default) {
        // Row decoding (bitmap decompress / vector XML parse) is independent per row —
        // decode in parallel so a profile full of icons doesn't gate startup serially.
        coroutineScope {
            repo.getAll(profileId).map { dbApp ->
                async {
                    "${dbApp.packageName}/${dbApp.activityName}" to decodeRow(dbApp, defaultColor)
                }
            }.awaitAll().toMap()
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
                // Adaptive Material You bitmaps are stored in their 108dp safe-zone form. Restore
                // the preview zoom too; otherwise a restart displays and later re-renders the raw
                // inset bitmap even though the adaptive export flag itself survived.
                else -> BitmapIconDrawable(
                    bitmapFromBase64(drawable),
                    isAdaptive,
                    if (isAdaptive) ADAPTIVE_ICON_SCALE else 1f
                )
            }
        }.getOrNull()
        val icon = decode(dbApp.drawable, dbApp.isXml, dbApp.isAdaptiveIcon)
        val baseIcon = if (dbApp.baseDrawable.isNotEmpty()) {
            decode(dbApp.baseDrawable, dbApp.baseIsXml, dbApp.baseIsAdaptiveIcon)
        } else icon
        // Stored artwork that refuses to decode: the icon is lost for this session, but the row
        // itself must survive the next save instead of disappearing without a word.
        // Either representation can recover the row: a valid base replaces a broken rendered
        // payload, while a valid rendered icon replaces a broken base on the next explicit save.
        val hasStoredArtwork = dbApp.drawable.isNotEmpty() || dbApp.baseDrawable.isNotEmpty()
        val decodeFailed = hasStoredArtwork && icon == null && baseIcon == null
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
            dbApp.isFallbackIcon,
            dbApp,
            decodeFailed
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
            // Encoding a bitmap icon means base64 of a PNG; do it once and reuse it for the
            // fingerprint below.
            val renderedPayload = rendered?.toDbString() ?: ""
            DbApplication(
                app.packageName,
                app.activityName,
                rendered?.isAdaptiveIcon() ?: false,
                rendered != null && rendered !is BitmapIconDrawable,
                renderedPayload,
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
                sourceUrl = app.sourceUrl ?: "",
                isFallbackIcon = app.isFallback
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

    /** Every stored icon's fingerprint, keyed the same way — the "saved" side of the comparison. */
    suspend fun savedHashes(profileId: Long): Map<String, String> =
        withContext(Dispatchers.Default) {
            repo.getAll(profileId).associate {
                "${it.packageName}/${it.activityName}" to it.fingerprint()
            }
        }

    /** Records what a build shipped, replacing the previous record for that profile. */
    suspend fun recordBuilt(profileId: Long) = withContext(Dispatchers.Default) {
        repo.replaceBuiltIcons(
            profileId,
            repo.getAll(profileId).map {
                BuiltIcon(profileId, it.packageName, it.activityName, it.fingerprint())
            }
        )
    }

    /**
     * Adopts the stored icons as "already built" for a profile that has no record yet and claims
     * no unbuilt changes — an install that predates the record. Without this every icon such a
     * profile owns would be reported as added the first time the new list is opened.
     */
    suspend fun adoptBuiltIfMissing(profileId: Long): Boolean = withContext(Dispatchers.Default) {
        if (repo.builtIcons(profileId).isNotEmpty()) return@withContext false
        recordBuilt(profileId)
        true
    }

    /** What the last build of [profileId] shipped, keyed like [savedHashes]. */
    suspend fun builtHashes(profileId: Long): Map<String, String> =
        withContext(Dispatchers.Default) {
            repo.builtIcons(profileId).associate {
                "${it.packageName}/${it.activityName}" to it.iconHash
            }
        }
}

/** The stored row's fingerprint — the row IS what a build exports. */
internal fun DbApplication.fingerprint(): String =
    iconFingerprint(drawable, calendarEnabled, calendarPrefix)

/**
 * Short, stable fingerprint of what a build would export for one app. Only the parts that end up
 * in the pack are hashed, so re-rendering the same icon never looks like a change.
 */
internal fun iconFingerprint(
    drawable: String,
    calendarEnabled: Boolean,
    calendarPrefix: String?
): String {
    if (drawable.isEmpty() && !calendarEnabled) return ""
    val payload = "$drawable|$calendarEnabled|${calendarPrefix.orEmpty()}"
    val digest = java.security.MessageDigest.getInstance("SHA-1").digest(payload.toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}
