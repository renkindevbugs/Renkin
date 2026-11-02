package dev.renkinProject.renkin.data

import android.content.Context
import androidx.room.withTransaction
import dev.renkinProject.renkin.data.transfer.BackupDatabaseSnapshot
import java.io.File
import java.util.concurrent.Callable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Persists the sets of generated icons (one per profile), so they survive app restarts.
 * Wraps the Room DAOs and runs every access off the main thread.
 */
class RenkinPackRepository(private val db: RenkinPackDatabase) {
    /** Production entry point: uses the shared singleton database. Tests pass an in-memory
     * [RenkinPackDatabase] to the primary constructor instead. */
    constructor(context: Context) : this(RenkinPackDatabase.get(context))

    private suspend fun <T> access(block: suspend () -> T): T = StorageOperationGate.run {
        withContext(Dispatchers.Default) { block() }
    }

    private suspend fun <T> transaction(block: suspend () -> T): T = StorageOperationGate.run {
        db.withTransaction { block() }
    }

    internal suspend fun writeRecoverySnapshot(file: File) = transaction {
        BackupDatabaseSnapshot.write(db.openHelper.writableDatabase, file)
    }

    internal suspend fun writeProfileStyleDiagnostics(file: File) = transaction {
        BackupDatabaseSnapshot.writeProfileDiagnostics(db.openHelper.writableDatabase, file)
    }

    internal suspend fun restoreRecoverySnapshot(file: File) = transaction {
        BackupDatabaseSnapshot.restore(db.openHelper.writableDatabase, file)
    }

    internal suspend fun checkRecoveryCompatibility(file: File) = access {
        BackupDatabaseSnapshot.checkCompatibility(db.openHelper.writableDatabase, file)
    }

    internal suspend fun flushRecoveryWrites() = access {
        BackupDatabaseSnapshot.flush(db.openHelper.writableDatabase)
    }

    private val dao = db.renkinPackDao()
    private val profileDao = db.profileDao()

    suspend fun getAll(profileId: Long): List<DbApplication> = access {
        consistentRead { withFullArtwork(dao.getAllInline(profileId), dao.oversizedArtwork(profileId)) }
    }

    internal suspend fun editorBaseline(profileId: Long, packageName: String, activityName: String): DbApplication? = access {
        consistentRead {
            dao.editorBaselineInline(profileId, packageName, activityName)?.let { row ->
                withFullArtwork(listOf(row), dao.oversizedArtwork(profileId)).single()
            }
        }
    }

    internal suspend fun fingerprintRows(profileId: Long): List<IconFingerprintRow> = access {
        consistentRead {
            val oversized = dao.oversizedArtwork(profileId).associateBy { it.packageName to it.activityName }
            dao.fingerprintRowsInline(profileId).map { row ->
                val artwork = oversized[row.packageName to row.activityName]
                if (artwork == null || artwork.drawableChars <= OversizedText.INLINE_CHARS) row
                else row.copy(drawable = readDrawable(artwork))
            }
        }
    }

    /**
     * [digest] of each stored icon's base (its drawable when it has none), keyed "package/activity".
     * Rows are read one at a time so only one icon's artwork is in memory, unlike [getAll].
     */
    internal suspend fun storedBaseDigests(profileId: Long, digest: (String) -> String): Map<String, String> = access {
        consistentRead {
            val oversized = dao.oversizedArtwork(profileId).associateBy { it.packageName to it.activityName }
            dao.exportKeys().filter { it.profileId == profileId }.mapNotNull { key ->
                val inline = dao.editorBaselineInline(profileId, key.packageName, key.activityName) ?: return@mapNotNull null
                val row = withFullArtwork(listOf(inline), listOfNotNull(oversized[key.packageName to key.activityName])).single()
                "${key.packageName}/${key.activityName}" to digest(row.baseDrawable.ifEmpty { row.drawable })
            }.toMap()
        }
    }

