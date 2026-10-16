package dev.renkinProject.renkin.data.transfer

import android.app.Application
import dev.renkinProject.renkin.data.UnsavedIconJournalStore
import android.content.Context
import android.net.Uri
import androidx.room.Room
import dev.renkinProject.renkin.data.editor.EditorDraftInvalidation
import dev.renkinProject.renkin.data.editor.EditorDraftStore
import dev.renkinProject.renkin.apk.PackKeystore
import dev.renkinProject.renkin.data.DEFAULT_PROFILE_ID
import dev.renkinProject.renkin.data.DbApplication
import dev.renkinProject.renkin.data.PackVerdict
import dev.renkinProject.renkin.data.Profile
import dev.renkinProject.renkin.data.ProfileStyleStore
import dev.renkinProject.renkin.data.StorageOperationGate
import dev.renkinProject.renkin.dataStore
import dev.renkinProject.renkin.data.RenkinPackDatabase
import dev.renkinProject.renkin.data.RenkinPackRepository
import dev.renkinProject.renkin.data.UploadedImageStore
import dev.renkinProject.renkin.data.watch.AppComponent
import dev.renkinProject.renkin.data.watch.BaselineInput
import dev.renkinProject.renkin.data.watch.CandidateInput
import dev.renkinProject.renkin.data.watch.SuggestionImport
import dev.renkinProject.renkin.data.watch.WatchDatabase
import dev.renkinProject.renkin.data.watch.WatchRepository
import dev.renkinProject.renkin.data.watch.WatchRuleImport
import dev.renkinProject.renkin.data.watch.WatchState
import dev.renkinProject.renkin.icon.creator.ColorizerStyle
import dev.renkinProject.renkin.icon.creator.ModifierPresetPayload
import dev.renkinProject.renkin.icon.creator.encodeColorizerStyle
import dev.renkinProject.renkin.icon.creator.encodeModifierPreset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * End-to-end round trip of the `.renkin` ZIP: export from one set of (in-memory) stores,
 * import into a fresh set, and check the data arrived intact. The shared DataStore is the
 * process-wide one — prefs content is exercised by the codec test; here the ZIP plumbing,
 * manifest validation and store replacement are under test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33], shadows = [BackupOsShadow::class])
class BackupManagerTest {
    private val draftInvalidation by lazy { EditorDraftInvalidation(EditorDraftStore(context), UnsavedIconJournalStore(context)) }

    private lateinit var context: Context
    private lateinit var srcPackDb: RenkinPackDatabase
    private lateinit var srcWatchDb: WatchDatabase
    private lateinit var tgtPackDb: RenkinPackDatabase
    private lateinit var tgtWatchDb: WatchDatabase

    @Before
    fun setUp() {
        StorageOperationGate.resetForTests()
        context = RuntimeEnvironment.getApplication()
        ShadowStatFs.registerStats(context.filesDir, 1_000_000, 1_000_000, 1_000_000)
        srcPackDb = inMemoryPackDb()
        srcWatchDb = inMemoryWatchDb()
        tgtPackDb = inMemoryPackDb()
        tgtWatchDb = inMemoryWatchDb()
    }

    @After
    fun tearDown() {
        StorageOperationGate.resetForTests()
        srcPackDb.close()
        srcWatchDb.close()
        tgtPackDb.close()
        tgtWatchDb.close()
    }

    private suspend fun sourceManager(pack: RenkinPackRepository, watch: WatchRepository): BackupManager {
        ProfileStyleStore(pack, context.dataStore).initialize(force = true)
        return BackupManager(context, pack, watch, editorDraftInvalidation = draftInvalidation)
    }

    private fun inMemoryPackDb() = Room.inMemoryDatabaseBuilder(context, RenkinPackDatabase::class.java)
        .allowMainThreadQueries().build()

    private fun inMemoryWatchDb() = Room.inMemoryDatabaseBuilder(context, WatchDatabase::class.java)
        .allowMainThreadQueries().build()

