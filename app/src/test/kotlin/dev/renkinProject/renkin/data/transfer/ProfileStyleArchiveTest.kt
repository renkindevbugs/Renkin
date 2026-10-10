package dev.renkinProject.renkin.data.transfer

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.preferencesOf
import androidx.room.Room
import dev.renkinProject.renkin.data.editor.EditorDraftInvalidation
import dev.renkinProject.renkin.data.editor.EditorDraftStore
import dev.renkinProject.renkin.data.*
import dev.renkinProject.renkin.data.watch.WatchDatabase
import dev.renkinProject.renkin.data.watch.WatchRepository
import dev.renkinProject.renkin.dataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowStatFs
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33], shadows = [BackupOsShadow::class])
class ProfileStyleArchiveTest {
    private val draftInvalidation by lazy { EditorDraftInvalidation(EditorDraftStore(context)) }
    private lateinit var context: Context
    private lateinit var packDb: RenkinPackDatabase
    private lateinit var watchDb: WatchDatabase
    private lateinit var pack: RenkinPackRepository
    private lateinit var owner: ProfileStyleStore
    private lateinit var manager: BackupManager

    @Before fun setup() = runBlocking {
        StorageOperationGate.resetForTests()
        context = RuntimeEnvironment.getApplication()
        ShadowStatFs.registerStats(context.filesDir, 1_000_000, 1_000_000, 1_000_000)
        packDb = Room.inMemoryDatabaseBuilder(context, RenkinPackDatabase::class.java).allowMainThreadQueries().build()
        watchDb = Room.inMemoryDatabaseBuilder(context, WatchDatabase::class.java).allowMainThreadQueries().build()
        pack = RenkinPackRepository(packDb)
        manager = BackupManager(context, pack, WatchRepository(watchDb), editorDraftInvalidation = draftInvalidation)
        owner = ProfileStyleStore(pack, context.dataStore)
        context.dataStore.edit { it.clear() }
        ProfileStyleAccess.install { owner }
        StorageOperationGate.installRecovery {
            manager.recoverPendingRestore()
            owner.initialize()
        }
        StorageOperationGate.run { }
    }

    @After fun cleanup() {
        StorageOperationGate.resetForTests()
        packDb.close()
        watchDb.close()
    }

    private suspend fun seed() = StorageOperationGate.run {
        val profiles = listOf(1L to 86, 7L to 121).map { (id, scale) ->
            val values = preferencesOf(GlobalIconScaleKey to scale, PrimarySourceKey to -99,
                GlobalColorizeKey to false, TextFontKey to "/missing/$id.ttf")
            Profile(id = id, name = "Style $id", prefsSnapshot = values.snapshotProfilePrefs(),
                profileStyle = "\n" + JSONObject(ProfileStyleCodec.encode(ProfileStylePreferences.capture(values))).toString(2))
        }
        pack.replaceEverything(profiles, emptyList())
        owner.initialize(force = true)
        profiles
    }

    private suspend fun export(): ByteArray = ByteArrayOutputStream().also { manager.exportBackup { it } }.toByteArray()

    private suspend fun assertApplicationOnly() {
        assertTrue(context.dataStore.data.first().asMap().keys.none { it in ProfilePrefKeys })
    }

