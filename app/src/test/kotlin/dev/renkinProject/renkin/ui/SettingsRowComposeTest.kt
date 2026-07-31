package dev.renkinProject.renkin.ui

import android.app.Application
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Settings rows that start long work (sync, app-list reload, backup import/export) used to
 * swallow taps silently while running. These pin the replacement: a busy row is inert, and an
 * idle one still fires — so a future refactor can't quietly restore the dead-row behaviour.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsRowComposeTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun idleRow_firesOnClick() {
        var clicks = 0
        compose.setContent {
            SettingsRow(Icons.Filled.Sync, "Sync icon packs", busy = false) { clicks++ }
        }

        compose.onNodeWithText("Sync icon packs").assertIsDisplayed()
        compose.onNodeWithText("Sync icon packs").performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun busyRow_ignoresClicks() {
        var clicks = 0
        compose.setContent {
            SettingsRow(Icons.Filled.Sync, "Sync icon packs", busy = true) { clicks++ }
        }

        // Still readable — only the action is suspended, the row doesn't disappear.
        compose.onNodeWithText("Sync icon packs").assertIsDisplayed()
        compose.onNodeWithText("Sync icon packs").performClick()
        assertEquals(0, clicks)
    }
}