    @Test
    fun iconPackStudioChecks_distinguishProfilesAndExcludeTheActiveProfile() = runBlocking {
        val packRepo = RenkinPackRepository(srcPackDb)
        packRepo.replaceEverything(
            listOf(
                Profile(id = DEFAULT_PROFILE_ID, name = "Renkin"),
                Profile(id = 4L, name = "IPS profile")
            ),
            listOf(
                DbApplication(
                    "com.normal",
                    "com.normal.Main",
                    isAdaptiveIcon = false,
                    isXml = false,
                    drawable = "normal",
                    sourcePackName = "com.example.icons",
                    profileId = DEFAULT_PROFILE_ID
                ),
                DbApplication(
                    "com.ips",
                    "com.ips.Main",
                    isAdaptiveIcon = false,
                    isXml = false,
                    drawable = "ips",
                    sourcePackName = "ginlemon.iconpackstudio.exported",
                    profileId = 4L
                )
            )
        )
        val manager = BackupManager(context, packRepo, WatchRepository(srcWatchDb), editorDraftInvalidation = draftInvalidation)

        assertFalse(manager.profileUsesIconPackStudio(DEFAULT_PROFILE_ID))
        assertTrue(manager.profileUsesIconPackStudio(4L))
        assertTrue(manager.otherProfilesUseIconPackStudio(DEFAULT_PROFILE_ID))
        assertFalse(manager.otherProfilesUseIconPackStudio(4L))
    }

    @Test
    fun exportImport_roundTripsProfilesIconsAndRules() = runBlocking {
        val srcPackRepo = RenkinPackRepository(srcPackDb)
        val srcWatchRepo = WatchRepository(srcWatchDb)
        srcPackRepo.replaceEverything(
            listOf(
                Profile(id = DEFAULT_PROFILE_ID, name = "Renkin", packLabel = "Renkin Pack"),
                Profile(id = 4L, name = "Dark", packLabel = "Renkin Dark", hasUnbuiltChanges = true)
            ),
            listOf(
                DbApplication("com.a", "com.a.Main", isAdaptiveIcon = true, isXml = false, drawable = "aWNvbg==", sourcePackName = "pack.x"),
                DbApplication("com.b", "com.b.Main", isAdaptiveIcon = false, isXml = false, drawable = "aWNvbjI=", profileId = 4L)
            )
        )
        srcWatchRepo.replaceAllRules(
            listOf(
                WatchRuleImport(
                    profileId = 4L, watchAllPacks = false, completed = false,
                    createdAt = 42L, completedAt = null,
                    apps = listOf(AppComponent("com.b", "com.b.Main")), packs = listOf("pack.x"),
                    baselines = listOf(
                        BaselineInput(
                            "com.b", "com.b.Main", "pack.x", 8L,
                            "drawable_b", "baseline-hash", 43L
                        )
                    )
                ),
                WatchRuleImport(
                    profileId = 4L, watchAllPacks = false, completed = true,
                    createdAt = 44L, completedAt = 45L,
                    apps = listOf(AppComponent("com.done", "com.done.Main")),
                    packs = listOf("pack.x"),
                    suggestions = listOf(
                        SuggestionImport(
                            "com.done", "com.done.Main", 45L,
                            listOf(CandidateInput("pack.x", "drawable_done", "candidate-hash"))
                        )
                    )
                )
            )
        )

        val out = ByteArrayOutputStream()
        sourceManager(srcPackRepo, srcWatchRepo).exportBackup { out }
        val bytes = out.toByteArray()

        val tgtPackRepo = RenkinPackRepository(tgtPackDb)
        val tgtWatchRepo = WatchRepository(tgtWatchDb)
        val summary = BackupManager(context, tgtPackRepo, tgtWatchRepo, editorDraftInvalidation = draftInvalidation)
            .importBackup { ByteArrayInputStream(bytes) }

        assertEquals(2, summary.profileCount)
        assertEquals(2, summary.iconCount)
        assertEquals(listOf("Renkin", "Dark"), tgtPackRepo.profiles().map { it.name })
        assertTrue(tgtPackRepo.profiles().single { it.id == 4L }.hasUnbuiltChanges)
        assertEquals(listOf("com.a"), tgtPackRepo.getAll(DEFAULT_PROFILE_ID).map { it.packageName })
        assertEquals("pack.x", tgtPackRepo.getAll(DEFAULT_PROFILE_ID).single().sourcePackName)
        assertEquals(listOf("com.b"), tgtPackRepo.getAll(4L).map { it.packageName })
        val rules = tgtWatchRepo.getAllRules()
        assertEquals(2, rules.size)
        val activeRule = rules.single { !it.rule.completed }
        assertEquals(4L, activeRule.rule.profileId)
        assertEquals(listOf("com.b"), activeRule.apps.map { it.packageName })
        assertEquals(listOf("pack.x"), activeRule.packs.map { it.iconPackPackage })
        assertEquals(
            "baseline-hash",
            tgtWatchRepo.getState(activeRule.rule.id, "com.b", "com.b.Main", "pack.x")?.lastIconHash
        )
        val completedRule = rules.single { it.rule.completed }
        val suggestion = completedRule.suggestions.single()
        assertEquals("com.done", suggestion.packageName)
        assertEquals(
            "candidate-hash",
            tgtWatchRepo.getCandidates(suggestion.id).single().iconHash
        )
    }

