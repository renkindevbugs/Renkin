package dev.renkinProject.renkin.feature.profiles

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IconResetBadgeComposeTest {

    @get:Rule
    val compose = createComposeRule()

    private val label = "Reset to default"

    @Test
    fun badgeIsShownOnlyForACustomIcon() {
        var custom by mutableStateOf(false)
        compose.setContent { IconResetBadge(visible = custom, enabled = true, onClick = {}) }

        compose.onNodeWithContentDescription(label).assertDoesNotExist()
        custom = true
        compose.onNodeWithContentDescription(label, useUnmergedTree = true).assertExists()
    }

    @Test
    fun tappingTheBadgeResetsTheIcon() {
        var resets = 0
        compose.setContent { IconResetBadge(visible = true, enabled = true, onClick = { resets++ }) }

        val badge = compose.onNodeWithContentDescription(label)
        val touch = badge.fetchSemanticsNode().touchBoundsInRoot
        with(compose.density) {
            assertTrue(touch.width.toDp() >= 48.dp && touch.height.toDp() >= 48.dp)
        }
        badge.performClick()

        assertEquals(1, resets)
    }

    @Test
    fun disabledBadgeIgnoresTaps() {
        var resets = 0
        compose.setContent { IconResetBadge(visible = true, enabled = false, onClick = { resets++ }) }

        compose.onNodeWithContentDescription(label).performClick()

        assertEquals(0, resets)
    }
}
