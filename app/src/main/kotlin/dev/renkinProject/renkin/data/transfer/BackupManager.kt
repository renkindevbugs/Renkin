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
import dev.renkinProject.renkin.data.InstalledApplication
import dev.renkinProject.renkin.data.LastWatchCheckAtKey
import dev.renkinProject.renkin.data.LastAutoBackupAtKey
import dev.renkinProject.renkin.data.ColorPreset
import dev.renkinProject.renkin.data.ModifierPreset
import dev.renkinProject.renkin.data.PackVerdict
import dev.renkinProject.renkin.data.RenkinPackRepository
import dev.renkinProject.renkin.data.UploadedImageStore
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
import dev.renkinProject.renkin.packages.ApplicationManager
import dev.renkinProject.renkin.packages.IconPackCatalog
import dev.renkinProject.renkin.service.WatchChecker
import dev.renkinProject.renkin.util.Log
import kotlinx.coroutines.Dispatchers
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
 * Import stages and validates the entire archive BEFORE any store is touched. Committing
 * across the databases, preferences and files is not yet crash-atomic. Whether
 * imported icons are usable is decided on the importing device every time they load
 * (PackVerdictManager) — verdicts inside the file are never trusted. After an import the
 * caller reloads the in-memory state and kicks off verdict verification.
 */