    @Test
    fun inspectBackup_reportsContentsWithoutRestoring() = runBlocking {
        val packRepo = RenkinPackRepository(srcPackDb)
        val watchRepo = WatchRepository(srcWatchDb)
        packRepo.replaceEverything(
            listOf(
                Profile(id = DEFAULT_PROFILE_ID, name = "Renkin"),
                Profile(id = 7L, name = "Light")
            ),
            listOf(
                DbApplication("com.a", "A", false, false, "a", profileId = DEFAULT_PROFILE_ID),
                DbApplication("com.b", "B", false, false, "b", profileId = 7L),
                DbApplication("com.c", "C", false, false, "c", profileId = 7L)
            )
        )
        packRepo.saveColorPreset("Blue", encodeColorizerStyle(ColorizerStyle(firstColor = 0xff0000ff.toInt())))
        packRepo.saveModifierPreset("Soft", encodeModifierPreset(ModifierPresetPayload(iconScale = 0.8f)), 1)
        watchRepo.replaceAllRules(
            listOf(
                WatchRuleImport(DEFAULT_PROFILE_ID, true, false, 1L, null, emptyList(), emptyList()),
                WatchRuleImport(
                    7L,
                    false,
                    true,
                    2L,
                    3L,
                    listOf(AppComponent("com.done", "Done")),
                    listOf("pack.done"),
                    suggestions = listOf(
                        SuggestionImport(
                            "com.done",
                            "Done",
                            3L,
                            listOf(CandidateInput("pack.done", "done_icon", "hash"))
                        )
                    )
                )
            )
        )
        val existingUploads = UploadedImageStore.list(context).size
        val upload = UploadedImageStore.directory(context).resolve("preview-test.png")
        upload.writeBytes(byteArrayOf(1, 2, 3))
        try {
            val out = ByteArrayOutputStream()
            sourceManager(packRepo, watchRepo).exportBackup { out }

            val inspection = BackupManager(context, RenkinPackRepository(tgtPackDb), WatchRepository(tgtWatchDb), editorDraftInvalidation = draftInvalidation)
                .inspectFile { ByteArrayInputStream(out.toByteArray()) }

            assertEquals(BackupManager.ImportKind.BACKUP, inspection.kind)
            val preview = checkNotNull(inspection.backup)
            assertEquals(listOf("Renkin", "Light"), preview.profiles.map { it.name })
            assertEquals(listOf(1, 2), preview.profiles.map { it.iconCount })
            assertEquals(3, preview.iconCount)
            assertEquals(1, preview.activeWatchRules)
            assertEquals(1, preview.completedWatchRules)
            assertEquals(2, preview.savedStyles)
            assertEquals(existingUploads + 1, preview.uploadedImages)

            assertTrue(RenkinPackRepository(tgtPackDb).profiles().isEmpty())
            assertTrue(WatchRepository(tgtWatchDb).getAllRules().isEmpty())
        } finally {
            upload.delete()
        }
    }

