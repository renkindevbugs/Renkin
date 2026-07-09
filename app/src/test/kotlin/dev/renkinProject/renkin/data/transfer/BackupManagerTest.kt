package dev.renkinProject.renkin.data.transfer

import android.app.Application
import android.content.Context
import androidx.room.Room
import dev.renkinProject.renkin.data.DEFAULT_PROFILE_ID
import dev.renkinProject.renkin.data.DbApplication
import dev.renkinProject.renkin.data.Profile
import dev.renkinProject.renkin.data.RenkinPackDatabase
import dev.renkinProject.renkin.data.RenkinPackRepository
import dev.renkinProject.renkin.data.watch.AppComponent
import dev.renkinProject.renkin.data.watch.WatchDatabase
import dev.renkinProject.renkin.data.watch.WatchRepository
import dev.renkinProject.renkin.data.watch.WatchRuleImport
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
    fun import_rejectsGarbageWithoutTouchingStores() = runBlocking {
        val tgtPackRepo = RenkinPackRepository(tgtPackDb)
        val tgtWatchRepo = WatchRepository(tgtWatchDb)
        tgtPackRepo.replaceEverything(
            listOf(Profile(id = DEFAULT_PROFILE_ID, name = "Keep me")),
            listOf(DbApplication("com.keep", "com.keep.Main", isAdaptiveIcon = false, isXml = false, drawable = "ZGF0YQ=="))
        )

        val result = runCatching {
            BackupManager(context, tgtPackRepo, tgtWatchRepo)
                .importBackup { ByteArrayInputStream("definitely not a zip".toByteArray()) }
        }

        assertTrue(result.isFailure)
        assertEquals(listOf("Keep me"), tgtPackRepo.profiles().map { it.name })
        assertEquals(listOf("com.keep"), tgtPackRepo.getAll(DEFAULT_PROFILE_ID).map { it.packageName })
    }
}