class BackupManager(
    private val context: Context,
    private val packRepo: RenkinPackRepository,
    private val watchRepo: WatchRepository,
    private val iconPackCatalog: IconPackCatalog = IconPackCatalog(context)
) {
    private val appManager by lazy { ApplicationManager(context) }
    private val operations = Mutex()

    private suspend fun <T> runOperation(block: suspend () -> T): T =
        withContext(Dispatchers.IO) {
            operations.withLock {
                try {
                    block()
                } catch (error: OutOfMemoryError) {
                    Log.error("BackupManager", "Backup exceeded heap; maxHeapBytes=${Runtime.getRuntime().maxMemory()}", error)
                    throw BackupMemoryException(error)
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

    suspend fun exportBackup(open: () -> OutputStream) = runOperation {
        // The backup mutex excludes restores, not Global Save or watch/gallery writers.
        // A storage-wide snapshot coordinated with those writers belongs to phase 3B.
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
        exportArchive(open, KIND_BACKUP, BackupValidator.prepare(data, ImportKind.BACKUP), files)
    }

    /**
     * Exports one profile for sharing. Every icon travels with its image data; pack-sourced
     * icons additionally get a `{pack, drawable}` reference so the importer can re-materialize
     * them from an installed copy. Whether paid-pack icons may be USED is enforced on the
     * importing device at load time, not here — so the export works fully offline.
     * No uploads or keystore travel with a share.
     */
    suspend fun exportProfile(profileId: Long, open: () -> OutputStream) = runOperation {
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
        val exportIcons = icons.map { icon ->
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

        exportArchive(open, KIND_PROFILE, BackupValidator.prepare(data, ImportKind.PROFILE))
    }

    private suspend fun exportArchive(
        open: () -> OutputStream,
        kind: String,
        data: BackupData,
        files: Map<String, File> = emptyMap()
    ) {
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
                writeBackupData(data, writer)
                writer.flush()
                output.fd.sync()
            }
            writeBackupArchive(open, manifestJson(kind), dataFile, files)
        } catch (error: OutOfMemoryError) {
            Log.error("BackupManager", "Out of memory exporting; profiles=${data.profiles.size}, icons=${data.profiles.sumOf { it.icons.size }}, dataBytes=${dataFile.length()}", error)
            throw error
        } finally {
            BackupStaging.release(directory)
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
        readArchive(open).use(::inspectArchive)
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
                val archive = readArchive {
                    context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open $uri for reading")
                }
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
        currentCoroutineContext().ensureActive()
        return commit {
            currentCoroutineContext().ensureActive()
            when (kindOf(archive.manifest)) {
                ImportKind.BACKUP -> restoreBackup(archive)
                ImportKind.PROFILE -> withContext(NonCancellable) { importProfile(archive.data) }
            }
        }
    }

    private suspend fun readArchive(open: () -> InputStream): ArchiveContents {
        return readContents(StagedBackupArchive.read(stagingRoot(), open))
    }

    private suspend fun readContents(staged: StagedBackupArchive): ArchiveContents {
        try {
            val manifest = parseBackupJson(staged.entries[MANIFEST_ENTRY]?.readText(Charsets.UTF_8)
                ?: throw IOException("Not a Renkin file (no manifest)"))
            val format = manifest.opt("format")
            if (format !is Int || format < 1) throw IOException("Invalid backup format version")
            if (format > BackupCodec.FORMAT_VERSION) throw IOException("File was made by a newer app version")
            val kind = kindOf(manifest)
            val dataFile = staged.entries[DATA_ENTRY] ?: throw IOException("File has no data entry")
            val data = BackupValidator.prepare(dataFile.reader(Charsets.UTF_8).use { readBackupData(it) }, kind)
            val uploads = staged.entries.filterKeys { name -> name.startsWith("$UPLOADS_DIR/") }.map { (name, file) ->
                val filename = name.removePrefix("$UPLOADS_DIR/")
                if ('/' in filename || !filename.endsWith(".png")) throw IOException("Invalid uploaded image entry")
                filename to file
            }.toMap()
            val keystore = staged.entries[KEYSTORE_ENTRY]
            val password = staged.entries[KEYSTORE_PASSWORD_ENTRY]
            if (password != null && keystore == null) throw IOException("Keystore password has no keystore")
            if (kind == ImportKind.PROFILE && (uploads.isNotEmpty() || keystore != null || password != null)) {
                throw IOException("Shared profile contains device files")
            }
            return ArchiveContents(staged, manifest, data, uploads, keystore, password)
        } catch (error: Throwable) {
            if (error is OutOfMemoryError) Log.error("BackupManager", "Out of memory decoding; dataBytes=${staged.entries[DATA_ENTRY]?.length()}", error)
            staged.close()
            throw error
        }
    }

    private class ArchiveContents(
        val staged: StagedBackupArchive,
        val manifest: JSONObject,
        val data: BackupData,
        val uploadedImages: Map<String, File>,
        val keystore: File?,
        val password: File?
    ) : Closeable {
        val fingerprint: String get() = staged.fingerprint
        override fun close() = staged.close()
    }

    private fun kindOf(manifest: JSONObject): ImportKind = when (manifest.optString("kind")) {
        KIND_BACKUP -> ImportKind.BACKUP
        KIND_PROFILE -> ImportKind.PROFILE
        else -> throw IOException("Unknown file kind")
    }

    private suspend fun restoreBackup(archive: ArchiveContents): ImportResult {
        val data = archive.data

        val uploadsDir = UploadedImageStore.directory(context)
        val replacements = buildMap<File, File?> {
            archive.keystore?.let { put(context.filesDir.resolve(IconPackBuilder.KEYSTORE_FILE_NAME), it) }
            if (archive.keystore != null) put(PackKeystore.passwordFile(context.filesDir), archive.password)
            for ((name, file) in archive.uploadedImages) put(File(uploadsDir, name), file)
            for (file in UploadedImageStore.list(context)) {
                if (file.name !in archive.uploadedImages) put(file, null)
            }
        }
        val incomingDataBytes = archive.staged.entries[DATA_ENTRY]?.length() ?: 0L
        ensureBackupRestoreSpace(context, incomingDataBytes)
        BackupFileTransaction.prepare(context.filesDir, replacements).use { files ->
            currentCoroutineContext().ensureActive()
            ensureBackupRestoreSpace(context, incomingDataBytes)
            withContext(NonCancellable) {
                try {
                    files.apply()
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

                    files.finish()
                } catch (error: Throwable) {
                    try {
                        files.rollback()
                    } catch (rollbackError: Exception) {
                        error.addSuppressed(rollbackError)
                    }
                    throw error
                }
            }
        }

        return ImportResult(ImportKind.BACKUP, data.profiles.size, data.profiles.sumOf { it.icons.size })
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

    private suspend fun restorePrefs(prefs: Map<String, BackupPref>) {
        context.dataStore.edit { store ->
            val autoBackupInterval = store[AutoBackupIntervalKey]
            val autoBackupTreeUri = store[AutoBackupTreeUriKey]
            val lastAutoBackupAt = store[LastAutoBackupAtKey]
            store.clear()
            for ((name, pref) in prefs) {
                if (name in DEVICE_LOCAL_PREF_NAMES) continue
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
