package dev.renkinProject.renkin.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.data.DEFAULT_PROFILE_ID
import dev.renkinProject.renkin.data.Profile
import org.junit.Assert.assertEquals
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
class ProfileActionsMenuComposeTest {

    @get:Rule
    val compose = createComposeRule()

    private fun string(id: Int): String = RuntimeEnvironment.getApplication().getString(id)

    @Test
    fun regularProfileOffersEveryAction() {
        compose.setContent {
            ProfileActionsMenu(
                profile = Profile(id = 2, name = "Personal"),
                shareEnabled = true,
                onBack = {},
                onShare = {},
                onEdit = {},
                onDelete = {}
            )
        }

        compose.onNodeWithText(string(R.string.shareProfile)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.editProfile)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.deleteProfile)).assertIsDisplayed()
    }

    @Test
    fun defaultProfileOnlyOffersShare() {
        compose.setContent {
            ProfileActionsMenu(
                profile = Profile(id = DEFAULT_PROFILE_ID, name = "Renkin"),
                shareEnabled = true,
                onBack = {},
                onShare = {},
                onEdit = {},
                onDelete = {}
            )
        }

        compose.onNodeWithText(string(R.string.shareProfile)).assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText(string(R.string.editProfile)).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText(string(R.string.deleteProfile)).fetchSemanticsNodes().size)
    }
}
