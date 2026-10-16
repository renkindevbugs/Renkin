package dev.renkinProject.renkin.data.transfer

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import dev.renkinProject.renkin.BuildConfig
import dev.renkinProject.renkin.apk.IconPackBuilder
import dev.renkinProject.renkin.apk.PackKeystore
import dev.renkinProject.renkin.data.ActiveProfileIdKey
import dev.renkinProject.renkin.data.AutoBackupIntervalKey
import dev.renkinProject.renkin.data.AutoBackupTreeUriKey
import dev.renkinProject.renkin.data.DEFAULT_PROFILE_ID
import dev.renkinProject.renkin.data.DbApplication
import dev.renkinProject.renkin.data.IconModifierInputStore
import dev.renkinProject.renkin.data.InstalledApplication
import dev.renkinProject.renkin.data.LastWatchCheckAtKey
import dev.renkinProject.renkin.data.LastAutoBackupAtKey
import dev.renkinProject.renkin.data.ColorPreset
import dev.renkinProject.renkin.data.ModifierPreset
import dev.renkinProject.renkin.data.PackVerdict
import dev.renkinProject.renkin.data.RenkinPackRepository
import dev.renkinProject.renkin.data.UploadedImageStore
import dev.renkinProject.renkin.data.StorageOperationGate
import dev.renkinProject.renkin.data.editor.EditorDraftInvalidation
import dev.renkinProject.renkin.data.ProfileStyleAccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import dev.renkinProject.renkin.data.snapshotProfilePrefs
import dev.renkinProject.renkin.data.getPreferencesAfterPendingWrites
import dev.renkinProject.renkin.data.watch.AppComponent
import dev.renkinProject.renkin.data.watch.BaselineInput
import dev.renkinProject.renkin.data.watch.CandidateInput
import dev.renkinProject.renkin.data.watch.RuleWithDetails
import dev.renkinProject.renkin.data.watch.SuggestionImport
import dev.renkinProject.renkin.data.watch.WatchRepository
import dev.renkinProject.renkin.data.watch.WatchRuleImport
import dev.renkinProject.renkin.dataStore
import dev.renkinProject.renkin.icon.creator.StoredIconModifierInput
import dev.renkinProject.renkin.packages.ApplicationManager
import dev.renkinProject.renkin.packages.IconPackCatalog
import dev.renkinProject.renkin.service.WatchChecker
import dev.renkinProject.renkin.service.RenkinNotifications
import dev.renkinProject.renkin.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter

/**
 * Writes and restores `.renkin` files (a ZIP of `manifest.json`, `data.json` and, for full
 * backups, the upload gallery and the pack-signing keystore). Two kinds share the format:
 *
 *  - **backup** — the whole device: every profile, all settings, uploads, keystore. Import
 *    replaces everything in place.
 *  - **profile** — one profile to share. Every icon embeds its image data, and pack-sourced
 *    icons also carry a `{pack, drawable}` reference. Import always creates a NEW profile;
 *    icons from a paid (or not-yet-verified) pack stay LOCKED on the importing device until
 *    that pack is installed there — but because the pixels ride along, a pack that later
 *    disappears from the store keeps working (its verdict flips to unlisted and unlocks).
 *
 * Import stages and validates the entire archive BEFORE any store is touched. A durable
 * local snapshot rolls back interrupted commits across databases, preferences and files. Whether
 * imported icons are usable is decided on the importing device every time they load
 * (PackVerdictManager) — verdicts inside the file are never trusted. After an import the
 * caller reloads the in-memory state and kicks off verdict verification.
 */