    private fun entries(bytes: ByteArray): Map<String, String> = buildMap {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                put(entry.name, zip.readBytes().toString(Charsets.UTF_8))
            }
        }
    }

    private fun archive(entries: Map<String, String>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            for ((name, value) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(value.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }.toByteArray()

    @Test fun fullArchiveCarriesExactStylesAndRestoresThemAcrossRestart() = runBlocking {
        val expected = seed()
        val bytes = export()
        val contents = entries(bytes)
        assertEquals(4, JSONObject(contents.getValue("manifest.json")).getInt("format"))
        val data = BackupCodec.decode(contents.getValue("data.json"), 4)
        assertApplicationOnly()
        assertEquals(86, data.prefs.getValue(GlobalIconScaleKey.name).value)
        assertEquals(expected.map { it.profileStyle }, data.profiles.map { it.profile.profileStyle })
        context.dataStore.setIntValue(GlobalIconScaleKey, 42)
        manager.importBackup { ByteArrayInputStream(bytes) }
        assertEquals(expected, pack.profiles())
        assertApplicationOnly()
        assertEquals(86, context.dataStore.getPreferencesAfterPendingWrites()[GlobalIconScaleKey])
        ProfileStyleStore(pack, context.dataStore).initialize()
        assertEquals(expected, pack.profiles())
    }

    @Test fun sharedArchivePreservesExactDocumentUnderFreshIdAndLeavesActiveSettingsAlone() = runBlocking {
        val original = seed().single { it.id == 7L }
        val output = ByteArrayOutputStream()
        manager.exportProfile(7) { output }
        val contents = entries(output.toByteArray())
        assertEquals(4, JSONObject(contents.getValue("manifest.json")).getInt("format"))
        val before = context.dataStore.getPreferencesAfterPendingWrites()
        val imported = manager.importFile { ByteArrayInputStream(output.toByteArray()) }
        val row = pack.profile(imported.importedProfileId!!)!!
        assertNotEquals(original.id, row.id)
        assertEquals(original.profileStyle, row.profileStyle)
        assertEquals(original.prefsSnapshot, row.prefsSnapshot)
        assertEquals(before, context.dataStore.getPreferencesAfterPendingWrites())
        assertApplicationOnly()
    }

    @Test fun legacyArchivesBootstrapEveryProfileBeforeResumingReaders() = runBlocking {
        for (format in 1..3) {
            val profiles = seed()
            val values = context.dataStore.getPreferencesAfterPendingWrites()
            val data = BackupData(profiles.map { BackupProfile(it, emptyList(), emptyList()) },
                values.asMap().map { (key, value) -> key.name to checkNotNull(BackupPref.of(value)) }.toMap())
            val bytes = archive(mapOf("manifest.json" to "{\"format\":$format,\"kind\":\"backup\"}",
                "data.json" to BackupCodec.encode(data, format)))
            context.dataStore.setIntValue(GlobalIconScaleKey, 42)
            manager.importBackup { ByteArrayInputStream(bytes) }
            assertEquals(86, context.dataStore.getPreferencesAfterPendingWrites()[GlobalIconScaleKey])
            assertApplicationOnly()
            for (profile in pack.profiles()) {
                assertEquals(ProfileStylePreferences.decodeLegacySnapshot(profile.prefsSnapshot),
                    ProfileStyleCodec.decode(profile.profileStyle))
            }
        }
    }

    @Test fun invalidDocumentsMismatchesAndDowngradedManifestsFailBeforeStorageChanges() = runBlocking {
        seed()
        val contents = entries(export())
        val before = pack.profiles()
        val preferences = context.dataStore.getPreferencesAfterPendingWrites()
        val epoch = StorageOperationGate.epoch
        val badData = listOf<(JSONObject) -> Unit>(
            { it.getJSONArray("profiles").getJSONObject(0).remove("profileStyle") },
            { it.getJSONArray("profiles").getJSONObject(1).put("profileStyle", "broken") },
            { it.getJSONArray("profiles").getJSONObject(1).put("prefsSnapshot", "{}") },
            { it.getJSONObject("prefs").getJSONObject(GlobalIconScaleKey.name).put("v", 99) }
        ).map { change -> contents + ("data.json" to JSONObject(contents.getValue("data.json")).also(change).toString()) }
        val downgrade = contents + ("manifest.json" to JSONObject(contents.getValue("manifest.json")).put("format", 3).toString())
        for ((index, invalid) in (badData + listOf(downgrade)).withIndex()) {
            val file = context.cacheDir.resolve("invalid-style-$index.renkin").apply { writeBytes(archive(invalid)) }
            assertTrue(runCatching { manager.prepareImport(Uri.fromFile(file)) }.isFailure)
            assertEquals(before, pack.profiles())
            assertEquals(preferences, context.dataStore.getPreferencesAfterPendingWrites())
            assertEquals(epoch, StorageOperationGate.epoch)
        }
    }

    @Test fun unconvertedExportLogsProfileIdentityBeforeOpeningDestination() = runBlocking {
        seed()
        packDb.profileDao().update(pack.profile(7)!!.copy(profileStyle = ""))
        var opened = false
        assertTrue(runCatching { manager.exportBackup { opened = true; ByteArrayOutputStream() } }.isFailure)
        assertFalse(opened)
        assertTrue(ShadowLog.getLogsForTag("ProfileStyleBackup").any { it.msg.contains("profile 7") })
        assertEquals("", pack.profile(7)!!.profileStyle)
    }

    @Test fun backgroundExportUsesFormatFourWithoutActivityAndLocalRecoveryStaysLegacy() = runBlocking {
        val expected = seed()
        val bytes = withContext(Dispatchers.Default) { export() }
        val contents = entries(bytes)
        assertEquals(4, JSONObject(contents.getValue("manifest.json")).getInt("format"))
        assertEquals(expected.map { it.profileStyle }, BackupCodec.decode(contents.getValue("data.json"), 4)
            .profiles.map { it.profile.profileStyle })
        val snapshot = StorageOperationGate.run { BackupRecoveryStore(context).capture(pack, WatchRepository(watchDb)) }
        val prefs = snapshot.directory.resolve("prefs.json").readText()
        assertTrue(BackupCodec.decode(prefs, RECOVERY_PREFS_FORMAT).profiles.isEmpty())
        assertEquals(context.dataStore.getPreferencesAfterPendingWrites()[GlobalIconScaleKey],
            BackupCodec.decode(prefs, RECOVERY_PREFS_FORMAT).prefs.getValue(GlobalIconScaleKey.name).value)
        assertFalse(prefs.contains("profileStyle"))
        assertApplicationOnly()
    }
}