    @Test
    fun inspectBackup_rejectsGarbage() = runBlocking {
        val result = runCatching {
            BackupManager(context, RenkinPackRepository(tgtPackDb), WatchRepository(tgtWatchDb), editorDraftInvalidation = draftInvalidation)
                .inspectFile { ByteArrayInputStream("not a backup".toByteArray()) }
        }

        assertTrue(result.isFailure)
    }

    @Test
    fun backupRestore_resetsStalePerDeviceVerdicts() = runBlocking {
        // A minimal backup referencing a pack.
        val srcPackRepo = RenkinPackRepository(srcPackDb)
        srcPackRepo.replaceEverything(
            listOf(Profile(id = DEFAULT_PROFILE_ID, name = "Renkin")),
            listOf(DbApplication("com.app", "A", isAdaptiveIcon = false, isXml = false, drawable = "data", sourcePackName = "pack.free", profileId = DEFAULT_PROFILE_ID))
        )
        val out = ByteArrayOutputStream()
        sourceManager(srcPackRepo, WatchRepository(srcWatchDb)).exportBackup { out }

        // Target device wrongly still "owns" pack.free from an earlier install (seenInstalled),
        // though nothing is installed now (Robolectric). A restore must not inherit that.
        val tgtPackRepo = RenkinPackRepository(tgtPackDb)
        tgtPackRepo.upsertVerdicts(listOf(PackVerdict("pack.free", "free", seenInstalled = true)))

        BackupManager(context, tgtPackRepo, WatchRepository(tgtWatchDb), editorDraftInvalidation = draftInvalidation)
            .importBackup { ByteArrayInputStream(out.toByteArray()) }

        // The stale ownership is gone — the pack would lock again on load.
        val after = tgtPackRepo.verdicts(listOf("pack.free"))["pack.free"]
        assertTrue(after == null || !after.seenInstalled)
    }

    @Test
    fun profileShare_embedsAllPixels_importsAsNewProfile() = runBlocking {
        val srcPackRepo = RenkinPackRepository(srcPackDb)
        val srcWatchRepo = WatchRepository(srcWatchDb)
        srcPackRepo.replaceEverything(
            listOf(
                Profile(id = DEFAULT_PROFILE_ID, name = "Renkin"),
                Profile(id = 3L, name = "Shared set", packLabel = "Shared Pack")
            ),
            listOf(
                DbApplication("com.paid", "com.paid.Main", isAdaptiveIcon = false, isXml = false, drawable = "cGFpZA==", sourcePackName = "pack.paid", profileId = 3L),
                DbApplication("com.free", "com.free.Main", isAdaptiveIcon = false, isXml = false, drawable = "ZnJlZQ==", sourcePackName = "pack.free", profileId = 3L),
                DbApplication("com.upload", "com.upload.Main", isAdaptiveIcon = false, isXml = false, drawable = "dXBsb2Fk", profileId = 3L)
            )
        )
        srcWatchRepo.insertRules(
            listOf(
                WatchRuleImport(3L, false, false, 5L, null, listOf(AppComponent("com.paid", "com.paid.Main")), listOf("pack.paid"))
            )
        )
        // The source device knows the pack's display name (recorded while it was installed).
        srcPackRepo.upsertVerdicts(listOf(PackVerdict("pack.paid", label = "Paid Icons", seenInstalled = true)))

        val out = ByteArrayOutputStream()
        sourceManager(srcPackRepo, srcWatchRepo).exportProfile(3L) { out }
        val bytes = out.toByteArray()

        val tgtPackRepo = RenkinPackRepository(tgtPackDb)
        val tgtWatchRepo = WatchRepository(tgtWatchDb)
        tgtPackRepo.replaceEverything(listOf(Profile(id = DEFAULT_PROFILE_ID, name = "Keep me")), emptyList())
        val result = BackupManager(context, tgtPackRepo, tgtWatchRepo, editorDraftInvalidation = draftInvalidation)
            .importFile { ByteArrayInputStream(bytes) }

        // The import is additive: a fresh profile next to the untouched existing ones.
        assertEquals(BackupManager.ImportKind.PROFILE, result.kind)
        val newId = result.importedProfileId!!
        assertTrue(newId > DEFAULT_PROFILE_ID)
        assertEquals(listOf("Keep me", "Shared set"), tgtPackRepo.profiles().map { it.name })
        assertTrue(tgtPackRepo.profiles().single { it.id == newId }.hasUnbuiltChanges)

        val rows = tgtPackRepo.getAll(newId).associateBy { it.packageName }
        // Every icon keeps its pixels — a paid pack vanishing from the store must not kill
        // the shared icons. Locking paid-pack rows is the importing device's job at load.
        assertEquals("cGFpZA==", rows.getValue("com.paid").drawable)
        assertEquals("pack.paid", rows.getValue("com.paid").sourcePackName)
        assertEquals("ZnJlZQ==", rows.getValue("com.free").drawable)
        assertEquals("dXBsb2Fk", rows.getValue("com.upload").drawable)

        // The watch rule came along, re-owned by the new profile.
        val rule = tgtWatchRepo.getAllRules().single()
        assertEquals(newId, rule.rule.profileId)
        assertEquals(listOf("com.paid"), rule.apps.map { it.packageName })

        // The pack's display name travelled for the missing-packs dialog — label only,
        // never the ownership flag (the file is untrusted).
        val verdict = tgtPackRepo.verdicts(listOf("pack.paid")).getValue("pack.paid")
        assertEquals("Paid Icons", verdict.label)
        assertEquals(false, verdict.seenInstalled)
    }

