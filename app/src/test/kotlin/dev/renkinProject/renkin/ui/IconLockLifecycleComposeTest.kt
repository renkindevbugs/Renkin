package dev.renkinProject.renkin.ui

import android.app.Application
import android.graphics.drawable.ColorDrawable
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.room.Room
import dev.renkinProject.renkin.apk.IconLockManager
import dev.renkinProject.renkin.data.AppSortOrder
import dev.renkinProject.renkin.data.DbApplication
import dev.renkinProject.renkin.data.RenkinPackDatabase
import dev.renkinProject.renkin.data.RenkinPackRepository
import dev.renkinProject.renkin.packages.PackageInfoStruct
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IconLockLifecycleComposeTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var db: RenkinPackDatabase
    private lateinit var manager: IconLockManager
    private val owner = Owner()
    private val app = PackageInfoStruct("App", "com.app", "Main", ColorDrawable(), 0)
    private val apps = listOf(app)
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), RenkinPackDatabase::class.java)
            .allowMainThreadQueries().build()
        manager = IconLockManager(RuntimeEnvironment.getApplication(), RenkinPackRepository(db))
        owner.registry.currentState = Lifecycle.State.STARTED
    }

    @After fun cleanup() {
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.DESTROYED }
        db.close()
    }

    private fun lock() {
        manager.lock(app.key, DbApplication(app.packageName, app.activityName, false, false, "pixels"))
        manager.publish()
    }

    @Test fun lockOnlyChangesUpdateFiltersCountsAndCategoriesAndResumeShowsLatest() = runBlocking<Unit> {
        lock()
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                val keys by manager.lockedIconKeysState.collectAsStateWithLifecycle()
                val summary by remember(keys) {
                    derivedStateOf {
                        val stats = calculateHeroPackStats(apps, emptySet(), keys)
                        val categories = categorizeGlobalIcons(apps, keys)
                        val filtered = apps.sortedFilteredApps(
                            "", false, false, AppSortOrder.NAME, emptyMap(), true, keys
                        ) { it }
                        "${stats.lockedCount}/${stats.missingCount}/${filtered.size}/${categories.iconless.size}"
                    }
                }
                Text(summary)
            }
        }
        compose.onNodeWithText("1/0/1/0").assertIsDisplayed()
        withContext(Dispatchers.Default) { manager.discard(app.key) }
        compose.onNodeWithText("0/1/0/1").assertIsDisplayed()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        withContext(Dispatchers.Default) { lock() }
        compose.onNodeWithText("0/1/0/1").assertIsDisplayed()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        compose.onNodeWithText("1/0/1/0").assertIsDisplayed()
    }
}
