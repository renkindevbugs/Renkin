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
import dev.renkinProject.renkin.data.LastWatchCheckAtKey
import dev.renkinProject.renkin.data.RenkinPackRepository
import dev.renkinProject.renkin.data.UploadedImageStore
import dev.renkinProject.renkin.data.snapshotProfilePrefs
import dev.renkinProject.renkin.data.watch.AppComponent
import dev.renkinProject.renkin.data.watch.WatchRepository
import dev.renkinProject.renkin.data.watch.WatchRuleImport
import dev.renkinProject.renkin.dataStore
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
 * Full-device backup: writes and restores the `.renkin` file (a ZIP of `manifest.json`,
 * `data.json`, the upload gallery and the pack-signing keystore). Import is all-or-nothing:
 * the file is fully parsed BEFORE any store is touched, so a corrupt file never leaves the
 * device half-wiped. After a successful import the caller must reload the in-memory app
 * state (ApplicationProvider.reloadActiveProfile).
 */
class BackupManager(
    private val context: Context,
    private val packRepo: RenkinPackRepository,
    private val watchRepo: WatchRepository
) {
    /** Production entry point: uses the shared singleton databases. Tests inject in-memory ones. */
    constructor(context: Context) : this(context, RenkinPackRepository(context), WatchRepository(context))

    data class ImportSummary(val profileCount: Int, val iconCount: Int)

    suspend fun exportBackup(uri: Uri) = exportBackup {
        context.contentResolver.openOutputStream(uri) ?: throw IOException("Cannot open $uri for writing")
    }

    suspend fun importBackup(uri: Uri): ImportSummary = importBackup {
        context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open $uri for reading")
    }

    suspend fun exportBackup(open: () -> OutputStream) = withContext(Dispatchers.IO) {
        // Fold the live generation prefs into the active profile's snapshot first (the same
        // capture a profile switch does), so the exported profile rows are self-consistent.
        val prefs = context.dataStore.data.first()
        val activeId = prefs[ActiveProfileIdKey] ?: DEFAULT_PROFILE_ID
        packRepo.profile(activeId)?.let {
            packRepo.updateProfile(it.copy(prefsSnapshot = prefs.snapshotProfilePrefs()))
        }

        val iconsByProfile = packRepo.getAllProfilesApplications().groupBy { it.profileId }
        val rulesByProfile = watchRepo.getAllRules().groupBy { it.rule.profileId }
        val data = BackupData(
            profiles = packRepo.profiles().map { profile ->
                BackupProfile(
                    profile = profile,
                    icons = iconsByProfile[profile.id].orEmpty(),
                    watchRules = rulesByProfile[profile.id].orEmpty().map { details ->
                        BackupWatchRule(
                            watchAllPacks = details.rule.watchAllPacks,
                            completed = details.rule.completed,
                            createdAt = details.rule.createdAt,
                            completedAt = details.rule.completedAt,
                            apps = details.apps.map { AppComponent(it.packageName, it.activityName) },
                            packs = details.packs.map { it.iconPackPackage }
                        )
                    }
                )
            },
            prefs = prefs.asMap().mapNotNull { (key, value) ->
                // The last-watch-check timestamp is about THIS device's worker, not the data.
                if (key.name == LastWatchCheckAtKey.name) return@mapNotNull null
                BackupPref.of(value)?.let { key.name to it }
            }.toMap()
        )

        ZipOutputStream(open().buffered()).use { zip ->
            zip.putTextEntry(MANIFEST_ENTRY, manifestJson())
            zip.putTextEntry(DATA_ENTRY, BackupCodec.encode(data))
            context.filesDir.resolve(IconPackBuilder.KEYSTORE_FILE_NAME)
                .takeIf { it.exists() }
                ?.let { zip.putFileEntry(KEYSTORE_ENTRY, it) }
            for (file in UploadedImageStore.list(context)) {
                zip.putFileEntry("$UPLOADS_DIR/${file.name}", file)
            }
        }
    }

    suspend fun importBackup(open: () -> InputStream): ImportSummary = withContext(Dispatchers.IO) {
        // Pass 1: read + fully validate the metadata before touching anything on the device.
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
        val meta = manifest ?: throw IOException("Not a Renkin backup (no manifest)")
        if (meta.optString("kind") != KIND_BACKUP) throw IOException("Not a full-backup file")
        if (meta.optInt("format", Int.MAX_VALUE) > BackupCodec.FORMAT_VERSION) {
            throw IOException("Backup was made by a newer app version")
        }
        val data = BackupCodec.decode(dataJson ?: throw IOException("Backup has no data entry"))
        if (data.profiles.none { it.profile.id == DEFAULT_PROFILE_ID }) {
            throw IOException("Backup has no default profile")
        }

        // Everything parsed — replace the stores.
        packRepo.replaceEverything(
            data.profiles.map { it.profile },
            data.profiles.flatMap { it.icons }
        )
        watchRepo.replaceAllRules(data.profiles.flatMap { bp ->
            bp.watchRules.map { rule ->
                WatchRuleImport(
                    profileId = bp.profile.id,
                    watchAllPacks = rule.watchAllPacks,
                    completed = rule.completed,
                    createdAt = rule.createdAt,
                    completedAt = rule.completedAt,
                    apps = rule.apps,
                    packs = rule.packs
                )
            }
        })
        restorePrefs(data.prefs)

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

        ImportSummary(data.profiles.size, data.profiles.sumOf { it.icons.size })
    }

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

    private fun manifestJson(): String = JSONObject()
        .put("format", BackupCodec.FORMAT_VERSION)
        .put("kind", KIND_BACKUP)
        .put("appVersion", BuildConfig.VERSION_NAME)
        .put("exportedAt", System.currentTimeMillis())
        .toString()

    companion object {
        private const val MANIFEST_ENTRY = "manifest.json"
        private const val DATA_ENTRY = "data.json"
        private const val KEYSTORE_ENTRY = "keystore/" + IconPackBuilder.KEYSTORE_FILE_NAME
        private const val UPLOADS_DIR = "uploads"
        private const val KIND_BACKUP = "backup"

        /** Suggested file name for the SAF save dialog. */
        fun defaultFileName(): String {
            val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
            return "renkin-backup-$date.renkin"
        }
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