    // The inline rows and the chunked artwork must describe the same stored state.
    private fun <T> consistentRead(block: () -> T): T = db.runInTransaction(Callable { block() })

    private fun withFullArtwork(rows: List<DbApplication>, oversized: List<OversizedIconArtwork>): List<DbApplication> {
        if (oversized.isEmpty()) return rows
        val byKey = oversized.associateBy { Triple(it.profileId, it.packageName, it.activityName) }
        return rows.map { row ->
            val artwork = byKey[Triple(row.profileId, row.packageName, row.activityName)] ?: return@map row
            row.copy(
                drawable = if (artwork.drawableChars > OversizedText.INLINE_CHARS) readDrawable(artwork) else row.drawable,
                baseDrawable = if (artwork.baseDrawableChars > OversizedText.INLINE_CHARS) {
                    OversizedText.read(artwork.baseDrawableChars) { start, count ->
                        dao.baseDrawableChunk(artwork.profileId, artwork.packageName, artwork.activityName, start, count).orEmpty()
                    }
                } else row.baseDrawable
            )
        }
    }

    private fun readDrawable(artwork: OversizedIconArtwork): String =
        OversizedText.read(artwork.drawableChars) { start, count ->
            dao.drawableChunk(artwork.profileId, artwork.packageName, artwork.activityName, start, count).orEmpty()
        }

    /**
     * Replaces the stored set of [profileId] in one shot — atomically: a crash or error
     * between the delete and the insert must not leave the profile empty.
     */
    suspend fun replaceAll(profileId: Long, apps: List<DbApplication>) = transaction {
        dao.deleteAllApplications(profileId)
        dao.insertAll(apps)
    }

    /** Every profile's stored icons in one read — backup export. */
    suspend fun getAllProfilesApplications(): List<DbApplication> = access {
        consistentRead { withFullArtwork(dao.getAllProfilesInline(), dao.oversizedArtworkAllProfiles()) }
    }

    internal suspend fun <T> readExportIcons(block: suspend (ExportIcons) -> T): T = transaction {
        val rows = ExportIcons(dao.exportKeys(), dao.oversizedArtworkAllProfiles())
        try { block(rows) } finally { rows.active = false }
    }

    internal inner class ExportIcons(
        private val keys: List<IconExportKey>,
        oversized: List<OversizedIconArtwork>
    ) {
        internal var active = true
        private val artwork = oversized.associateBy { Triple(it.profileId, it.packageName, it.activityName) }

        suspend fun visit(profileId: Long, consume: suspend (DbApplication) -> Unit) {
            check(active) { "Export read has expired" }
            for (key in keys) {
                if (key.profileId != profileId) continue
                currentCoroutineContext().ensureActive()
                val row = checkNotNull(dao.editorBaselineInline(profileId, key.packageName, key.activityName)) {
                    "Stored icon changed during export"
                }
                consume(withFullArtwork(listOf(row), listOfNotNull(artwork[Triple(profileId, key.packageName, key.activityName)])).single())
            }
        }
    }

    internal suspend fun exportSourcePacks(profileId: Long? = null, excludedProfileId: Long? = null): Set<String> = access {
        dao.exportSourcePacks(profileId, excludedProfileId).toSet() + profileDao.getAll()
            .filter { (profileId == null || it.id == profileId) && it.id != excludedProfileId }
            .mapNotNull { ProfilePackIconCodec.sourcePackName(it.packIcon) }
    }

    internal suspend fun modifierInputDocuments(): List<String> = access { dao.modifierInputDocuments() }

