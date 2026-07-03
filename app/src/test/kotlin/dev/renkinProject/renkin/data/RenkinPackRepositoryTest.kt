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
 * fully replaces the stored set (the behaviour the saved-pack persistence relies on).
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
        repo.replaceAll(listOf(app("com.a"), app("com.b")))

        val all = repo.getAll()
        assertEquals(setOf("com.a", "com.b"), all.map { it.packageName }.toSet())
    }

    @Test
    fun replaceAll_replacesPreviousSet() = runBlocking {
        repo.replaceAll(listOf(app("com.a")))
        repo.replaceAll(listOf(app("com.b")))

        val all = repo.getAll()
        assertEquals(listOf("com.b"), all.map { it.packageName })
    }

    @Test
    fun replaceAll_empty_clearsStore() = runBlocking {
        repo.replaceAll(listOf(app("com.a")))
        repo.replaceAll(emptyList())

        assertTrue(repo.getAll().isEmpty())
    }

    @Test
    fun getAll_preservesRowFields() = runBlocking {
        repo.replaceAll(listOf(DbApplication("com.x", "com.x.Main", isAdaptiveIcon = true, isXml = true, drawable = "blob")))

        val row = repo.getAll().single()
        assertEquals("com.x.Main", row.activityName)
        assertTrue(row.isAdaptiveIcon)
        assertTrue(row.isXml)
        assertEquals("blob", row.drawable)
    }
}
