package dev.renkinProject.renkin.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
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
 * The picker rows are one option out of a list, so they must carry selection semantics — a
 * plain clickable row leaves TalkBack announcing the pack name with no hint of which one is
 * active, and the check mark is a purely visual cue a screen reader never sees.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PickerRowSemanticsTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun selectedRow_reportsItsSelectionToAccessibility() {
        compose.setContent {
            PickerRow(title = "Lawnicons", subtitle = "160 of 197", icon = null, selected = true) {}
        }

        compose.onNodeWithText("Lawnicons").assertIsDisplayed()
        compose.onNodeWithText("Lawnicons").assertIsSelected()
    }

    @Test
    fun unselectedRow_reportsItIsNotSelected() {
        compose.setContent {
            PickerRow(title = "Arcticons", subtitle = null, icon = null, selected = false) {}
        }

        compose.onNodeWithText("Arcticons").assertIsNotSelected()
    }

    @Test
    fun rowStillFiresItsClick() {
        var picks = 0
        compose.setContent {
            PickerRow(title = "Delta", subtitle = null, icon = null, selected = false) { picks++ }
        }

        compose.onNodeWithText("Delta").performClick()
        assertEquals(1, picks)
    }
}
