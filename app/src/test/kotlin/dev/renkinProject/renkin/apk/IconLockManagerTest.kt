package dev.renkinProject.renkin.apk

import android.app.Application
import android.content.Context
import androidx.room.Room
import dev.renkinProject.renkin.data.DbApplication
import dev.renkinProject.renkin.data.RenkinPackDatabase
import dev.renkinProject.renkin.data.RenkinPackRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class IconLockManagerTest {
    private lateinit var db: RenkinPackDatabase
    private lateinit var manager: IconLockManager

    @Before
    fun setUp() {
        val context: Context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, RenkinPackDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        manager = IconLockManager(context, RenkinPackRepository(db))
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun discardRemovesHeldRowAndPublishedLockKey() {
        val key = "com.example/com.example.Main"
        manager.lock(
            key,
            DbApplication(
                packageName = "com.example",
                activityName = "com.example.Main",
                isAdaptiveIcon = false,
                isXml = false,
                drawable = "pixels",
                sourcePackName = "missing.pack"
            )
        )
        manager.publish()
        assertEquals(setOf(key), manager.lockedIconKeys)

        manager.discard(key)

        assertTrue(manager.lockedIconKeys.isEmpty())
        assertTrue(manager.preservedRows().isEmpty())
    }

    private fun row(name: String) = DbApplication(name, "Main", false, false, "pixels-$name", sourcePackName = "missing.pack")

    @Test fun stagedLocksAndReleasesDoNotMutatePreviouslyPublishedSnapshots() {
        val first = row("first")
        val second = row("second")
        manager.lock("first/Main", first)
        assertTrue(manager.lockedIconKeysState.value.isEmpty())
        assertEquals(listOf(first), manager.preservedRows().toList())
        manager.publish()
        val previous = manager.lockedIconKeysState.value
        manager.lock("second/Main", second)
        manager.release("first/Main")
        assertEquals(setOf("first/Main"), previous)
        assertEquals(previous, manager.lockedIconKeysState.value)
        manager.publish()
        assertEquals(setOf("second/Main"), manager.lockedIconKeysState.value)
        assertEquals(setOf("first/Main"), previous)
    }

    @Test fun replacingSomeLockedRowsPublishesImmediatelyAndPreservesOtherHeldRows() {
        val first = row("first")
        val second = row("second")
        val orphan = row("orphan")
        manager.lock("first/Main", first)
        manager.lock("second/Main", second)
        manager.holdOrphan("orphan/Main", orphan)
        manager.publish()
        manager.releaseReplaced(setOf("first/Main", "absent/Main"))
        assertEquals(setOf("second/Main"), manager.lockedIconKeysState.value)
        assertEquals(setOf(second, orphan), manager.preservedRows().toSet())
    }

    @Test fun clearRemovesPublishedLocksAndOrphansBeforeLoadingAnotherProfile() {
        manager.lock("first/Main", row("first"))
        manager.holdOrphan("orphan/Main", row("orphan"))
        manager.publish()
        val previous = manager.lockedIconKeysState.value
        manager.clear()
        assertTrue(manager.lockedIconKeysState.value.isEmpty())
        assertTrue(manager.preservedRows().isEmpty())
        manager.lock("other/Main", row("other"))
        manager.publish()
        assertEquals(setOf("other/Main"), manager.lockedIconKeysState.value)
        assertEquals(setOf("first/Main"), previous)
    }

    @Test fun lateCollectorSeesLatestSnapshotPublishedOffMain() = runBlocking<Unit> {
        withContext(Dispatchers.Default) {
            manager.lock("first/Main", row("first"))
            manager.publish()
            manager.discard("first/Main")
            manager.lock("latest/Main", row("latest"))
            manager.publish()
        }
        assertEquals(setOf("latest/Main"), manager.lockedIconKeysState.first())
        assertEquals(manager.lockedIconKeysState.value, manager.lockedIconKeys)
    }
}
