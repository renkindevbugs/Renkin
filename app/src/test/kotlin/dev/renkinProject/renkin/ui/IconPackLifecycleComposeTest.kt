package dev.renkinProject.renkin.ui

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.renkinProject.renkin.apk.IconPackRepository
import dev.renkinProject.renkin.data.IconPack
import dev.renkinProject.renkin.packages.IconPackCatalog
import kotlinx.coroutines.runBlocking
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
class IconPackLifecycleComposeTest {
    @get:Rule val compose = createComposeRule()
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test fun stoppedScreenKeepsItsSnapshotAndResumeShowsLatestRepositoryState() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val repo = IconPackRepository(context, IconPackCatalog(context))
        val owner = Owner().apply { registry.currentState = Lifecycle.State.STARTED }
        suspend fun load(name: String) {
            repo.load(emptyList(), { listOf(IconPack("com.$name", name, 1, "1", 0)) }) { _, _ -> emptyList() }
        }
        load("Old")
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                val state by repo.state.collectAsStateWithLifecycle()
                Text(state.packs.singleOrNull()?.applicationName ?: "Loading")
            }
        }
        compose.onNodeWithText("Old").assertIsDisplayed()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        load("Intermediate")
        load("Latest")
        compose.onNodeWithText("Old").assertIsDisplayed()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        compose.onNodeWithText("Latest").assertIsDisplayed()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.DESTROYED }
    }
}
