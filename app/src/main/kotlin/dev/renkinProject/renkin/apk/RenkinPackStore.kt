package dev.renkinProject.renkin.apk

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import dev.renkinProject.renkin.data.BuiltIcon
import dev.renkinProject.renkin.data.DbApplication
import dev.renkinProject.renkin.data.IconFingerprintRow
import dev.renkinProject.renkin.data.IconModifierInputStore
import dev.renkinProject.renkin.data.RenkinPackRepository
import dev.renkinProject.renkin.data.StorageOperationGate
import dev.renkinProject.renkin.drawable.BitmapIconDrawable
import dev.renkinProject.renkin.drawable.IconPackDrawable
import dev.renkinProject.renkin.drawable.ImageVectorDrawable
import dev.renkinProject.renkin.extension.toLowerHex
import dev.renkinProject.renkin.icon.creator.IconModifierInput
import dev.renkinProject.renkin.icon.creator.StoredIconModifierInput
import dev.renkinProject.renkin.icon.creator.StoredIconModifierRecipe
import dev.renkinProject.renkin.icon.parser.XmlNodeParser
import dev.renkinProject.renkin.packages.PackageInfoStruct
import dev.renkinProject.renkin.xml.XmlDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import dev.renkinProject.renkin.util.timeProfilePhase

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
    private val inputStore = IconModifierInputStore(context)
    private val artworkDispatcher = Dispatchers.Default.limitedParallelism(2)

    data class ChangeBaselines(val saved: Map<String, String>, val built: Map<String, String>)

    internal data class BaselineSource(
        val rows: List<IconFingerprintRow>,
        val built: Map<String, String>
    )

    internal suspend fun readModifierInput(state: IconModifierInput) = inputStore.read(state)

    internal suspend fun decodeModifierInput(input: IconModifierInputStore.ReadInput) = inputStore.decode(input)

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
        val decodeFailed: Boolean = false,
        val modifierRecipe: String? = null,
        val modifierInput: IconModifierInput? = null
    ) {
        /** No artwork of its own: the icon is rebuilt from the (present) source pack. */
        val isReference: Boolean get() = icon == null && row.drawable.isEmpty() && sourcePackName != null
    }

    /** Loads [profileId]'s saved icons + calendar flags, keyed by "package/activity". */
    suspend fun load(profileId: Long, defaultColor: Color): Map<String, SavedEntry> = withContext(Dispatchers.Default) {
        // Bound transient PNG validation buffers and XML parsing while loading independent rows.
        coroutineScope {
            repo.getAll(profileId).map { dbApp ->
                async(artworkDispatcher) {
                    "${dbApp.packageName}/${dbApp.activityName}" to decodeRow(dbApp, defaultColor)
                }
            }.awaitAll().toMap()
        }
    }

    /** An editable base detached from [icon]'s mutable geometry/pixels; null when it cannot be copied. */
    fun detachedCopy(icon: IconPackDrawable): IconPackDrawable? = runCatching {
        when (icon) {
            is ImageVectorDrawable -> icon.deepCopy()
            is BitmapIconDrawable -> icon.detachedStoredCopy() ?: icon.toBitmap().let { bitmap ->
                bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, bitmap.isMutable)?.let {
                    BitmapIconDrawable(it, icon.isAdaptiveIcon(), icon.previewScale)
                }
            }
            else -> null
        }
    }.getOrNull()

    // Per-payload guard: one corrupt base64/XML record loses that single icon (the row's
    // calendar flags survive) instead of crashing the whole profile load.
    fun decodeStored(drawable: String, isXml: Boolean, isAdaptive: Boolean, defaultColor: Color): IconPackDrawable? = runCatching {
        when {
            drawable.isEmpty() -> null
            isXml -> {
                val nodes = XmlDecoder.fromBase64(drawable)
                XmlNodeParser.parse(context.resources, nodes, defaultColor)
            }
            // Adaptive Material You bitmaps are stored in their 108dp safe-zone form. Restore
            // the preview zoom too; otherwise a restart displays and later re-renders the raw
            // inset bitmap even though the adaptive export flag itself survived.
            else -> BitmapIconDrawable.fromStored(drawable, isAdaptive)
        }
    }.getOrNull()

    /** Decodes one stored row — used by [load] and by late unlocks (a missing pack arriving). */
    fun decodeRow(dbApp: DbApplication, defaultColor: Color): SavedEntry {
        fun decode(drawable: String, isXml: Boolean, isAdaptive: Boolean) = decodeStored(drawable, isXml, isAdaptive, defaultColor)
        val icon = decode(dbApp.drawable, dbApp.isXml, dbApp.isAdaptiveIcon)
        fun decodeBase() = decode(dbApp.baseDrawable, dbApp.baseIsXml, dbApp.baseIsAdaptiveIcon)
        // A base identical to the rendered icon is stored empty (issue #19: detailed adaptive
        // payloads stored twice outgrew a CursorWindow); older rows repeat the identical payload.
        val baseIsRendered = dbApp.drawable.isNotEmpty() && (dbApp.baseDrawable.isEmpty() ||
            dbApp.baseDrawable == dbApp.drawable && dbApp.baseIsXml == dbApp.isXml &&
            dbApp.baseIsAdaptiveIcon == dbApp.isAdaptiveIcon)
        val baseIcon = when {
            dbApp.baseDrawable.isEmpty() && dbApp.drawable.isEmpty() -> icon
            // Avoid parsing/decompressing identical payloads twice.
            baseIsRendered -> icon?.let(::detachedCopy) ?: decode(dbApp.drawable, dbApp.isXml, dbApp.isAdaptiveIcon)
            else -> decodeBase()
        }
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
            decodeFailed,
            // A rendered fallback cannot inherit metadata for a base that failed to decode.
            if (baseIcon != null) StoredIconModifierRecipe.normalize(dbApp.modifierRecipe) else null,
            if (baseIcon != null) StoredIconModifierInput.readState(dbApp.modifierInput)?.takeIf { state ->
                state.storedInput?.let(inputStore::exists) == true
            } else null
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
    ) = StorageOperationGate.run {
        withContext(Dispatchers.Default) {
            val preparedInputs = timeProfilePhase("prepare inputs profile=$profileId") {
                val prepared = mutableMapOf<IconModifierInput, IconModifierInputStore.Prepared>()
                for (app in apps) {
                    val state = app.modifierInput ?: continue
                    if (app.createdIcon == null || state in prepared) continue
                    inputStore.prepare(state)?.let { prepared[state] = it }
                }
                prepared
            }
            val rows = timeProfilePhase("encode profile=$profileId rows=${apps.size}") {
                encodeRows(profileId, apps, preservedRows, preparedInputs)
            }
            timeProfilePhase("write profile=$profileId rows=${rows.size}") {
                repo.replaceAll(profileId, rows)
            }
            preparedInputs.forEach { (state, prepared) -> inputStore.accept(state, prepared) }
        }
    }

    /**
     * Encodes [apps] exactly as a save would, without writing Room: null for a cleared icon.
     * Modifier inputs are stored like a save's, so a restored row keeps its editable source.
     */
    suspend fun encodeUnsaved(profileId: Long, apps: List<PackageInfoStruct>): List<DbApplication?> =
        withContext(Dispatchers.Default) {
            val prepared = mutableMapOf<IconModifierInput, IconModifierInputStore.Prepared>()
            for (app in apps) {
                val state = app.modifierInput ?: continue
                if (app.createdIcon == null || state in prepared) continue
                inputStore.prepare(state)?.let { prepared[state] = it }
            }
            val rows = coroutineScope {
                apps.map { app -> async(artworkDispatcher) { encodeRow(profileId, app, prepared) } }.awaitAll()
            }
            prepared.forEach { (state, input) -> inputStore.accept(state, input) }
            rows
        }

    private suspend fun encodeRows(
        profileId: Long,
        apps: List<PackageInfoStruct>,
        preservedRows: Collection<DbApplication>,
        preparedInputs: Map<IconModifierInput, IconModifierInputStore.Prepared> = emptyMap()
    ): List<DbApplication> {
        val dbApps = coroutineScope {
            apps.filter { it.createdIcon != null || it.calendarEnabled }.map { app ->
                async(artworkDispatcher) { encodeRow(profileId, app, preparedInputs) }
            }.awaitAll().filterNotNull()
        }
        val liveKeys = dbApps.map { "${it.packageName}/${it.activityName}" }.toSet()
        val kept = preservedRows.filter { "${it.packageName}/${it.activityName}" !in liveKeys }
        return dbApps + kept
    }

    private fun encodeRow(
        profileId: Long,
        app: PackageInfoStruct,
        preparedInputs: Map<IconModifierInput, IconModifierInputStore.Prepared>
    ): DbApplication? {
        val rendered = app.createdIcon
        val base = app.baseIcon ?: rendered
        if (rendered == null && !app.calendarEnabled) return null
        // The same source/export instance needs only one PNG/XML serialization.
        val renderedPayload = rendered?.toDbString() ?: ""
        val renderedIsAdaptive = rendered?.isAdaptiveIcon() ?: false
        val renderedIsXml = rendered != null && rendered !is BitmapIconDrawable
        val basePayload = if (base === rendered) renderedPayload else base?.toDbString() ?: ""
        val baseIsAdaptive = base?.isAdaptiveIcon() ?: false
        val baseIsXml = base != null && base !is BitmapIconDrawable
        // Read back as the rendered icon; see decodeRow.
        val baseIsRendered = renderedPayload.isNotEmpty() && basePayload == renderedPayload &&
            baseIsAdaptive == renderedIsAdaptive && baseIsXml == renderedIsXml
        return DbApplication(
            app.packageName,
            app.activityName,
            renderedIsAdaptive,
            renderedIsXml,
            renderedPayload,
            app.calendarEnabled,
            app.calendarPrefix ?: "",
            app.calendarPackName ?: "",
            app.sourcePackName ?: "",
            profileId,
            isCustomIcon = app.isCustom,
            isLegacyIcon = app.isLegacy,
            baseDrawable = if (baseIsRendered) "" else basePayload,
            baseIsAdaptiveIcon = baseIsAdaptive,
            baseIsXml = baseIsXml,
            sourceUrl = app.sourceUrl ?: "",
            isFallbackIcon = app.isFallback,
            modifierRecipe = if (base != null) app.modifierRecipe.orEmpty() else "",
            modifierInput = if (base != null) app.modifierInput?.let { preparedInputs[it]?.document }.orEmpty() else ""
        )
    }

    /** Every stored icon's fingerprint, keyed "package/activity" — the "saved" side of the comparison. */
    suspend fun savedHashes(profileId: Long): Map<String, String> =
        withContext(Dispatchers.Default) {
            fingerprints(repo.fingerprintRows(profileId))
        }

    private suspend fun fingerprints(rows: List<IconFingerprintRow>): Map<String, String> = rows.associate {
        currentCoroutineContext().ensureActive()
        "${it.packageName}/${it.activityName}" to iconFingerprint(it.drawable, it.calendarEnabled, it.calendarPrefix)
    }

    internal suspend fun readBaselineSource(profileId: Long): BaselineSource =
        StorageOperationGate.run {
            BaselineSource(repo.fingerprintRows(profileId), builtHashes(profileId))
        }

    internal suspend fun calculateBaselines(source: BaselineSource): ChangeBaselines =
        withContext(Dispatchers.Default) { ChangeBaselines(fingerprints(source.rows), source.built) }

    /** Caller revalidates saved rows and build ownership under the profile/storage gates. */
    internal suspend fun adoptBaselineSource(
        profileId: Long, source: BaselineSource, saved: Map<String, String>
    ) {
        repo.replaceBuiltIcons(profileId, source.rows.map { row ->
            BuiltIcon(profileId, row.packageName, row.activityName,
                saved.getValue("${row.packageName}/${row.activityName}"))
        })
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

    suspend fun recordBuilt(
        profileId: Long,
        apps: List<PackageInfoStruct>,
        preservedRows: Collection<DbApplication>
    ) = withContext(Dispatchers.Default) {
        repo.replaceBuiltIcons(profileId, encodeRows(profileId, apps, preservedRows).map {
            BuiltIcon(profileId, it.packageName, it.activityName, it.fingerprint())
        })
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
    return digest.toLowerHex()
}

/**
 * Projects a saved row's metadata onto [this] app with an already resolved [icon]. Every field a
 * save keeps must travel here; how the icon is resolved stays with each caller (restore, load and
 * the locked-row refresh deliberately differ).
 */
internal fun PackageInfoStruct.withSavedEntry(
    entry: RenkinPackStore.SavedEntry,
    icon: IconPackDrawable,
    baseIcon: IconPackDrawable?,
    isRefreshMade: Boolean = this.isRefreshMade
): PackageInfoStruct = changeExport(
    icon,
    isFallback = entry.isFallback,
    sourcePackName = entry.sourcePackName,
    isRefreshMade = isRefreshMade,
    isCustom = entry.isCustom,
    isLegacy = entry.isLegacy,
    baseIcon = baseIcon,
    sourceUrl = entry.sourceUrl,
    modifierRecipe = entry.modifierRecipe,
    modifierInput = entry.modifierInput
).changeCalendar(entry.calendarEnabled, entry.calendarPrefix, entry.calendarPackName)