    @Test
    fun backup_roundTripsKeystoreWithItsPassword() = runBlocking {
        val filesDir = context.filesDir
        PackKeystore.keystoreFile(filesDir).writeBytes(byteArrayOf(1, 2, 3))
        PackKeystore.passwordFile(filesDir).writeText("hunter2")
        try {
            val srcPackRepo = RenkinPackRepository(srcPackDb)
            srcPackRepo.replaceEverything(listOf(Profile(id = DEFAULT_PROFILE_ID, name = "Renkin")), emptyList())
            val out = ByteArrayOutputStream()
            sourceManager(srcPackRepo, WatchRepository(srcWatchDb)).exportBackup { out }

            PackKeystore.keystoreFile(filesDir).delete()
            PackKeystore.passwordFile(filesDir).delete()

            BackupManager(context, RenkinPackRepository(tgtPackDb), WatchRepository(tgtWatchDb), editorDraftInvalidation = draftInvalidation)
                .importBackup { ByteArrayInputStream(out.toByteArray()) }

            assertArrayEquals(byteArrayOf(1, 2, 3), PackKeystore.keystoreFile(filesDir).readBytes())
            assertEquals("hunter2", PackKeystore.passwordFile(filesDir).readText())
        } finally {
            PackKeystore.keystoreFile(filesDir).delete()
            PackKeystore.passwordFile(filesDir).delete()
        }
    }

