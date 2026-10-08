package dev.renkinProject.renkin.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
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
import dev.renkinProject.renkin.apk.ApplicationListState
import dev.renkinProject.renkin.apk.ProfileSessionToken
import dev.renkinProject.renkin.data.AppSortOrder
import dev.renkinProject.renkin.drawable.BitmapIconDrawable
import dev.renkinProject.renkin.packages.PackageInfoStruct
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ApplicationListLifecycleComposeTest {
    @get:Rule val compose = createComposeRule()
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private val owner = Owner()

    @After fun cleanup() {
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.DESTROYED }
    }

    @Test fun oneSnapshotUpdatesFiltersCountsAndCategoriesAndResumesWithLatestApplications() = runBlocking<Unit> {
        val app = PackageInfoStruct("App", "com.app", "Main", ColorDrawable(), 0)
        val original = ApplicationListState(1, persistentListOf(app), setOf(app.key), true, ProfileSessionToken(1, 1, "provider"))
        val state = MutableStateFlow(original)
        owner.registry.currentState = Lifecycle.State.STARTED
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                val snapshot by state.collectAsStateWithLifecycle()
                val summary = remember(snapshot.revision) {
                    val stats = calculateHeroPackStats(snapshot.applications, emptySet(), snapshot.lockedIconKeys)
                    val categories = categorizeGlobalIcons(snapshot.applications, snapshot.lockedIconKeys)
                    val filtered = snapshot.applications.sortedFilteredApps(
                        "", false, false, AppSortOrder.NAME, emptyMap(), true, snapshot.lockedIconKeys
                    ) { it }
                    "${stats.lockedCount}/${stats.missingCount}/${filtered.size}/${categories.custom.size}"
                }
                Text(summary)
            }
        }
        compose.onNodeWithText("1/0/1/0").assertIsDisplayed()
        val edited = app.changeExport(BitmapIconDrawable(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)), isCustom = true)
        withContext(Dispatchers.Default) {
            state.value = original.copy(revision = 2, applications = persistentListOf(edited), lockedIconKeys = emptySet())
        }
        compose.onNodeWithText("0/0/0/1").assertIsDisplayed()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        withContext(Dispatchers.Default) {
            state.value = original.copy(revision = 3, lockedIconKeys = emptySet())
        }
        compose.onNodeWithText("0/0/0/1").assertIsDisplayed()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        compose.onNodeWithText("0/1/0/0").assertIsDisplayed()
    }
}
