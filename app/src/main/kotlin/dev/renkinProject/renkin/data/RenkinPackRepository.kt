package dev.renkinProject.renkin.data

import android.content.Context
import androidx.room.withTransaction
import dev.renkinProject.renkin.data.transfer.BackupDatabaseSnapshot
import java.io.File
import kotlinx.coroutines.Dispatchers
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
        dao.getAll(profileId)
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
        dao.getAllProfiles()
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
    suspend fun distinctSourcePacks(): List<String> = access {
        dao.distinctSourcePacks()
    }

    // ---- Profiles -----------------------------------------------------------------

    /** All profiles, default first (ordered by id), as a reactive stream for the switcher UI. */
    fun profilesFlow(): Flow<List<Profile>> = StorageOperationGate.observe(profileDao.getAllFlow())

    suspend fun profiles(): List<Profile> = access { profileDao.getAll() }

    suspend fun profile(id: Long): Profile? = access { profileDao.get(id) }

    /** Restores the undeletable default row without touching its icons or an existing profile. */
    suspend fun ensureDefaultProfile(): Profile = access {
        profileDao.insertIfMissing(
            Profile(id = DEFAULT_PROFILE_ID, name = "Renkin", packLabel = "Renkin Pack")
        )
        checkNotNull(profileDao.get(DEFAULT_PROFILE_ID))
    }

    suspend fun createProfile(profile: Profile): Long = access {
        profileDao.insert(profile)
    }

    suspend fun updateProfile(profile: Profile) = access {
        profileDao.update(profile)
    }

    /** Deletes the profile and all of its stored icons. The default profile is never deleted. */
    suspend fun deleteProfile(id: Long) = access {
        if (id == DEFAULT_PROFILE_ID) return@access
        dao.deleteAllApplications(id)
        profileDao.delete(id)
    }
}
