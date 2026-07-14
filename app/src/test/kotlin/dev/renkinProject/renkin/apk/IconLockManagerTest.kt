package dev.renkinProject.renkin.apk

import android.app.Application
import android.content.Context
import androidx.room.Room
import dev.renkinProject.renkin.data.DbApplication
import dev.renkinProject.renkin.data.RenkinPackDatabase
import dev.renkinProject.renkin.data.RenkinPackRepository
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
}
