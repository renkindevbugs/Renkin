package dev.renkinProject.renkin.ui

import android.app.Application
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.renkinProject.renkin.R
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The shared fullscreen chrome must keep every screen's way out (the back arrow) and its slots,
 * and the crash-log GitHub link must survive a device with no browser instead of crashing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FullscreenDialogScaffoldComposeTest {

    @get:Rule
    val compose = createComposeRule()

    private val app get() = RuntimeEnvironment.getApplication()

    @After
    fun removeCrashFiles() {
        File(app.filesDir, "crashes").deleteRecursively()
    }

    @Test
    fun backArrowDismissesAndEverySlotIsShown() {
        var dismissed = 0
        compose.setContent {
            FullscreenDialogScaffold(
                title = "Screen title",
                onDismiss = { dismissed++ },
                actions = { Text("Action slot") },
                bottomBar = { Text("Bottom slot") }
            ) { padding -> Text("Body", Modifier.padding(padding)) }
        }

        compose.onNodeWithText("Screen title").assertIsDisplayed()
        compose.onNodeWithText("Action slot").assertIsDisplayed()
        compose.onNodeWithText("Bottom slot").assertIsDisplayed()
        compose.onNodeWithText("Body").assertIsDisplayed()

        compose.onNodeWithContentDescription(app.getString(R.string.close)).performClick()

        assertEquals(1, dismissed)
    }

    @Test
    fun githubLinkWithoutABrowserExplainsInsteadOfCrashing() {
        File(app.filesDir, "crashes").apply { mkdirs() }
            .resolve("crash_1700000000000.txt").writeText("Renkin crash report")
        // No browser installed: starting the link activity throws, as on a stripped ROM.
        shadowOf(app).checkActivities(true)
        val toaster = Toaster()
        compose.setContent {
            CompositionLocalProvider(LocalToaster provides toaster) {
                CrashLogsScreen(onDismiss = {})
            }
        }

        val link = app.getString(R.string.crashLogsReport)
        // The list is read off the main thread, so the link appears a moment after composition.
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithText(link).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(link).performClick()

        val message = runBlocking { withTimeout(WAIT_MS) { toaster.events.first() } }
        assertEquals(app.getString(R.string.linkOpenFailed), message)
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