    /**
     * Backup import: replaces every profile and every stored icon in one transaction, keeping
     * the backup's profile ids (icons and watch rules reference them). Callers must have fully
     * parsed the incoming data first — a decode error must not run after the wipe.
     */
    suspend fun replaceEverything(profiles: List<Profile>, apps: List<DbApplication>) = transaction {
        dao.deleteEverything()
        profileDao.deleteEverything()
        profiles.forEach { profileDao.insert(it) }
        dao.insertAll(apps)
        builtIconDao.deleteForMissingProfiles()
        // Backup ids can reuse a profile id this device had; its looks must not carry over.
        globalStyleHistoryDao.deleteEverything()
    }

    // ---- What the last build shipped -----------------------------------------------

    private val builtIconDao = db.builtIconDao()

    suspend fun builtIcons(profileId: Long): List<BuiltIcon> = access {
        builtIconDao.get(profileId)
    }

    /** One transaction: a half-written record would read as "half the pack is unbuilt". */
    suspend fun replaceBuiltIcons(profileId: Long, rows: List<BuiltIcon>) = transaction {
        builtIconDao.deleteForProfile(profileId)
        builtIconDao.insertAll(rows)
    }

    // ---- Global style Apply history ---------------------------------------------------

    private val globalStyleHistoryDao = db.globalStyleHistoryDao()

    /** Newest first. */
    suspend fun globalStyleHistory(profileId: Long): List<GlobalStyleHistoryEntry> = access {
        globalStyleHistoryDao.list(profileId)
    }

    suspend fun globalStyleHistoryEntry(id: Long): GlobalStyleHistoryEntry? = access { globalStyleHistoryDao.get(id) }

    /** Every profile's entry ids, so files kept beside entries can be pruned. */
    suspend fun globalStyleHistoryIds(): Set<Long> = access { globalStyleHistoryDao.ids().toSet() }

    /**
     * Skips a recipe equal to the newest one, then keeps the newest [GLOBAL_STYLE_HISTORY_LIMIT].
     * An empty history first records [previousStyle], so the very first Apply can be undone too.
     */
    suspend fun recordGlobalStyle(profileId: Long, style: String, appliedAt: Long, previousStyle: String) = transaction {
        val newest = globalStyleHistoryDao.list(profileId).firstOrNull()
        if (newest?.style == style) return@transaction
        if (newest == null && previousStyle != style) {
            globalStyleHistoryDao.insert(GlobalStyleHistoryEntry(profileId = profileId, appliedAt = appliedAt, style = previousStyle))
        }
        globalStyleHistoryDao.insert(GlobalStyleHistoryEntry(profileId = profileId, appliedAt = appliedAt, style = style))
        globalStyleHistoryDao.trim(profileId, GLOBAL_STYLE_HISTORY_LIMIT)
    }

    /** A bake is always its own entry, even when its recipe repeats the newest one. Returns its id. */
    suspend fun recordGlobalStyleBake(profileId: Long, style: String, appliedAt: Long, previousStyle: String): Long = transaction {
        if (globalStyleHistoryDao.list(profileId).isEmpty()) {
            globalStyleHistoryDao.insert(GlobalStyleHistoryEntry(profileId = profileId, appliedAt = appliedAt, style = previousStyle))
        }
        val id = globalStyleHistoryDao.insert(GlobalStyleHistoryEntry(profileId = profileId, appliedAt = appliedAt, style = style))
        globalStyleHistoryDao.trim(profileId, GLOBAL_STYLE_HISTORY_LIMIT)
        id
    }

    suspend fun deleteGlobalStyle(profileId: Long, id: Long) = access { globalStyleHistoryDao.delete(profileId, id) }

    // ---- Saved colours -------------------------------------------------------------

    private val presetDao = db.colorPresetDao()

    /** Saved colours/gradients, oldest first — the order they appear in the picker. */
    fun colorPresetsFlow(): Flow<List<ColorPreset>> = StorageOperationGate.observe(presetDao.getAllFlow())

    suspend fun saveColorPreset(name: String, style: String): Long =
        access {
            presetDao.insert(ColorPreset(name = name, style = style))
        }

    suspend fun deleteColorPreset(id: Long) = access {
        presetDao.delete(id)
    }

