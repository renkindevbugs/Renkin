package dev.renkinProject.renkin.data.transfer

import android.app.Application
import android.content.Context
import androidx.room.Room
import dev.renkinProject.renkin.apk.PackKeystore
import dev.renkinProject.renkin.data.DEFAULT_PROFILE_ID
import dev.renkinProject.renkin.data.DbApplication
import dev.renkinProject.renkin.data.PackVerdict
import dev.renkinProject.renkin.data.Profile
import dev.renkinProject.renkin.data.RenkinPackDatabase
import dev.renkinProject.renkin.data.RenkinPackRepository
import dev.renkinProject.renkin.data.watch.AppComponent
import dev.renkinProject.renkin.data.watch.WatchDatabase
import dev.renkinProject.renkin.data.watch.WatchRepository
import dev.renkinProject.renkin.data.watch.WatchRuleImport
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * End-to-end round trip of the `.renkin` ZIP: export from one set of (in-memory) stores,
 * import into a fresh set, and check the data arrived intact. The shared DataStore is the
 * process-wide one — prefs content is exercised by the codec test; here the ZIP plumbing,
 * manifest validation and store replacement are under test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class BackupManagerTest {

    private lateinit var context: Context
    private lateinit var srcPackDb: RenkinPackDatabase
    private lateinit var srcWatchDb: WatchDatabase
    private lateinit var tgtPackDb: RenkinPackDatabase
    private lateinit var tgtWatchDb: WatchDatabase

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        srcPackDb = inMemoryPackDb()
        srcWatchDb = inMemoryWatchDb()
        tgtPackDb = inMemoryPackDb()
        tgtWatchDb = inMemoryWatchDb()
    }

    @After
    fun tearDown() {
        srcPackDb.close()
        srcWatchDb.close()
        tgtPackDb.close()
        tgtWatchDb.close()
    }

    private fun inMemoryPackDb() = Room.inMemoryDatabaseBuilder(context, RenkinPackDatabase::class.java)
        .allowMainThreadQueries().build()

    private fun inMemoryWatchDb() = Room.inMemoryDatabaseBuilder(context, WatchDatabase::class.java)
        .allowMainThreadQueries().build()

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
                    apps = listOf(AppComponent("com.b", "com.b.Main")), packs = listOf("pack.x")
                )
            )
        )

        val out = ByteArrayOutputStream()
        BackupManager(context, srcPackRepo, srcWatchRepo).exportBackup { out }
        val bytes = out.toByteArray()

        val tgtPackRepo = RenkinPackRepository(tgtPackDb)
        val tgtWatchRepo = WatchRepository(tgtWatchDb)
        val summary = BackupManager(context, tgtPackRepo, tgtWatchRepo)
            .importBackup { ByteArrayInputStream(bytes) }

        assertEquals(2, summary.profileCount)
        assertEquals(2, summary.iconCount)
        assertEquals(listOf("Renkin", "Dark"), tgtPackRepo.profiles().map { it.name })
        assertTrue(tgtPackRepo.profiles().single { it.id == 4L }.hasUnbuiltChanges)
        assertEquals(listOf("com.a"), tgtPackRepo.getAll(DEFAULT_PROFILE_ID).map { it.packageName })
        assertEquals("pack.x", tgtPackRepo.getAll(DEFAULT_PROFILE_ID).single().sourcePackName)
        assertEquals(listOf("com.b"), tgtPackRepo.getAll(4L).map { it.packageName })
        val rule = tgtWatchRepo.getAllRules().single()
        assertEquals(4L, rule.rule.profileId)
        assertEquals(listOf("com.b"), rule.apps.map { it.packageName })
        assertEquals(listOf("pack.x"), rule.packs.map { it.iconPackPackage })
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
        BackupManager(context, srcPackRepo, WatchRepository(srcWatchDb)).exportBackup { out }

        // Target device wrongly still "owns" pack.free from an earlier install (seenInstalled),
        // though nothing is installed now (Robolectric). A restore must not inherit that.
        val tgtPackRepo = RenkinPackRepository(tgtPackDb)
        tgtPackRepo.upsertVerdicts(listOf(PackVerdict("pack.free", "free", seenInstalled = true)))

        BackupManager(context, tgtPackRepo, WatchRepository(tgtWatchDb))
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
        BackupManager(context, srcPackRepo, srcWatchRepo).exportProfile(3L) { out }
        val bytes = out.toByteArray()

        val tgtPackRepo = RenkinPackRepository(tgtPackDb)
        val tgtWatchRepo = WatchRepository(tgtWatchDb)
        tgtPackRepo.replaceEverything(listOf(Profile(id = DEFAULT_PROFILE_ID, name = "Keep me")), emptyList())
        val result = BackupManager(context, tgtPackRepo, tgtWatchRepo)
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
            BackupManager(context, srcPackRepo, WatchRepository(srcWatchDb)).exportBackup { out }

            PackKeystore.keystoreFile(filesDir).delete()
            PackKeystore.passwordFile(filesDir).delete()

            BackupManager(context, RenkinPackRepository(tgtPackDb), WatchRepository(tgtWatchDb))
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
            BackupManager(context, srcPackRepo, WatchRepository(srcWatchDb)).exportBackup { out }

            // A longer keystore already on the device: restore goes through a temp file and a
            // rename, so the result must be the backup's bytes exactly — never a mix of both.
            PackKeystore.keystoreFile(filesDir).writeBytes(byteArrayOf(7, 7, 7, 7, 7, 7, 7, 7))

            BackupManager(context, RenkinPackRepository(tgtPackDb), WatchRepository(tgtWatchDb))
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
            BackupManager(context, srcPackRepo, WatchRepository(srcWatchDb)).exportBackup { out }

            // The device meanwhile has a random-password keystore — restoring the legacy
            // backup must not leave that password behind (it would lock the keystore out).
            PackKeystore.passwordFile(filesDir).writeText("stale-random")

            BackupManager(context, RenkinPackRepository(tgtPackDb), WatchRepository(tgtWatchDb))
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
        BackupManager(context, srcPackRepo, srcWatchRepo).exportProfile(2L) { out }
        val bytes = out.toByteArray()

        val tgtPackRepo = RenkinPackRepository(tgtPackDb)
        val tgtWatchRepo = WatchRepository(tgtWatchDb)
        tgtPackRepo.replaceEverything(listOf(Profile(id = DEFAULT_PROFILE_ID, name = "Renkin")), emptyList())
        val manager = BackupManager(context, tgtPackRepo, tgtWatchRepo)
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
            BackupManager(context, tgtPackRepo, tgtWatchRepo)
                .importFile { ByteArrayInputStream("definitely not a zip".toByteArray()) }
        }

        assertTrue(result.isFailure)
        assertEquals(listOf("Keep me"), tgtPackRepo.profiles().map { it.name })
        assertEquals(listOf("com.keep"), tgtPackRepo.getAll(DEFAULT_PROFILE_ID).map { it.packageName })
    }
}
