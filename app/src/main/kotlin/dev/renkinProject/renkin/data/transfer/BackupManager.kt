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
import dev.renkinProject.renkin.data.ActiveProfileIdKey
import dev.renkinProject.renkin.data.DEFAULT_PROFILE_ID
import dev.renkinProject.renkin.data.DbApplication
import dev.renkinProject.renkin.data.InstalledApplication
import dev.renkinProject.renkin.data.LastWatchCheckAtKey
import dev.renkinProject.renkin.data.PackVerdict
import dev.renkinProject.renkin.data.RawItem
import dev.renkinProject.renkin.data.RenkinPackRepository
import dev.renkinProject.renkin.data.UploadedImageStore
import dev.renkinProject.renkin.data.snapshotProfilePrefs
import dev.renkinProject.renkin.data.toComponentInfo
import dev.renkinProject.renkin.data.watch.AppComponent
import dev.renkinProject.renkin.data.watch.RuleWithDetails
import dev.renkinProject.renkin.data.watch.WatchRepository
import dev.renkinProject.renkin.data.watch.WatchRuleImport
import dev.renkinProject.renkin.dataStore
import dev.renkinProject.renkin.packages.ApplicationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Writes and restores `.renkin` files (a ZIP of `manifest.json`, `data.json` and, for full
 * backups, the upload gallery and the pack-signing keystore). Two kinds share the format:
 *
 *  - **backup** — the whole device: every profile, all settings, uploads, keystore. Import
 *    replaces everything in place.
 *  - **profile** — one profile to share. Icons from packs verified paid (or unverifiable —
 *    fail-closed) carry no image data, only a `{pack, drawable}` reference the importer
 *    resolves from their own installed copy; free/unlisted/non-pack icons embed fully.
 *    Import always creates a NEW profile.
 *
 * Import is all-or-nothing: the file is fully parsed BEFORE any store is touched. Whether
 * imported icons are usable is decided on the importing device every time they load
 * (PackVerdictManager) — verdicts inside the file are never trusted. After an import the
 * caller reloads the in-memory state and kicks off verdict verification.
 */