    /** Backup export reads the library in one shot. */
    suspend fun allColorPresets(): List<ColorPreset> = access {
        presetDao.getAll()
    }

    /**
     * Backup import: the saved colours belong to the device, not a profile, so a full restore
     * replaces the whole library the same way it replaces profiles.
     */
    suspend fun replaceColorPresets(presets: List<ColorPreset>) = transaction {
        presetDao.deleteEverything()
        presets.forEach { presetDao.insert(it) }
    }

    // ---- Modifier presets ----------------------------------------------------------

    private val modifierPresetDao = db.modifierPresetDao()

    /** Saved Modifier-tab recipes, most recently used first — the order the strip shows them. */
    fun modifierPresetsFlow(): Flow<List<ModifierPreset>> = StorageOperationGate.observe(modifierPresetDao.getAllFlow())

    suspend fun saveModifierPreset(name: String, payload: String, schemaVersion: Int): Long =
        access {
            modifierPresetDao.insert(
                ModifierPreset(name = name, payload = payload, schemaVersion = schemaVersion)
            )
        }

    suspend fun renameModifierPreset(id: Long, name: String) = access {
        modifierPresetDao.rename(id, name)
    }

    suspend fun updateModifierPreset(id: Long, payload: String, schemaVersion: Int) =
        access {
            modifierPresetDao.update(id, payload, schemaVersion, System.currentTimeMillis())
        }

    /** Loading a preset makes it the most recent one, which is what the strip is ordered by. */
    suspend fun markModifierPresetUsed(id: Long) = access {
        modifierPresetDao.markUsed(id, System.currentTimeMillis())
    }

    suspend fun deleteModifierPreset(id: Long) = access {
        modifierPresetDao.delete(id)
    }

    suspend fun allModifierPresets(): List<ModifierPreset> = access {
        modifierPresetDao.getAll()
    }

    suspend fun replaceModifierPresets(presets: List<ModifierPreset>) = transaction {
        modifierPresetDao.deleteEverything()
        presets.forEach { modifierPresetDao.insert(it.copy(id = 0)) }
    }

    // ---- Pack verdicts -------------------------------------------------------------

    private val verdictDao = db.packVerdictDao()

    suspend fun verdicts(packages: List<String>): Map<String, PackVerdict> = access {
        verdictDao.get(packages).associateBy { it.packageName }
    }

    suspend fun allVerdicts(): List<PackVerdict> = access {
        verdictDao.getAll()
    }

    suspend fun upsertVerdicts(verdicts: List<PackVerdict>) = access {
        if (verdicts.isNotEmpty()) verdictDao.upsert(verdicts)
    }

    /**
     * Rebuilds the per-device verdict cache from scratch, keeping only what is actually
     * installed now (marked owned). Used after a full-backup restore: the cache is device-local
     * truth about which packs are owned/priced HERE and must not survive a restore that replaced
     * everything else — otherwise a pack the device no longer has installed would stay unlocked.
     */
    suspend fun resetVerdictsToInstalled(installed: List<IconPack>) = access {
        verdictDao.deleteEverything()
        if (installed.isNotEmpty()) {
            verdictDao.upsert(installed.map {
                PackVerdict(it.packageName, seenInstalled = true, label = it.applicationName)
            })
        }
    }

    /** Every pack any stored icon (any profile) came from. */
    suspend fun distinctSourcePacks(): List<String> = exportSourcePacks().toList()

    // ---- Profiles -----------------------------------------------------------------

    /** All profiles, default first (ordered by id), as a reactive stream for the switcher UI. */
    fun profilesFlow(): Flow<List<Profile>> = StorageOperationGate.observe(profileDao.getAllFlow())

    suspend fun profiles(): List<Profile> = access { profileDao.getAll() }

    suspend fun profile(id: Long): Profile? = access { profileDao.get(id) }