    @Test
    fun restoringKeystore_replacesAnOlderOneWholeAndLeavesNoTempFile() = runBlocking {
        val filesDir = context.filesDir
        try {
            PackKeystore.keystoreFile(filesDir).writeBytes(byteArrayOf(1, 2, 3))
            PackKeystore.passwordFile(filesDir).writeText("hunter2")
            val srcPackRepo = RenkinPackRepository(srcPackDb)
            srcPackRepo.replaceEverything(listOf(Profile(id = DEFAULT_PROFILE_ID, name = "Renkin")), emptyList())
            val out = ByteArrayOutputStream()
            sourceManager(srcPackRepo, WatchRepository(srcWatchDb)).exportBackup { out }

            // A longer keystore already on the device: restore goes through a temp file and a
            // rename, so the result must be the backup's bytes exactly — never a mix of both.
            PackKeystore.keystoreFile(filesDir).writeBytes(byteArrayOf(7, 7, 7, 7, 7, 7, 7, 7))

            BackupManager(context, RenkinPackRepository(tgtPackDb), WatchRepository(tgtWatchDb), editorDraftInvalidation = draftInvalidation)
                .importBackup { ByteArrayInputStream(out.toByteArray()) }

            assertArrayEquals(byteArrayOf(1, 2, 3), PackKeystore.keystoreFile(filesDir).readBytes())
            // The scratch file must not survive a successful restore.
            assertTrue(filesDir.listFiles()?.none { it.name.endsWith(".tmp") } ?: true)
        } finally {
            PackKeystore.keystoreFile(filesDir).delete()
            PackKeystore.passwordFile(filesDir).delete()
        }
    }

    @Test
    fun restoringPasswordlessBackup_dropsTheStaleRandomPassword() = runBlocking {
        val filesDir = context.filesDir
        try {
            // The backup carries a legacy keystore (no password file existed when it was made).
            PackKeystore.keystoreFile(filesDir).writeBytes(byteArrayOf(9))
            val srcPackRepo = RenkinPackRepository(srcPackDb)
            srcPackRepo.replaceEverything(listOf(Profile(id = DEFAULT_PROFILE_ID, name = "Renkin")), emptyList())
            val out = ByteArrayOutputStream()
            sourceManager(srcPackRepo, WatchRepository(srcWatchDb)).exportBackup { out }

            // The device meanwhile has a random-password keystore — restoring the legacy
            // backup must not leave that password behind (it would lock the keystore out).
            PackKeystore.passwordFile(filesDir).writeText("stale-random")

            BackupManager(context, RenkinPackRepository(tgtPackDb), WatchRepository(tgtWatchDb), editorDraftInvalidation = draftInvalidation)
                .importBackup { ByteArrayInputStream(out.toByteArray()) }

            assertArrayEquals(byteArrayOf(9), PackKeystore.keystoreFile(filesDir).readBytes())
            assertFalse(PackKeystore.passwordFile(filesDir).exists())
        } finally {
            PackKeystore.keystoreFile(filesDir).delete()
            PackKeystore.passwordFile(filesDir).delete()
        }
    }

    @Test
    fun profileImport_dedupesNamesWithNumberSuffix() = runBlocking {
        val srcPackRepo = RenkinPackRepository(srcPackDb)
        val srcWatchRepo = WatchRepository(srcWatchDb)
        srcPackRepo.replaceEverything(
            listOf(
                Profile(id = DEFAULT_PROFILE_ID, name = "Renkin"),
                Profile(id = 2L, name = "Gaming")
            ),
            emptyList()
        )
        val out = ByteArrayOutputStream()
        sourceManager(srcPackRepo, srcWatchRepo).exportProfile(2L) { out }
        val bytes = out.toByteArray()

        val tgtPackRepo = RenkinPackRepository(tgtPackDb)
        val tgtWatchRepo = WatchRepository(tgtWatchDb)
        tgtPackRepo.replaceEverything(listOf(Profile(id = DEFAULT_PROFILE_ID, name = "Renkin")), emptyList())
        val manager = BackupManager(context, tgtPackRepo, tgtWatchRepo, editorDraftInvalidation = draftInvalidation)
        manager.importFile { ByteArrayInputStream(bytes) }
        manager.importFile { ByteArrayInputStream(bytes) }
        manager.importFile { ByteArrayInputStream(bytes) }

        assertEquals(
            listOf("Renkin", "Gaming", "Gaming (2)", "Gaming (3)"),
            tgtPackRepo.profiles().map { it.name }
        )
    }