class BackupManager(
    private val context: Context,
    private val packRepo: RenkinPackRepository,
    private val watchRepo: WatchRepository,
    private val verdictManager: PackVerdictManager = PackVerdictManager(context, packRepo)
) {
    /** Production entry point: uses the shared singleton databases. Tests inject in-memory ones. */
    constructor(context: Context) : this(context, RenkinPackRepository(context), WatchRepository(context))

    private val appManager by lazy { ApplicationManager(context) }

    enum class ImportKind { BACKUP, PROFILE }

    data class ImportResult(
        val kind: ImportKind,
        val profileCount: Int,
        val iconCount: Int,
        /** Id of the newly created profile for [ImportKind.PROFILE] imports. */
        val importedProfileId: Long? = null
    )

    // ---- Export --------------------------------------------------------------------

    suspend fun exportBackup(uri: Uri) = exportBackup {
        context.contentResolver.openOutputStream(uri) ?: throw IOException("Cannot open $uri for writing")
    }

    suspend fun exportProfile(profileId: Long, uri: Uri) = exportProfile(profileId) {
        context.contentResolver.openOutputStream(uri) ?: throw IOException("Cannot open $uri for writing")
    }

    suspend fun exportBackup(open: () -> OutputStream) = withContext(Dispatchers.IO) {
        // Fold the live generation prefs into the active profile's snapshot first (the same
        // capture a profile switch does), so the exported profile rows are self-consistent.
        val prefs = context.dataStore.data.first()
        val activeId = prefs[ActiveProfileIdKey] ?: DEFAULT_PROFILE_ID
        packRepo.profile(activeId)?.let {
            packRepo.updateProfile(it.copy(prefsSnapshot = prefs.snapshotProfilePrefs()))
        }

        val allIcons = packRepo.getAllProfilesApplications()
        val iconsByProfile = allIcons.groupBy { it.profileId }
        val rulesByProfile = watchRepo.getAllRules().groupBy { it.rule.profileId }
        val data = BackupData(
            profiles = packRepo.profiles().map { profile ->
                BackupProfile(
                    profile = profile,
                    icons = iconsByProfile[profile.id].orEmpty(),
                    watchRules = rulesByProfile[profile.id].orEmpty().map { it.toBackupRule() }
                )
            },
            prefs = prefs.asMap().mapNotNull { (key, value) ->
                // The last-watch-check timestamp is about THIS device's worker, not the data.
                if (key.name == LastWatchCheckAtKey.name) return@mapNotNull null
                BackupPref.of(value)?.let { key.name to it }
            }.toMap(),
            packLabels = packLabelsFor(allIcons.mapNotNull { it.sourcePackName.ifEmpty { null } }.toSet())
        )

        ZipOutputStream(open().buffered()).use { zip ->
            zip.putTextEntry(MANIFEST_ENTRY, manifestJson(KIND_BACKUP))
            zip.putTextEntry(DATA_ENTRY, BackupCodec.encode(data))
            context.filesDir.resolve(IconPackBuilder.KEYSTORE_FILE_NAME)
                .takeIf { it.exists() }
                ?.let { zip.putFileEntry(KEYSTORE_ENTRY, it) }
            for (file in UploadedImageStore.list(context)) {
                zip.putFileEntry("$UPLOADS_DIR/${file.name}", file)
            }
        }
    }

    /**
     * Exports one profile for sharing. Icons from packs that are paid — or whose price
     * can't be verified right now (no network): fail-closed, a share must never leak paid
     * artwork — are stripped to references. No uploads or keystore travel with a share.
     */
    suspend fun exportProfile(profileId: Long, open: () -> OutputStream) = withContext(Dispatchers.IO) {
        val prefs = context.dataStore.data.first()
        val activeId = prefs[ActiveProfileIdKey] ?: DEFAULT_PROFILE_ID
        if (profileId == activeId) {
            packRepo.profile(profileId)?.let {
                packRepo.updateProfile(it.copy(prefsSnapshot = prefs.snapshotProfilePrefs()))
            }
        }
        val profile = packRepo.profile(profileId) ?: throw IOException("Profile $profileId does not exist")
        val icons = packRepo.getAll(profileId)
        val rules = watchRepo.getAllRules()
            .filter { it.rule.profileId == profileId }
            .map { it.toBackupRule() }

        val sourcePacks = icons.mapNotNull { it.sourcePackName.ifEmpty { null } }.toSet()
        val stripPacks = verdictManager.ensureVerdicts(sourcePacks)
        val exportIcons = icons.map { icon ->
            if (icon.sourcePackName.isNotEmpty() && icon.sourcePackName in stripPacks && icon.drawable.isNotEmpty()) {
                icon.copy(
                    drawable = "",
                    isXml = false,
                    // Bulk-refresh icons are exactly the appfilter-mapped ones, so this
                    // resolution is faithful; a hand-picked alternate degrades to the
                    // pack's default icon for the app.
                    sourceDrawableName = icon.sourceDrawableName.ifEmpty {
                        appFilterDrawableName(icon.sourcePackName, icon.packageName, icon.activityName) ?: ""
                    }
                )
            } else icon
        }
        val data = BackupData(
            profiles = listOf(BackupProfile(profile, exportIcons, rules)),
            prefs = emptyMap(),
            packLabels = packLabelsFor(sourcePacks)
        )

        ZipOutputStream(open().buffered()).use { zip ->
            zip.putTextEntry(MANIFEST_ENTRY, manifestJson(KIND_PROFILE))
            zip.putTextEntry(DATA_ENTRY, BackupCodec.encode(data))
        }
    }

    // ---- Import --------------------------------------------------------------------

    /** Reads just enough of the file to tell a full backup from a shared profile. */
    suspend fun peekKind(uri: Uri): ImportKind = withContext(Dispatchers.IO) {
        val manifest = openZip(uri).use { zip ->
            var entry = zip.nextEntry
            while (entry != null && entry.name != MANIFEST_ENTRY) entry = zip.nextEntry
            if (entry == null) throw IOException("Not a Renkin file (no manifest)")
            JSONObject(zip.readEntryText())
        }
        kindOf(manifest)
    }

    suspend fun importFile(uri: Uri): ImportResult = importFile {
        context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open $uri for reading")
    }

    /** Dispatches on the file's kind: full backups replace, shared profiles add. */
    suspend fun importFile(open: () -> InputStream): ImportResult = withContext(Dispatchers.IO) {
        val (manifest, data) = readArchive(open)
        when (kindOf(manifest)) {
            ImportKind.BACKUP -> restoreBackup(data, open)
            ImportKind.PROFILE -> importProfile(data)
        }
    }

    suspend fun importBackup(uri: Uri): ImportResult = importBackup {
        context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open $uri for reading")
    }

    suspend fun importBackup(open: () -> InputStream): ImportResult = withContext(Dispatchers.IO) {
        val (manifest, data) = readArchive(open)
        if (kindOf(manifest) != ImportKind.BACKUP) throw IOException("Not a full-backup file")
        restoreBackup(data, open)
    }

    /** Pass 1 of an import: read and fully validate before touching anything on the device. */
    private fun readArchive(open: () -> InputStream): Pair<JSONObject, BackupData> {
        var manifest: JSONObject? = null
        var dataJson: String? = null
        ZipInputStream(open().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                when (entry.name) {
                    MANIFEST_ENTRY -> manifest = JSONObject(zip.readEntryText())
                    DATA_ENTRY -> dataJson = zip.readEntryText()
                }
                entry = zip.nextEntry
            }
        }
        val meta = manifest ?: throw IOException("Not a Renkin file (no manifest)")
        if (meta.optInt("format", Int.MAX_VALUE) > BackupCodec.FORMAT_VERSION) {
            throw IOException("File was made by a newer app version")
        }
        val data = BackupCodec.decode(dataJson ?: throw IOException("File has no data entry"))
        return meta to data
    }

    private fun kindOf(manifest: JSONObject): ImportKind = when (manifest.optString("kind")) {
        KIND_BACKUP -> ImportKind.BACKUP
        KIND_PROFILE -> ImportKind.PROFILE
        else -> throw IOException("Unknown file kind")
    }

    private suspend fun restoreBackup(data: BackupData, open: () -> InputStream): ImportResult {
        if (data.profiles.none { it.profile.id == DEFAULT_PROFILE_ID }) {
            throw IOException("Backup has no default profile")
        }

        packRepo.replaceEverything(
            data.profiles.map { it.profile },
            data.profiles.flatMap { it.icons }
        )
        watchRepo.replaceAllRules(data.profiles.flatMap { bp ->
            bp.watchRules.map { it.toImport(bp.profile.id) }
        })
        restorePrefs(data.prefs)
        storePackLabels(data.packLabels)

        // Pass 2: the bundled files. The gallery is replaced wholesale to match the backup.
        val uploadsDir = UploadedImageStore.directory(context)
        uploadsDir.listFiles()?.forEach { it.delete() }
        ZipInputStream(open().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                when {
                    entry.name == KEYSTORE_ENTRY ->
                        writeEntryTo(zip, context.filesDir.resolve(IconPackBuilder.KEYSTORE_FILE_NAME))
                    entry.name.startsWith("$UPLOADS_DIR/") && entry.name.endsWith(".png") ->
                        // Only the file name is trusted, never the entry's path (zip-slip).
                        writeEntryTo(zip, File(uploadsDir, File(entry.name).name))
                }
                entry = zip.nextEntry
            }
        }

        return ImportResult(ImportKind.BACKUP, data.profiles.size, data.profiles.sumOf { it.icons.size })
    }

    /** A shared profile always lands as a NEW profile — imports never overwrite anything. */
    private suspend fun importProfile(data: BackupData): ImportResult {
        val bp = data.profiles.firstOrNull() ?: throw IOException("File contains no profile")
        val newId = packRepo.createProfile(
            // Fresh identity (the id also names the built pack's package) and fresh flags:
            // the icons are saved-but-not-built, and the missing-pack dialog choice is local.
            bp.profile.copy(id = 0, hasUnbuiltChanges = true, hideMissingPackWarning = false)
        )
        packRepo.replaceAll(newId, bp.icons.map { it.copy(profileId = newId) })
        watchRepo.insertRules(bp.watchRules.map { it.toImport(newId) })
        storePackLabels(data.packLabels)
        return ImportResult(ImportKind.PROFILE, 1, bp.icons.size, importedProfileId = newId)
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

    private fun RuleWithDetails.toBackupRule() = BackupWatchRule(
        watchAllPacks = rule.watchAllPacks,
        completed = rule.completed,
        createdAt = rule.createdAt,
        completedAt = rule.completedAt,
        apps = apps.map { AppComponent(it.packageName, it.activityName) },
        packs = packs.map { it.iconPackPackage }
    )

    private fun BackupWatchRule.toImport(profileId: Long) = WatchRuleImport(
        profileId = profileId,
        watchAllPacks = watchAllPacks,
        completed = completed,
        createdAt = createdAt,
        completedAt = completedAt,
        apps = apps,
        packs = packs
    )

    /** Display names for [packs]: from the installed copy, else the verdict cache. */
    private suspend fun packLabelsFor(packs: Set<String>): Map<String, String> {
        if (packs.isEmpty()) return emptyMap()
        val installed = runCatching { appManager.getIconPacks() }.getOrDefault(emptyList())
            .associate { it.packageName to it.applicationName }
        val cached = packRepo.verdicts(packs.toList())
        return packs.mapNotNull { pack ->
            val label = installed[pack] ?: cached[pack]?.label?.ifEmpty { null }
            label?.let { pack to it }
        }.toMap()
    }

    /** The drawable name [packPackage]'s appfilter maps to the app's component, if any. */
    private fun appFilterDrawableName(packPackage: String, packageName: String, activityName: String): String? {
        val installedApp = InstalledApplication(packageName, activityName, 0)
        return runCatching {
            appManager.getAppFilterRawElements(packPackage, listOf(installedApp))
                .filterIsInstance<RawItem>()
                .firstOrNull { it.component == installedApp.toComponentInfo() }
                ?.drawableLink
        }.getOrNull()
    }

    private fun openZip(uri: Uri): ZipInputStream = ZipInputStream(
        (context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open $uri for reading")).buffered()
    )

    private suspend fun restorePrefs(prefs: Map<String, BackupPref>) {
        context.dataStore.edit { store ->
            store.clear()
            for ((name, pref) in prefs) {
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
        }
    }

    private fun manifestJson(kind: String): String = JSONObject()
        .put("format", BackupCodec.FORMAT_VERSION)
        .put("kind", kind)
        .put("appVersion", BuildConfig.VERSION_NAME)
        .put("exportedAt", System.currentTimeMillis())
        .toString()

    companion object {
        private const val MANIFEST_ENTRY = "manifest.json"
        private const val DATA_ENTRY = "data.json"
        private const val KEYSTORE_ENTRY = "keystore/" + IconPackBuilder.KEYSTORE_FILE_NAME
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

private fun ZipOutputStream.putTextEntry(name: String, text: String) {
    putNextEntry(ZipEntry(name))
    write(text.toByteArray(Charsets.UTF_8))
    closeEntry()
}

private fun ZipOutputStream.putFileEntry(name: String, file: File) {
    putNextEntry(ZipEntry(name))
    file.inputStream().use { it.copyTo(this) }
    closeEntry()
}

/** Reads the CURRENT zip entry (ZipInputStream ends the stream at each entry boundary). */
private fun ZipInputStream.readEntryText(): String = readBytes().toString(Charsets.UTF_8)

private fun writeEntryTo(zip: ZipInputStream, target: File) {
    target.outputStream().use { zip.copyTo(it) }
}