class BackupManager(
    private val context: Context,
    private val packRepo: RenkinPackRepository,
    private val watchRepo: WatchRepository,
    private val iconPackCatalog: IconPackCatalog = IconPackCatalog(context),
    private val editorDraftInvalidation: EditorDraftInvalidation
) {
    private var inputCleanupComplete = false
    private val appManager by lazy { ApplicationManager(context) }
    private val operations = Mutex()
    private val recovery = BackupRecoveryStore(context)
    private val mutablePreviousStateAvailable = MutableStateFlow(false)
    val previousStateAvailable = mutablePreviousStateAvailable.asStateFlow()
    private val mutablePreviousStateStored = MutableStateFlow(false)
    val previousStateStored = mutablePreviousStateStored.asStateFlow()
    private val mutableAbandonedRecoveryAvailable = MutableStateFlow(false)
    val abandonedRecoveryAvailable = mutableAbandonedRecoveryAvailable.asStateFlow()
    enum class RestoreStage { IDLE, VALIDATING_SAVED_STATE, SAVING_CURRENT_STATE, RESTORING_DATA, RESTORING_FILES }
    private val mutableRestoreStage = MutableStateFlow(RestoreStage.IDLE)
    val restoreStage = mutableRestoreStage.asStateFlow()
    private val mutableSnapshotStage = MutableStateFlow<BackupSnapshotStage?>(null)
    val snapshotStage = mutableSnapshotStage.asStateFlow()
    private val restoreCancellationLock = Any()
    private var restoreCancellationRequested = false
    private val mutableRestoreCancellable = MutableStateFlow(false)
    val restoreCancellable = mutableRestoreCancellable.asStateFlow()

    fun requestRestorePreparationCancellation(): Boolean = synchronized(restoreCancellationLock) {
        if (!mutableRestoreCancellable.value) return@synchronized false
        restoreCancellationRequested = true
        mutableRestoreCancellable.value = false
        true
    }

    private fun allowRestorePreparationCancellation() = synchronized(restoreCancellationLock) {
        if (restoreCancellationRequested) throw CancellationException("Restore preparation cancelled")
        mutableRestoreCancellable.value = true
    }

    private fun beginRestoreCommit() = synchronized(restoreCancellationLock) {
        if (restoreCancellationRequested) throw CancellationException("Restore preparation cancelled")
        // Cancellation and the first durable mutation have one boundary: an accepted Cancel
        // cannot race into a non-cancellable commit after the button was tapped.
        mutableRestoreCancellable.value = false
        mutableRestoreStage.value = RestoreStage.RESTORING_DATA
    }

    private suspend fun <T> runOperation(block: suspend () -> T): T =
        withContext(Dispatchers.IO) {
            StorageOperationGate.run {
                operations.withLock {
                    synchronized(restoreCancellationLock) { restoreCancellationRequested = false }
                    try {
                        block()
                    } catch (error: OutOfMemoryError) {
                        Log.error("BackupManager", "Backup exceeded heap; maxHeapBytes=${Runtime.getRuntime().maxMemory()}", error)
                        throw BackupMemoryException(error)
                    } finally {
                        synchronized(restoreCancellationLock) { mutableRestoreCancellable.value = false }
                        mutableSnapshotStage.value = null
                        mutableRestoreStage.value = RestoreStage.IDLE
                    }
                }
            }
        }

    enum class ImportKind { BACKUP, PROFILE }

    data class ImportResult(
        val kind: ImportKind,
        val profileCount: Int,
        val iconCount: Int,
        /** Id of the newly created profile for [ImportKind.PROFILE] imports. */
        val importedProfileId: Long? = null
    )

    data class ImportInspection(
        val kind: ImportKind,
        val fingerprint: String,
        val backup: BackupPreview? = null
    )

    data class BackupPreview(
        val appVersion: String,
        val exportedAt: Long,
        val profiles: List<ProfilePreview>,
        val activeWatchRules: Int,
        val completedWatchRules: Int,
        val savedStyles: Int,
        val uploadedImages: Int
    ) {
        val iconCount: Int = profiles.sumOf { it.iconCount }
    }

    data class ProfilePreview(val name: String, val iconCount: Int)

    // ---- Export --------------------------------------------------------------------

    /** Whether a saved profile references at least one personal Icon Pack Studio export. */
    suspend fun profileUsesIconPackStudio(profileId: Long): Boolean =
        packRepo.getAll(profileId).usesIconPackStudioExport()

    /** Whether any saved profile except [excludedProfileId] references an IPS export. */
    suspend fun otherProfilesUseIconPackStudio(excludedProfileId: Long): Boolean =
        packRepo.getAllProfilesApplications()
            .asSequence()
            .filterNot { it.profileId == excludedProfileId }
            .usesIconPackStudioExport()

    suspend fun exportBackup(uri: Uri) = exportBackup {
        context.contentResolver.openOutputStream(uri) ?: throw IOException("Cannot open $uri for writing")
    }

    suspend fun exportProfile(profileId: Long, uri: Uri) = exportProfile(profileId) {
        context.contentResolver.openOutputStream(uri) ?: throw IOException("Cannot open $uri for writing")
    }

    suspend fun exportBackup(open: () -> OutputStream) {
        val prepared = prepareBackupExport()
        writePreparedExport(prepared, open)
    }

    suspend fun exportProfile(profileId: Long, open: () -> OutputStream) {
        val prepared = prepareProfileExport(profileId)
        writePreparedExport(prepared, open)
    }

    class PreparedExport internal constructor(internal val directory: File, internal val archive: File) {
        internal var closed = false
    }

    private suspend fun prepareExport(operation: suspend () -> PreparedExport): PreparedExport {
        var prepared: PreparedExport? = null
        try {
            return runOperation { operation().also { prepared = it } }
        } catch (error: Throwable) {
            prepared?.let { discardExport(it) }
            throw error
        }
    }

    suspend fun discardExport(prepared: PreparedExport) = withContext(NonCancellable + Dispatchers.IO) {
        if (!prepared.closed) {
            prepared.closed = true
            BackupStaging.release(prepared.directory)
        }
    }

    suspend fun writePreparedExport(prepared: PreparedExport, uri: Uri) = writePreparedExport(prepared) {
        context.contentResolver.openOutputStream(uri) ?: throw IOException("Cannot open $uri for writing")
    }

    suspend fun writePreparedExport(prepared: PreparedExport, open: () -> OutputStream) {
        try {
            withContext(Dispatchers.IO) {
                check(!prepared.closed) { "Prepared export has expired" }
                currentCoroutineContext().ensureActive()
                // Only immutable local bytes travel to SAF. No storage or profile lock is held.
                open().use { output ->
                    prepared.archive.inputStream().use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                }
            }
        } finally { discardExport(prepared) }
    }

    suspend fun prepareBackupExport(): PreparedExport = prepareExport {
        // The storage gate freezes profile commits, watch, prefs, gallery and signing
        // writers until the complete archive has copied every live input.
        val prefs = context.dataStore.getPreferencesAfterPendingWrites()
        val activeId = prefs[ActiveProfileIdKey] ?: DEFAULT_PROFILE_ID
        packRepo.profile(activeId)?.let {
            packRepo.updateProfile(it.copy(prefsSnapshot = prefs.snapshotProfilePrefs()))
        }

        val allIcons = packRepo.getAllProfilesApplications()
        val iconsByProfile = allIcons.groupBy { it.profileId }
        val rulesByProfile = watchRepo.getAllRules()
            .groupBy { it.rule.profileId }
        val data = BackupData(
            profiles = packRepo.profiles().map { profile ->
                BackupProfile(
                    profile = profile,
                    icons = iconsByProfile[profile.id].orEmpty(),
                    watchRules = rulesByProfile[profile.id].orEmpty().map { it.toBackupRule() }
                )
            },
            prefs = prefs.asMap().mapNotNull { (key, value) ->
                if (key.name in DEVICE_LOCAL_PREF_NAMES) return@mapNotNull null
                BackupPref.of(value)?.let { key.name to it }
            }.toMap(),
            packLabels = packLabelsFor(allIcons.mapNotNull { it.sourcePackName.ifEmpty { null } }.toSet()),
            colorPresets = packRepo.allColorPresets().map {
                BackupColorPreset(it.name, it.style, it.createdAt)
            },
            modifierPresets = packRepo.allModifierPresets().map {
                BackupModifierPreset(
                    it.name,
                    it.payload,
                    it.schemaVersion,
                    it.createdAt,
                    it.lastUsedAt
                )
            }
        )

        val files = buildMap {
            context.filesDir.resolve(IconPackBuilder.KEYSTORE_FILE_NAME)
                .takeIf { it.exists() }
                ?.let { keystore ->
                    put(KEYSTORE_ENTRY, keystore)
                    // A failed first build can leave a password without a keystore. That
                    // orphan is not a signing identity and must not travel on its own.
                    PackKeystore.passwordFile(context.filesDir)
                        .takeIf { it.exists() }
                        ?.let { put(KEYSTORE_PASSWORD_ENTRY, it) }
                }
            for (file in UploadedImageStore.list(context)) {
                put("$UPLOADS_DIR/${file.name}", file)
            }
        }
        val availableInputs = IconModifierInputStore.directory(context).listFiles().orEmpty()
            .filter { it.isFile && IconModifierInputStore.validName(it.name) }.associateBy { it.name }
        val (exportData, inputFiles) = prepareModifierInputs(data, availableInputs)
        exportArchive(KIND_BACKUP, BackupValidator.prepare(exportData, ImportKind.BACKUP, BackupCodec.FORMAT_VERSION), files +
            inputFiles.mapKeys { (name, _) -> "${IconModifierInputStore.ARCHIVE_DIR}/$name" })
    }

    /**
     * Exports one profile for sharing. Every icon travels with its image data; pack-sourced
     * icons additionally get a `{pack, drawable}` reference so the importer can re-materialize
     * them from an installed copy. Whether paid-pack icons may be USED is enforced on the
     * importing device at load time, not here — so the export works fully offline.
     * No uploads or keystore travel with a share.
     */
    suspend fun prepareProfileExport(profileId: Long): PreparedExport = prepareExport {
        val prefs = context.dataStore.getPreferencesAfterPendingWrites()
        val activeId = prefs[ActiveProfileIdKey] ?: DEFAULT_PROFILE_ID
        if (profileId == activeId) {
            packRepo.profile(profileId)?.let {
                packRepo.updateProfile(it.copy(prefsSnapshot = prefs.snapshotProfilePrefs()))
            }
        }
        val profile = packRepo.profile(profileId) ?: throw IOException("Profile $profileId does not exist")
        val icons = packRepo.getAll(profileId)
        val rules = watchRepo.getAllRules()
            .filter { it.rule.profileId == profileId && !it.rule.completed }
            .map { it.toBackupRule() }

        val sourcePacks = icons.mapNotNull { it.sourcePackName.ifEmpty { null } }.toSet()
        val exportIcons = icons.map { saved ->
            // Sharing carries the reviewed image, never a hidden pre-edit upload/source.
            val icon = saved.copy(modifierInput = "")
            if (icon.sourcePackName.isNotEmpty() && icon.sourceDrawableName.isEmpty()) {
                icon.copy(
                    // Bulk-refresh icons are exactly the appfilter-mapped ones, so this
                    // resolution is faithful; a hand-picked alternate degrades to the
                    // pack's default icon for the app.
                    sourceDrawableName = appFilterDrawableName(icon.sourcePackName, icon.packageName, icon.activityName) ?: ""
                )
            } else icon
        }
        val data = BackupData(
            profiles = listOf(BackupProfile(profile, exportIcons, rules)),
            prefs = emptyMap(),
            packLabels = packLabelsFor(sourcePacks)
        )

        exportArchive(KIND_PROFILE, BackupValidator.prepare(data, ImportKind.PROFILE, BackupCodec.FORMAT_VERSION))
    }

    private suspend fun exportArchive(
        kind: String,
        data: BackupData,
        files: Map<String, File> = emptyMap()
    ): PreparedExport {
        val directory = BackupStaging.create(stagingRoot(), "backup-export-")
        val dataFile = File(directory, DATA_ENTRY)
        try {
            ensureBackupSpace(directory)
            val coroutineContext = currentCoroutineContext()
            FileOutputStream(dataFile).use { output ->
                var bytes = 0L
                var nextSpaceCheck = 0L
                val bounded = object : OutputStream() {
                    override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
                    override fun write(buffer: ByteArray, offset: Int, count: Int) {
                        coroutineContext.ensureActive()
                        bytes += count
                        if (bytes > BackupArchiveLimits.MAX_DATA_BYTES) throw BackupSizeException()
                        if (bytes >= nextSpaceCheck) {
                            ensureBackupSpace(directory, 1024 * 1024L)
                            nextSpaceCheck = bytes + 1024 * 1024
                        }
                        output.write(buffer, offset, count)
                    }
                }
                val writer = OutputStreamWriter(bounded, Charsets.UTF_8).buffered()
                writeBackupData(data, writer, BackupCodec.FORMAT_VERSION)
                writer.flush()
                output.fd.sync()
            }
            val archive = File(directory, "archive.zip")
            writeBackupArchive({
                val output = FileOutputStream(archive)
                object : OutputStream() {
                    private var bytes = 0L
                    private var nextSpaceCheck = 0L
                    override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
                    override fun write(buffer: ByteArray, offset: Int, count: Int) {
                        coroutineContext.ensureActive()
                        bytes += count
                        if (bytes > BackupArchiveLimits.MAX_ARCHIVE_BYTES) throw BackupSizeException()
                        if (bytes >= nextSpaceCheck) {
                            ensureBackupSpace(directory, 1024 * 1024L)
                            nextSpaceCheck = bytes + 1024 * 1024L
                        }
                        output.write(buffer, offset, count)
                    }
                    override fun close() = output.close()
                }
            }, manifestJson(kind), dataFile, files)
            dataFile.delete()
            return PreparedExport(directory, archive)
        } catch (error: OutOfMemoryError) {
            Log.error("BackupManager", "Out of memory exporting; profiles=${data.profiles.size}, icons=${data.profiles.sumOf { it.icons.size }}, dataBytes=${dataFile.length()}", error)
            BackupStaging.release(directory)
            throw error
        } catch (error: Throwable) {
            BackupStaging.release(directory)
            throw error
        }
    }

    private fun stagingRoot(): File = File(context.filesDir, "backup-imports")

    private fun Sequence<DbApplication>.usesIconPackStudioExport(): Boolean =
        any { isIconPackStudioExport(it.sourcePackName) }

    private fun Iterable<DbApplication>.usesIconPackStudioExport(): Boolean =
        asSequence().usesIconPackStudioExport()

    // ---- Import --------------------------------------------------------------------

    /** Fully validates a picked file and builds the summary shown before a destructive restore. */
    suspend fun inspectFile(uri: Uri): ImportInspection = inspectFile {
        context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open $uri for reading")
    }

    suspend fun inspectFile(open: () -> InputStream): ImportInspection = runOperation {
        readArchive(open, inspectionOnly = true).use(::inspectArchive)
    }

    class PreparedImport internal constructor(
        internal val staged: StagedBackupArchive,
        val inspection: ImportInspection
    ) {
        internal var closed = false
    }

    suspend fun prepareImport(uri: Uri): PreparedImport {
        var prepared: PreparedImport? = null
        try {
            return runOperation {
                val archive = readArchive(inspectionOnly = true, open = {
                    context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open $uri for reading")
                })
                try {
                    PreparedImport(archive.staged, inspectArchive(archive)).also { prepared = it }
                } catch (error: Throwable) {
                    archive.close()
                    throw error
                }
            }
        } catch (error: Throwable) {
            // withContext can cancel the handoff after staging succeeded on IO.
            prepared?.let { discardImport(it) }
            throw error
        }
    }

    suspend fun discardImport(prepared: PreparedImport) = withContext(NonCancellable + Dispatchers.IO) {
        operations.withLock { closePrepared(prepared) }
    }

    suspend fun importPrepared(
        prepared: PreparedImport,
        commit: suspend (suspend () -> ImportResult) -> ImportResult = ::commitImportDirectly
    ): ImportResult = runOperation {
        check(!prepared.closed) { "Prepared import has expired" }
        mutableRestoreStage.value = RestoreStage.VALIDATING_SAVED_STATE
        try {
            readContents(prepared.staged).use { archive ->
                if (kindOf(archive.manifest) != prepared.inspection.kind) throw IOException("Prepared file kind changed")
                applyArchive(archive, commit)
            }
        } finally {
            closePrepared(prepared)
        }
    }

    private fun closePrepared(prepared: PreparedImport) {
        if (!prepared.closed) {
            prepared.closed = true
            prepared.staged.close()
        }
    }

    private fun inspectArchive(archive: ArchiveContents): ImportInspection {
        val kind = kindOf(archive.manifest)
        if (kind == ImportKind.PROFILE) return ImportInspection(kind, archive.fingerprint)
        val rules = archive.data.profiles.flatMap { it.watchRules }
        return ImportInspection(
            kind = kind,
            fingerprint = archive.fingerprint,
            backup = BackupPreview(
                appVersion = archive.manifest.optString("appVersion"),
                exportedAt = archive.manifest.optLong("exportedAt"),
                profiles = archive.data.profiles.map { ProfilePreview(it.profile.name, it.icons.size) },
                activeWatchRules = rules.count { !it.completed },
                completedWatchRules = rules.count { it.completed },
                savedStyles = archive.data.colorPresets.size + archive.data.modifierPresets.size,
                uploadedImages = archive.uploadedImages.size
            )
        )
    }

    suspend fun importFile(
        uri: Uri,
        expectedKind: ImportKind? = null,
        expectedFingerprint: String? = null,
        commit: suspend (suspend () -> ImportResult) -> ImportResult = ::commitImportDirectly
    ): ImportResult = importPreparedFile(
        open = { context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open $uri for reading") },
        expectedKind = expectedKind,
        expectedFingerprint = expectedFingerprint,
        commit = commit
    )

    suspend fun importFile(open: () -> InputStream): ImportResult =
        importPreparedFile(open, null, null, ::commitImportDirectly)

    suspend fun importBackup(uri: Uri): ImportResult = importFile(uri, ImportKind.BACKUP)

    suspend fun importBackup(open: () -> InputStream): ImportResult =
        importPreparedFile(open, ImportKind.BACKUP, null, ::commitImportDirectly)

    private suspend fun commitImportDirectly(commit: suspend () -> ImportResult): ImportResult = commit()

    private suspend fun importPreparedFile(
        open: () -> InputStream,
        expectedKind: ImportKind?,
        expectedFingerprint: String?,
        commit: suspend (suspend () -> ImportResult) -> ImportResult
    ): ImportResult = runOperation {
        readArchive(open).use { archive ->
            val kind = kindOf(archive.manifest)
            if (expectedKind != null && kind != expectedKind) throw IOException("File kind changed after inspection")
            if (expectedFingerprint != null && archive.fingerprint != expectedFingerprint) {
                throw IOException("File changed after inspection")
            }
            applyArchive(archive, commit)
        }
    }

    private suspend fun applyArchive(
        archive: ArchiveContents,
        commit: suspend (suspend () -> ImportResult) -> ImportResult
    ): ImportResult {
        check(!archive.inspectionOnly) { "Inspection metadata cannot be imported" }
        currentCoroutineContext().ensureActive()
        return when (kindOf(archive.manifest)) {
            ImportKind.BACKUP -> restoreBackup(archive, commit)
            ImportKind.PROFILE -> {
                val snapshot = captureCurrentState()
                try {
                    currentCoroutineContext().ensureActive()
                    ensureRecoverySpace(snapshot, archive.staged.entries[DATA_ENTRY]?.length() ?: 0L)
                    commitWithRecovery(snapshot, commit) { importProfile(archive.data) }
                } finally { recovery.discard(snapshot) }
            }
        }
    }

    private suspend fun readArchive(open: () -> InputStream, inspectionOnly: Boolean = false): ArchiveContents {
        return readContents(StagedBackupArchive.read(stagingRoot(), open), inspectionOnly)
    }

    private suspend fun readContents(staged: StagedBackupArchive, inspectionOnly: Boolean = false): ArchiveContents {
        try {
            val manifest = parseBackupJson(staged.entries[MANIFEST_ENTRY]?.readText(Charsets.UTF_8)
                ?: throw IOException("Not a Renkin file (no manifest)"))
            val format = manifest.opt("format")
            if (format !is Int || format < 1) throw IOException("Invalid backup format version")
            if (format > BackupCodec.FORMAT_VERSION) throw IOException("File was made by a newer app version")
            val kind = kindOf(manifest)
            val dataFile = staged.entries[DATA_ENTRY] ?: throw IOException("File has no data entry")
            val parsedData = BackupValidator.prepare(
                dataFile.reader(Charsets.UTF_8).use {
                    if (inspectionOnly) readBackupInspectionData(it, format) else readBackupData(it, format)
                }, kind, format
            )
            val uploads = staged.entries.filterKeys { name -> name.startsWith("$UPLOADS_DIR/") }.map { (name, file) ->
                val filename = name.removePrefix("$UPLOADS_DIR/")
                if ('/' in filename || !filename.endsWith(".png")) throw IOException("Invalid uploaded image entry")
                filename to file
            }.toMap()
            val keystore = staged.entries[KEYSTORE_ENTRY]
            val password = staged.entries[KEYSTORE_PASSWORD_ENTRY]
            val inputs = staged.entries.filterKeys { it.startsWith("${IconModifierInputStore.ARCHIVE_DIR}/") }
                .map { (name, file) ->
                    val filename = name.removePrefix("${IconModifierInputStore.ARCHIVE_DIR}/")
                    if (!IconModifierInputStore.validName(filename)) throw IOException("Invalid modifier input entry")
                    filename to file
                }.toMap()
            if (password != null && keystore == null) throw IOException("Keystore password has no keystore")
            if (kind == ImportKind.PROFILE && (uploads.isNotEmpty() || inputs.isNotEmpty() || keystore != null || password != null)) {
                throw IOException("Shared profile contains device files")
            }
            val (data, inputFiles) = prepareModifierInputs(parsedData, if (kind == ImportKind.BACKUP) inputs else emptyMap())
            return ArchiveContents(staged, manifest, data, uploads, keystore, password, inputFiles, inspectionOnly)
        } catch (error: Throwable) {
            if (error is OutOfMemoryError) Log.error("BackupManager", "Out of memory decoding; dataBytes=${staged.entries[DATA_ENTRY]?.length()}", error)
            staged.close()
            throw error
        }
    }

    private suspend fun prepareModifierInputs(
        data: BackupData,
        available: Map<String, File>
    ): Pair<BackupData, Map<String, File>> {
        val verified = mutableMapOf<String, Boolean>()
        val files = mutableMapOf<String, File>()
        val profiles = data.profiles.map { profile ->
            profile.copy(icons = profile.icons.map { icon ->
                val artwork = StoredIconModifierInput.artwork(icon.modifierInput)
                val name = artwork?.let { "${it.hash}.input" }
                val source = name?.let(available::get)
                val valid = if (artwork == null || name == null || source == null) false else {
                    verified[name] ?: IconModifierInputStore.validFile(source, artwork.hash).also { verified[name] = it }
                }
                if (valid) {
                    files[requireNotNull(name)] = requireNotNull(source)
                    icon
                } else {
                    if (icon.modifierInput.isNotEmpty()) Log.info("BackupManager", "Discarded missing or invalid optional modifier input")
                    icon.copy(modifierInput = "")
                }
            })
        }
        return data.copy(profiles = profiles) to files
    }

    private class ArchiveContents(
        val staged: StagedBackupArchive,
        val manifest: JSONObject,
        val data: BackupData,
        val uploadedImages: Map<String, File>,
        val keystore: File?,
        val password: File?,
        val modifierInputs: Map<String, File>,
        val inspectionOnly: Boolean
    ) : Closeable {
        val fingerprint: String get() = staged.fingerprint
        override fun close() = staged.close()
    }

    private fun kindOf(manifest: JSONObject): ImportKind = when (manifest.optString("kind")) {
        KIND_BACKUP -> ImportKind.BACKUP
        KIND_PROFILE -> ImportKind.PROFILE
        else -> throw IOException("Unknown file kind")
    }

    suspend fun recoverPendingRestore() = runOperation {
        val snapshot = recovery.pendingSnapshot()
        if (snapshot != null) {
            StorageOperationGate.reportRecoveryRunning()
            withContext(NonCancellable) {
                Log.info("BackupManager", "Recovering interrupted storage restore")
                restoreSnapshot(snapshot)
                recovery.clearPending()
            }
        }
        recovery.syncMetadata()
        recovery.cleanup()
        BackupStaging.cleanAfterProcessStart(context.filesDir)
        if (!inputCleanupComplete) {
            IconModifierInputStore.cleanupAfterProcessStart(
                context,
                packRepo.modifierInputDocuments() + editorDraftInvalidation.retainedModifierInputs()
            )
            inputCleanupComplete = true
        }
        refreshAbandonedRecoveryAvailability()
        try {
            refreshPreviousStateAvailability()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            mutablePreviousStateAvailable.value = false
            Log.error("BackupManager", "Retained undo snapshot is unavailable; preserved recovery storage", error)
        }
    }

    suspend fun restorePreviousState(commit: suspend (suspend () -> Unit) -> Unit) = runOperation {
        allowRestorePreparationCancellation()
        mutableRestoreStage.value = RestoreStage.VALIDATING_SAVED_STATE
        val previous = recovery.previousSnapshot() ?: throw IOException("No previous saved state")
        recovery.validate(previous)
        checkSnapshotCompatibility(previous)
        val snapshot = captureCurrentState()
        try {
            currentCoroutineContext().ensureActive()
            ensureRecoverySpace(snapshot, 0)
            commitWithRecovery(snapshot, commit) { restoreSnapshot(previous) }
        } finally { recovery.discard(snapshot) }
    }

    suspend fun abandonPendingRecovery() = runOperation {
        withContext(NonCancellable) {
            editorDraftInvalidation.duringStorageRecovery()
            recovery.preserveAbandonedRecovery()
            refreshAbandonedRecoveryAvailability()
            StorageOperationGate.markStorageReplaced()
            try {
                ProfileStyleAccess.repairInvalidDocuments { recovery.preserveCurrentProfileStyles(packRepo) }
            } finally { refreshAbandonedRecoveryAvailability() }
            ProfileStyleAccess.initialize(force = true)
            mutablePreviousStateAvailable.value = false
            mutablePreviousStateStored.value = false
        }
    }

    suspend fun discardAbandonedRecovery() = runOperation {
        try {
            withContext(NonCancellable) { recovery.discardAbandonedRecovery() }
        } finally { refreshAbandonedRecoveryAvailability() }
    }

    private fun refreshAbandonedRecoveryAvailability() {
        try {
            mutableAbandonedRecoveryAvailable.value = recovery.hasAbandonedRecovery()
        } catch (error: Exception) {
            Log.error("BackupManager", "Cannot list abandoned recovery copies", error)
        }
    }

    suspend fun discardPreviousState() = runOperation {
        withContext(NonCancellable) { recovery.discardPreviousState() }
        refreshPreviousStateAvailability()
    }

    private suspend fun refreshPreviousStateAvailability() {
        mutablePreviousStateStored.value = recovery.hasPreviousState()
        mutablePreviousStateAvailable.value = false
        val snapshot = recovery.previousSnapshot() ?: return
        try {
            checkSnapshotCompatibility(snapshot)
            mutablePreviousStateStored.value = true
            mutablePreviousStateAvailable.value = true
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { Log.error("BackupManager", "Previous state is incompatible; retained for explicit discard", error) }
    }

    private suspend fun captureCurrentState(): BackupRecoveryStore.Snapshot {
        allowRestorePreparationCancellation()
        mutableRestoreStage.value = RestoreStage.SAVING_CURRENT_STATE
        return recovery.capture(packRepo, watchRepo) { mutableSnapshotStage.value = it }
    }

    private suspend fun checkSnapshotCompatibility(snapshot: BackupRecoveryStore.Snapshot) {
        packRepo.checkRecoveryCompatibility(File(snapshot.directory, "pack.json"))
        watchRepo.checkRecoveryCompatibility(File(snapshot.directory, "watch.json"))
    }

    private fun ensureRecoverySpace(snapshot: BackupRecoveryStore.Snapshot, incomingDataBytes: Long) {
        val databaseBytes = snapshot.directory.resolve("pack.json").length() + snapshot.directory.resolve("watch.json").length()
        val largestFile = snapshot.files.filter { it.startsWith("gallery/") || it.startsWith("signing/") ||
            it.startsWith("${IconModifierInputStore.ARCHIVE_DIR}/") }
            .maxOfOrNull { File(snapshot.directory, it).length() } ?: 0L
        ensureBackupSpace(context.filesDir, databaseBytes + incomingDataBytes + largestFile)
    }

    private suspend fun restoreSnapshot(snapshot: BackupRecoveryStore.Snapshot) {
        recovery.validate(snapshot)
        checkSnapshotCompatibility(snapshot)
        editorDraftInvalidation.duringStorageRecovery()
        mutableRestoreStage.value = RestoreStage.RESTORING_DATA
        StorageOperationGate.markStorageReplaced()
        // The pending marker survives every step. A failed or interrupted rollback is retried
        // from these immutable files before any subsequent storage operation can proceed.
        clearWatchNotifications()
        packRepo.restoreRecoverySnapshot(File(snapshot.directory, "pack.json"))
        watchRepo.restoreRecoverySnapshot(File(snapshot.directory, "watch.json"))
        val prefs = File(snapshot.directory, "prefs.json").reader(Charsets.UTF_8).use {
            readBackupData(it, RECOVERY_PREFS_FORMAT).prefs
        }
        restorePrefs(prefs, preserveDeviceLocal = false)
        ProfileStyleAccess.initialize(force = true)
        mutableRestoreStage.value = RestoreStage.RESTORING_FILES
        recovery.restoreFiles(snapshot)
        packRepo.flushRecoveryWrites()
        watchRepo.flushRecoveryWrites()
    }

    private suspend fun clearWatchNotifications() {
        val ids = watchRepo.getAllRules().flatMap { rule -> rule.suggestions.map { it.id } }
        try {
            RenkinNotifications().cancelIconAvailable(context, ids, cancelSummary = true)
        } catch (error: Exception) {
            Log.error("BackupManager", "Cannot clear notifications for replaced watch records", error)
        }
    }

    private suspend fun <T> commitWithRecovery(
        snapshot: BackupRecoveryStore.Snapshot,
        commit: suspend (suspend () -> T) -> T,
        operation: suspend () -> T
    ): T {
        currentCoroutineContext().ensureActive()
        beginRestoreCommit()
        return withContext(NonCancellable) {
            // Only the durable phase may invalidate the live profile session. Preparation
            // and an accepted Cancel must leave in-memory icons, tokens and undo untouched.
            // Fail before the commit callback can invalidate the provider session or start recovery.
            editorDraftInvalidation.beforeStorageReplacement()
            commit {
                var activated = false
                try {
                    recovery.activate(snapshot)
                    activated = true
                    StorageOperationGate.markStorageReplaced()
                    mutableRestoreStage.value = RestoreStage.RESTORING_DATA
                    val result = operation()
                    ProfileStyleAccess.initialize(force = true)
                    packRepo.flushRecoveryWrites()
                    watchRepo.flushRecoveryWrites()
                    recovery.complete(snapshot)
                    mutablePreviousStateStored.value = true
                    mutablePreviousStateAvailable.value = true
                    result
                } catch (error: Throwable) {
                    if (activated) {
                        try {
                            recovery.ensurePending(snapshot)
                            restoreSnapshot(snapshot)
                            recovery.clearPending()
                        } catch (rollbackError: Throwable) {
                            error.addSuppressed(rollbackError)
                            StorageOperationGate.requireRecovery(error)
                            Log.error("BackupManager", "Rollback could not finish; recovery snapshot retained", error)
                            throw BackupRecoveryRequiredException(error)
                        }
                    } else {
                        try {
                            recovery.clearPending()
                        } catch (cleanupError: Throwable) {
                            error.addSuppressed(cleanupError)
                            StorageOperationGate.requireRecovery(error)
                            throw BackupRecoveryRequiredException(error)
                        }
                    }
                    throw error
                }
            }
        }
    }

    private suspend fun restoreBackup(
        archive: ArchiveContents,
        commit: suspend (suspend () -> ImportResult) -> ImportResult
    ): ImportResult {
        val data = archive.data
        val uploadsDir = UploadedImageStore.directory(context)
        val inputsDir = IconModifierInputStore.directory(context)
        if (archive.modifierInputs.isNotEmpty() && !inputsDir.isDirectory && !inputsDir.mkdirs()) {
            throw IOException("Cannot create modifier input restore storage")
        }
        val replacements = buildMap<File, File?> {
            archive.keystore?.let { put(context.filesDir.resolve(IconPackBuilder.KEYSTORE_FILE_NAME), it) }
            if (archive.keystore != null) put(PackKeystore.passwordFile(context.filesDir), archive.password)
            for ((name, file) in archive.uploadedImages) put(File(uploadsDir, name), file)
            for (file in UploadedImageStore.list(context)) {
                if (file.name !in archive.uploadedImages) put(file, null)
            }
            for ((name, file) in archive.modifierInputs) put(File(inputsDir, name), file)
            for (file in inputsDir.listFiles().orEmpty()) {
                if (file.isFile && IconModifierInputStore.validName(file.name) && file.name !in archive.modifierInputs) put(file, null)
            }
        }
        val incomingDataBytes = archive.staged.entries[DATA_ENTRY]?.length() ?: 0L
        ensureBackupRestoreSpace(context, incomingDataBytes)
        var snapshot = captureCurrentState()
        return try {
            BackupFileTransaction.prepare(context.filesDir, replacements) { batch ->
                snapshot = recovery.attachFileBatch(snapshot, batch)
            }.use { files ->
                currentCoroutineContext().ensureActive()
                ensureRecoverySpace(snapshot, incomingDataBytes)
                try {
                    val result = commitWithRecovery(snapshot, commit) {
                        clearWatchNotifications()
                        UploadedImageStore.invalidatePendingTrash()
                        mutableRestoreStage.value = RestoreStage.RESTORING_FILES
                        files.apply()
                        mutableRestoreStage.value = RestoreStage.RESTORING_DATA
                        packRepo.replaceEverything(
                            data.profiles.map { it.profile },
                            data.profiles.flatMap { it.icons }
                        )
                        watchRepo.replaceAllRules(data.profiles.flatMap { bp ->
                            bp.watchRules.map { it.toImport(bp.profile.id) }
                        })
                        WatchChecker(context, iconPackCatalog, watchRepo).initializeMissingBaselines()
                        // Replace-all, like everything else in a full restore. Archives written before saved
                        // colours existed carry none, which correctly clears a library the user is replacing.
                        packRepo.replaceColorPresets(
                            data.colorPresets.map { ColorPreset(name = it.name, style = it.style, createdAt = it.createdAt) }
                        )
                        packRepo.replaceModifierPresets(
                            data.modifierPresets.map {
                                ModifierPreset(
                                    name = it.name,
                                    payload = it.payload,
                                    schemaVersion = it.schemaVersion,
                                    createdAt = it.createdAt,
                                    lastUsedAt = it.lastUsedAt
                                )
                            }
                        )
                        restorePrefs(data.prefs)
                        // The verdict cache is per-device truth (which packs are owned/priced HERE), not part of
                        // the backup — re-derive it from what's actually installed so a restored profile locks
                        // exactly like a fresh import: packs not installed here stay locked until installed.
                        packRepo.resetVerdictsToInstalled(
                            runCatching { iconPackCatalog.installedIconPacks() }.getOrDefault(emptyList())
                        )
                        storePackLabels(data.packLabels)
                        files.sync()
                        ImportResult(ImportKind.BACKUP, data.profiles.size, data.profiles.sumOf { it.icons.size })
                    }
                    files.finish()
                    result
                } catch (error: Throwable) {
                    if (error !is BackupRecoveryRequiredException) files.finish()
                    throw error
                }
            }
        } finally { recovery.discard(snapshot) }
    }

    /** A shared profile always lands as a NEW profile — imports never overwrite anything. */
    private suspend fun importProfile(data: BackupData): ImportResult {
        val bp = data.profiles.firstOrNull() ?: throw IOException("File contains no profile")
        // A numbered suffix keeps repeated imports of the same share tellable apart.
        val name = uniqueProfileName(bp.profile.name, packRepo.profiles().map { it.name }.toSet())
        val newId = packRepo.createProfile(
            // Fresh identity (the id also names the built pack's package) and fresh flags:
            // the icons are saved-but-not-built, and the missing-pack dialog choice is local.
            bp.profile.copy(id = 0, name = name, hasUnbuiltChanges = true, hideMissingPackWarning = false)
        )
        packRepo.replaceAll(newId, bp.icons.map { it.copy(profileId = newId) })
        watchRepo.insertRules(bp.watchRules.map { it.toImport(newId) })
        WatchChecker(context, iconPackCatalog, watchRepo).initializeMissingBaselines()
        storePackLabels(data.packLabels)
        return ImportResult(ImportKind.PROFILE, 1, bp.icons.size, importedProfileId = newId)
    }

    private fun uniqueProfileName(base: String, existing: Set<String>): String {
        if (base !in existing) return base
        var n = 2
        while ("$base ($n)" in existing) n++
        return "$base ($n)"
    }

    /**
     * Remembers the exporting device's pack names so the missing-packs dialog can name a
     * pack this device has never seen. Only fills empty labels — never touches verdicts or
     * the ownership flag (the file is untrusted).
     */
    private suspend fun storePackLabels(labels: Map<String, String>) {
        if (labels.isEmpty()) return
        val existing = packRepo.verdicts(labels.keys.toList())
        packRepo.upsertVerdicts(labels.mapNotNull { (pack, label) ->
            val row = existing[pack] ?: PackVerdict(pack)
            if (row.label.isEmpty() && label.isNotEmpty()) row.copy(label = label) else null
        })
    }

    // ---- Helpers ---------------------------------------------------------------------

    private suspend fun RuleWithDetails.toBackupRule(): BackupWatchRule = BackupWatchRule(
        watchAllPacks = rule.watchAllPacks,
        completed = rule.completed,
        createdAt = rule.createdAt,
        completedAt = rule.completedAt,
        apps = apps.map { AppComponent(it.packageName, it.activityName) },
        packs = packs.map { it.iconPackPackage },
        baselines = watchRepo.getStatesForRule(rule.id).map {
            BackupWatchState(
                packageName = it.packageName,
                activityName = it.activityName,
                iconPackPackage = it.iconPackPackage,
                lastPackVersionCode = it.lastPackVersionCode,
                lastIconName = it.lastIconName,
                lastIconHash = it.lastIconHash,
                lastCheckedAt = it.lastCheckedAt
            )
        },
        suggestions = suggestions.map { suggestion ->
            BackupWatchSuggestion(
                packageName = suggestion.packageName,
                activityName = suggestion.activityName,
                createdAt = suggestion.createdAt,
                candidates = watchRepo.getCandidates(suggestion.id).map {
                    BackupWatchCandidate(it.iconPackPackage, it.drawableName, it.iconHash)
                }
            )
        }
    )

    private fun BackupWatchRule.toImport(profileId: Long) = WatchRuleImport(
        profileId = profileId,
        watchAllPacks = watchAllPacks,
        completed = completed,
        createdAt = createdAt,
        completedAt = completedAt,
        apps = apps,
        packs = packs,
        baselines = baselines.map {
            BaselineInput(
                packageName = it.packageName,
                activityName = it.activityName,
                iconPackPackage = it.iconPackPackage,
                lastPackVersionCode = it.lastPackVersionCode,
                lastIconName = it.lastIconName,
                lastIconHash = it.lastIconHash,
                lastCheckedAt = it.lastCheckedAt
            )
        },
        suggestions = suggestions.map {
            SuggestionImport(
                packageName = it.packageName,
                activityName = it.activityName,
                createdAt = it.createdAt,
                candidates = it.candidates.map { candidate ->
                    CandidateInput(candidate.iconPackPackage, candidate.drawableName, candidate.iconHash)
                }
            )
        }
    )

    /** Display names for [packs]: from the installed copy, else the verdict cache. */
    private suspend fun packLabelsFor(packs: Set<String>): Map<String, String> {
        if (packs.isEmpty()) return emptyMap()
        val installed = runCatching { iconPackCatalog.installedIconPacks() }.getOrDefault(emptyList())
            .associate { it.packageName to it.applicationName }
        val cached = packRepo.verdicts(packs.toList())
        return packs.mapNotNull { pack ->
            val label = installed[pack] ?: cached[pack]?.label?.ifEmpty { null }
            label?.let { pack to it }
        }.toMap()
    }

    /** The drawable name [packPackage]'s appfilter maps to the app's component, if any. */
    private fun appFilterDrawableName(packPackage: String, packageName: String, activityName: String): String? =
        runCatching {
            appManager.appFilterDrawableName(packPackage, InstalledApplication(packageName, activityName, 0))
        }.getOrNull()

    private suspend fun restorePrefs(prefs: Map<String, BackupPref>, preserveDeviceLocal: Boolean = true) {
        context.dataStore.edit { store ->
            val autoBackupInterval = store[AutoBackupIntervalKey].takeIf { preserveDeviceLocal }
            val autoBackupTreeUri = store[AutoBackupTreeUriKey].takeIf { preserveDeviceLocal }
            val lastAutoBackupAt = store[LastAutoBackupAtKey].takeIf { preserveDeviceLocal }
            store.clear()
            for ((name, pref) in prefs) {
                if (preserveDeviceLocal && name in DEVICE_LOCAL_PREF_NAMES) continue
                when (pref.tag) {
                    BackupPref.BOOL -> store[booleanPreferencesKey(name)] = pref.value as Boolean
                    BackupPref.INT -> store[intPreferencesKey(name)] = pref.value as Int
                    BackupPref.LONG -> store[longPreferencesKey(name)] = pref.value as Long
                    BackupPref.FLOAT -> store[floatPreferencesKey(name)] = pref.value as Float
                    BackupPref.DOUBLE -> store[doublePreferencesKey(name)] = pref.value as Double
                    BackupPref.STRING -> store[stringPreferencesKey(name)] = pref.value as String
                    BackupPref.STRING_SET -> store[stringSetPreferencesKey(name)] =
                        (pref.value as Collection<*>).filterIsInstance<String>().toSet()
                }
            }
            autoBackupInterval?.let { store[AutoBackupIntervalKey] = it }
            autoBackupTreeUri?.let { store[AutoBackupTreeUriKey] = it }
            lastAutoBackupAt?.let { store[LastAutoBackupAtKey] = it }
        }
    }

    private fun manifestJson(kind: String): String = JSONObject()
        .put("format", BackupCodec.FORMAT_VERSION)
        .put("kind", kind)
        .put("appVersion", BuildConfig.VERSION_NAME)
        .put("exportedAt", System.currentTimeMillis())
        .toString()

    companion object {
        private val DEVICE_LOCAL_PREF_NAMES = setOf(
            LastWatchCheckAtKey.name,
            AutoBackupIntervalKey.name,
            AutoBackupTreeUriKey.name,
            LastAutoBackupAtKey.name
        )
        private const val MANIFEST_ENTRY = "manifest.json"
        private const val DATA_ENTRY = "data.json"
        private const val KEYSTORE_ENTRY = "keystore/" + IconPackBuilder.KEYSTORE_FILE_NAME
        private const val KEYSTORE_PASSWORD_ENTRY = "keystore/" + PackKeystore.PASSWORD_FILE_NAME
        private const val UPLOADS_DIR = "uploads"
        private const val KIND_BACKUP = "backup"
        private const val KIND_PROFILE = "profile"

        /** Suggested file name for the SAF save dialog (full backup). */
        fun defaultFileName(): String = "renkin-backup-${today()}.renkin"

        /** Suggested file name for a shared profile. */
        fun profileFileName(profileName: String): String {
            val safe = profileName.lowercase()
                .replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "profile" }
            return "renkin-profile-$safe-${today()}.renkin"
        }

        private fun today(): String =
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
    }
}