    @Test
    fun import_rejectsGarbageWithoutTouchingStores() = runBlocking {
        val tgtPackRepo = RenkinPackRepository(tgtPackDb)
        val tgtWatchRepo = WatchRepository(tgtWatchDb)
        tgtPackRepo.replaceEverything(
            listOf(Profile(id = DEFAULT_PROFILE_ID, name = "Keep me")),
            listOf(DbApplication("com.keep", "com.keep.Main", isAdaptiveIcon = false, isXml = false, drawable = "ZGF0YQ=="))
        )

        val result = runCatching {
            BackupManager(context, tgtPackRepo, tgtWatchRepo, editorDraftInvalidation = draftInvalidation)
                .importFile { ByteArrayInputStream("definitely not a zip".toByteArray()) }
        }

        assertTrue(result.isFailure)
        assertEquals(listOf("Keep me"), tgtPackRepo.profiles().map { it.name })
        assertEquals(listOf("com.keep"), tgtPackRepo.getAll(DEFAULT_PROFILE_ID).map { it.packageName })
    }

    private fun minimalData(name: String = "Incoming") = BackupData(
        listOf(BackupProfile(Profile(id = 1, name = name), emptyList(), emptyList())), emptyMap()
    )

    private fun archive(data: BackupData, format: Int = 3, extras: Map<String, String> = emptyMap()): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            val entries = linkedMapOf(
                "manifest.json" to "{\"format\":$format,\"kind\":\"backup\"}",
                "data.json" to BackupCodec.encode(data, BackupCodec.LEGACY_FORMAT_VERSION)
            ).apply { putAll(extras) }
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    @Test fun invalidArchives_neverReachCommitOrChangeLiveFilesAndStores() = runBlocking {
        val pack = RenkinPackRepository(tgtPackDb)
        pack.replaceEverything(listOf(Profile(id = 1, name = "Keep")), emptyList())
        val manager = BackupManager(context, pack, WatchRepository(tgtWatchDb), editorDraftInvalidation = draftInvalidation)
        val data = minimalData()
        val profile = data.profiles.single()
        val files = context.filesDir
        val keystore = PackKeystore.keystoreFile(files).apply { writeText("original") }
        try {
            val invalid = listOf(
                archive(data, format = 5), archive(data, format = 0),
                archive(data.copy(profiles = listOf(profile, profile))),
                archive(data.copy(profiles = listOf(profile.copy(profile = profile.profile.copy(id = 2))))),
                archive(data, extras = mapOf("uploads/nested/image.png" to "invalid")),
                archive(data, extras = mapOf("keystore/${PackKeystore.PASSWORD_FILE_NAME}" to "orphan"))
            )
            for (bytes in invalid) {
                val source = context.cacheDir.resolve("invalid.renkin").apply { writeBytes(bytes) }
                var committed = false
                val error = runCatching {
                    manager.importFile(Uri.fromFile(source), commit = { operation -> committed = true; operation() })
                }.exceptionOrNull()
                assertTrue(error is IOException)
                assertFalse(committed)
                assertEquals("Keep", pack.profiles().single().name)
                assertEquals("original", keystore.readText())
            }
        } finally { keystore.delete() }
    }

    @Test fun preparedImport_usesVerifiedSnapshotAfterSourceDisappears() = runBlocking {
        val pack = RenkinPackRepository(tgtPackDb)
        pack.replaceEverything(listOf(Profile(id = 1, name = "Before")), emptyList())
        val manager = BackupManager(context, pack, WatchRepository(tgtWatchDb), editorDraftInvalidation = draftInvalidation)
        val source = context.cacheDir.resolve("prepared.renkin").apply { writeBytes(archive(minimalData())) }
        val prepared = manager.prepareImport(Uri.fromFile(source))
        assertEquals("Before", pack.profiles().single().name)
        assertTrue(source.delete())
        manager.importPrepared(prepared)
        assertEquals("Incoming", pack.profiles().single().name)
        assertTrue(prepared.closed)
        assertTrue(prepared.staged.entries.values.none { it.exists() })
    }

