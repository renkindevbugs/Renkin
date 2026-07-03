package dev.renkinProject.renkin.data.watch

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
 * Exercises the real [WatchRepository] CRUD against an in-memory Room database. This is
 * possible because the repository now takes a [WatchDatabase] (DI), so a test database can
 * stand in for the production singleton. A plain [Application] (not the @HiltAndroidApp one)
 * and a pinned SDK keep the test off Hilt and off the very new compileSdk.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class WatchRepositoryTest {

    private lateinit var db: WatchDatabase
    private lateinit var repo: WatchRepository

    @Before
    fun setUp() {
        val context: Context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, WatchDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = WatchRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun createRule_isReturnedByGetActiveRules() = runBlocking {
        val id = repo.createRule(
            apps = listOf(AppComponent("com.a", "A")),
            watchAllPacks = false,
            packPackages = listOf("pack1")
        )

        val rules = repo.getActiveRules()
        assertEquals(1, rules.size)
        val rule = rules.first()
        assertEquals(id, rule.rule.id)
        assertEquals(false, rule.rule.watchAllPacks)
        assertEquals(listOf("com.a"), rule.apps.map { it.packageName })
        assertEquals(listOf("pack1"), rule.packs.map { it.iconPackPackage })
    }

    @Test
    fun watchAllPacks_doesNotStorePackList() = runBlocking {
        repo.createRule(
            apps = listOf(AppComponent("com.a", "A")),
            watchAllPacks = true,
            packPackages = listOf("pack1", "pack2")
        )

        val rule = repo.getActiveRules().first()
        assertTrue(rule.rule.watchAllPacks)
        assertTrue(rule.packs.isEmpty())
    }

    @Test
    fun deleteRule_removesIt() = runBlocking {
        val id = repo.createRule(listOf(AppComponent("com.a", "A")), false, listOf("pack1"))

        repo.deleteRule(id)

        assertTrue(repo.getActiveRules().isEmpty())
    }

    @Test
    fun updateRule_replacesAppsAndPacks() = runBlocking {
        val id = repo.createRule(listOf(AppComponent("com.a", "A")), false, listOf("pack1"))

        repo.updateRule(
            ruleId = id,
            apps = listOf(AppComponent("com.b", "B")),
            watchAllPacks = false,
            packPackages = listOf("pack2")
        )

        val rule = repo.getRule(id)!!
        assertEquals(listOf("com.b"), rule.apps.map { it.packageName })
        assertEquals(listOf("pack2"), rule.packs.map { it.iconPackPackage })
    }
}
