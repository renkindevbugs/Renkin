package dev.renkinProject.renkin.data

import android.app.Application
import android.content.Context
import androidx.room.Room
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

/**
 * CRUD round-trip for [RenkinPackRepository] against an in-memory Room database — possible
 * because the repository now takes a [RenkinPackDatabase] (DI). Verifies that replaceAll
 * fully replaces the stored set of one profile (the saved-pack persistence relies on it),
 * without touching another profile's rows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class RenkinPackRepositoryTest {

    private lateinit var db: RenkinPackDatabase
    private lateinit var repo: RenkinPackRepository

    @Before
    fun setUp() {
        val context: Context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, RenkinPackDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = RenkinPackRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun app(pkg: String) = DbApplication(pkg, "$pkg.Main", isAdaptiveIcon = false, isXml = false, drawable = "data:$pkg")

    @Test
    fun replaceAll_thenGetAll_returnsStored() = runBlocking {
        repo.replaceAll(DEFAULT_PROFILE_ID, listOf(app("com.a"), app("com.b")))

        val all = repo.getAll(DEFAULT_PROFILE_ID)
        assertEquals(setOf("com.a", "com.b"), all.map { it.packageName }.toSet())
    }

    @Test
    fun replaceAll_replacesPreviousSet() = runBlocking {
        repo.replaceAll(DEFAULT_PROFILE_ID, listOf(app("com.a")))
        repo.replaceAll(DEFAULT_PROFILE_ID, listOf(app("com.b")))

        val all = repo.getAll(DEFAULT_PROFILE_ID)
        assertEquals(listOf("com.b"), all.map { it.packageName })
    }

    @Test
    fun replaceAll_empty_clearsStore() = runBlocking {
        repo.replaceAll(DEFAULT_PROFILE_ID, listOf(app("com.a")))
        repo.replaceAll(DEFAULT_PROFILE_ID, emptyList())

        assertTrue(repo.getAll(DEFAULT_PROFILE_ID).isEmpty())
    }

    @Test
    fun replaceAll_leavesOtherProfilesAlone() = runBlocking {
        repo.replaceAll(DEFAULT_PROFILE_ID, listOf(app("com.a")))
        repo.replaceAll(2L, listOf(app("com.b").copy(profileId = 2L)))

        repo.replaceAll(DEFAULT_PROFILE_ID, emptyList())

        assertTrue(repo.getAll(DEFAULT_PROFILE_ID).isEmpty())
        assertEquals(listOf("com.b"), repo.getAll(2L).map { it.packageName })
    }

    @Test
    fun replaceEverything_replacesAllProfilesAndIcons() = runBlocking {
        repo.createProfile(Profile(name = "old", packLabel = "old pack"))
        repo.replaceAll(DEFAULT_PROFILE_ID, listOf(app("com.old")))

        val importedProfiles = listOf(
            Profile(id = DEFAULT_PROFILE_ID, name = "Renkin", packLabel = "Renkin Pack"),
            Profile(id = 7L, name = "imported", packLabel = "Imported Pack")
        )
        val importedApps = listOf(app("com.a"), app("com.b").copy(profileId = 7L))
        repo.replaceEverything(importedProfiles, importedApps)

        assertEquals(listOf(DEFAULT_PROFILE_ID, 7L), repo.profiles().map { it.id })
        assertEquals(listOf("com.a"), repo.getAll(DEFAULT_PROFILE_ID).map { it.packageName })
        assertEquals(listOf("com.b"), repo.getAll(7L).map { it.packageName })
    }

    @Test
    fun replaceEverything_profileIdsKeepGrowingAfterImport() = runBlocking {
        // Imported profiles carry explicit ids; a profile created afterwards must not collide.
        repo.replaceEverything(
            listOf(
                Profile(id = DEFAULT_PROFILE_ID, name = "Renkin"),
                Profile(id = 9L, name = "imported")
            ),
            emptyList()
        )

        val newId = repo.createProfile(Profile(name = "fresh"))
        assertTrue("new profile id $newId must be above the imported ids", newId > 9L)
    }

    @Test
    fun getAllProfilesApplications_returnsEveryProfilesRows() = runBlocking {
        repo.replaceAll(DEFAULT_PROFILE_ID, listOf(app("com.a")))
        repo.replaceAll(2L, listOf(app("com.b").copy(profileId = 2L)))

        val all = repo.getAllProfilesApplications()
        assertEquals(setOf("com.a" to DEFAULT_PROFILE_ID, "com.b" to 2L), all.map { it.packageName to it.profileId }.toSet())
    }

    @Test
    fun getAll_preservesRowFields() = runBlocking {
        repo.replaceAll(DEFAULT_PROFILE_ID, listOf(DbApplication("com.x", "com.x.Main", isAdaptiveIcon = true, isXml = true, drawable = "blob")))

        val row = repo.getAll(DEFAULT_PROFILE_ID).single()
        assertEquals("com.x.Main", row.activityName)
        assertTrue(row.isAdaptiveIcon)
        assertTrue(row.isXml)
        assertEquals("blob", row.drawable)
    }
}