    internal suspend fun nextProfileId(): Long = access {
        db.openHelper.readableDatabase.query(
            "SELECT MAX(COALESCE((SELECT MAX(id) FROM Profile), 0), " +
                "COALESCE((SELECT seq FROM sqlite_sequence WHERE name = 'Profile'), 0)) + 1"
        ).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }
    }

    /** Restores the undeletable default row without touching its icons or an existing profile. */
    suspend fun ensureDefaultProfile(): Profile = access {
        profileDao.insertIfMissing(
            Profile(id = DEFAULT_PROFILE_ID, name = "Renkin", packLabel = "Renkin Pack")
        )
        checkNotNull(profileDao.get(DEFAULT_PROFILE_ID))
    }

    suspend fun createProfile(profile: Profile): Long = access {
        profileDao.insert(profile.copy(packIcon = ProfilePackIconCodec.normalize(profile.packIcon)))
    }

    suspend fun updateProfile(profile: Profile) = access {
        val current = profileDao.get(profile.id)
        // Metadata updates can carry a row captured before a style write. Only the style
        // owner may replace the document and its projection once that row is converted.
        // Added app targets have their own writer for the same reason.
        val styled = if (current?.profileStyle?.isNotEmpty() == true) {
            profile.copy(profileStyle = current.profileStyle, prefsSnapshot = current.prefsSnapshot)
        } else profile
        val staleIdentity = current != null && (current.customPackageName != styled.customPackageName ||
            current.packIcon != styled.packIcon || current.packLabel != styled.packLabel)
        profileDao.update(styled.copy(appTargets = current?.appTargets ?: styled.appTargets,
            customPackageName = current?.customPackageName ?: styled.customPackageName,
            packIcon = current?.packIcon ?: styled.packIcon,
            hasUnbuiltChanges = styled.hasUnbuiltChanges || (staleIdentity && current.hasUnbuiltChanges)))
    }

    suspend fun updateProfileDetails(
        profileId: Long, name: String, description: String, packLabel: String,
        customPackageName: String? = null, packIcon: String? = null
    ) = access {
        // A profile deleted while its details dialog was open is not an error: nothing to save.
        val current = profileDao.get(profileId) ?: return@access
        val packageName = customPackageName ?: current.customPackageName
        val icon = ProfilePackIconCodec.normalize(packIcon ?: current.packIcon)
        val changed = current.packLabel != packLabel || current.customPackageName != packageName || current.packIcon != icon
        profileDao.updateDetails(profileId, name, description, packLabel, packageName, icon,
            current.hasUnbuiltChanges || changed)
    }

    suspend fun setProfileUnbuilt(profileId: Long, unbuilt: Boolean) = access {
        profileDao.setUnbuilt(profileId, unbuilt)
    }

    suspend fun setHideMissingPackWarning(profileId: Long, hide: Boolean) = access {
        profileDao.setHideMissingPackWarning(profileId, hide)
    }

    suspend fun updateAppTargets(profileId: Long, document: String) = access {
        check(profileDao.updateAppTargets(profileId, document) == 1) { "Profile disappeared while saving its apps" }
    }

    internal suspend fun writeProfileStyles(styles: List<ProfileStyleUpdate>) = transaction {
        for (style in styles) {
            check(profileDao.updateStyle(style.profileId, style.document, style.legacySnapshot) == 1) {
                "Profile disappeared during style persistence"
            }
        }
    }

    /**
     * Deletes the profile, its stored icons and its last-build record. The default profile is
     * never deleted. Build records left behind by deletions before this cleanup go too.
     */
    suspend fun deleteProfile(id: Long) = transaction {
        if (id == DEFAULT_PROFILE_ID) return@transaction
        dao.deleteAllApplications(id)
        profileDao.delete(id)
        builtIconDao.deleteForMissingProfiles()
        globalStyleHistoryDao.deleteForMissingProfiles()
    }
}