    @Test fun discardedImport_releasesFilesAndCannotBeCommitted() = runBlocking {
        val manager = BackupManager(context, RenkinPackRepository(tgtPackDb), WatchRepository(tgtWatchDb), editorDraftInvalidation = draftInvalidation)
        val source = context.cacheDir.resolve("discard.renkin").apply { writeBytes(archive(minimalData())) }
        val prepared = manager.prepareImport(Uri.fromFile(source))
        manager.discardImport(prepared)
        manager.discardImport(prepared)
        assertTrue(prepared.staged.entries.values.none { it.exists() })
        assertTrue(runCatching { manager.importPrepared(prepared) }.exceptionOrNull() is IllegalStateException)
        assertTrue(RenkinPackRepository(tgtPackDb).profiles().isEmpty())
    }

    @Test fun changedFingerprint_rejectsLegacyReopenedInputBeforeCommit() = runBlocking {
        val pack = RenkinPackRepository(tgtPackDb)
        pack.replaceEverything(listOf(Profile(id = 1, name = "Keep")), emptyList())
        val manager = BackupManager(context, pack, WatchRepository(tgtWatchDb), editorDraftInvalidation = draftInvalidation)
        val source = context.cacheDir.resolve("changed.renkin").apply { writeBytes(archive(minimalData())) }
        val inspection = manager.inspectFile { source.inputStream() }
        source.writeBytes(archive(minimalData("Different")))
        val error = runCatching { manager.importFile(Uri.fromFile(source), expectedFingerprint = inspection.fingerprint) }.exceptionOrNull()
        assertTrue(error is IOException)
        assertEquals("Keep", pack.profiles().single().name)
    }

    @Test fun export_validatesBeforeOpeningOutput() = runBlocking {
        val pack = RenkinPackRepository(srcPackDb)
        pack.replaceEverything(listOf(Profile(id = 1, name = "Saved")), emptyList())
        pack.saveColorPreset("Broken", "invalid")
        var opened = false
        val error = runCatching {
            sourceManager(pack, WatchRepository(srcWatchDb)).exportBackup {
                opened = true
                ByteArrayOutputStream()
            }
        }.exceptionOrNull()
        assertTrue(error is IOException)
        assertFalse(opened)
    }

    @Test fun exportImport_normalizesRealStaleWatchRowsWithoutChangingSource() = runBlocking {
        val pack = RenkinPackRepository(srcPackDb)
        val watch = WatchRepository(srcWatchDb)
        pack.replaceEverything(listOf(Profile(id = 1, name = "Saved")), emptyList())
        val app = AppComponent("app.one", "Main")
        val active = watch.saveRule(null, listOf(app), false, listOf("pack.one"), 1)
        watch.upsertState(WatchState(active, "removed.app", "Main", "pack.one", 1, null, null, 1))
        val completed = watch.saveRule(null, listOf(app), false, listOf("pack.one"), 1)
        watch.completeWithSuggestion(completed, app, listOf(CandidateInput("pack.one", "icon", "")))
        // Insert after the normal prune path to exercise an older database's orphan state.
        watch.upsertState(WatchState(active, "removed.app", "Main", "pack.one", 1, null, null, 1))
        val output = ByteArrayOutputStream()
        sourceManager(pack, watch).exportBackup { output }
        assertEquals(2, watch.getAllRules().size)
        assertEquals(1, watch.getStatesForRule(active).size)
        val target = WatchRepository(tgtWatchDb)
        BackupManager(context, RenkinPackRepository(tgtPackDb), target, editorDraftInvalidation = draftInvalidation)
            .importBackup { ByteArrayInputStream(output.toByteArray()) }
        assertEquals(1, target.getAllRules().size)
        assertTrue(target.getStatesForRule(target.getAllRules().single().rule.id).isEmpty())
    }

    @Test fun legacyFormatsOneThroughThreeRemainImportable() = runBlocking {
        val pack = RenkinPackRepository(tgtPackDb)
        val manager = BackupManager(context, pack, WatchRepository(tgtWatchDb), editorDraftInvalidation = draftInvalidation)
        for (format in 1..3) {
            manager.importBackup { ByteArrayInputStream(archive(minimalData("Legacy $format"), format)) }
            assertEquals("Legacy $format", pack.profiles().single().name)
        }
    }
}
