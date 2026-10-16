package dev.renkinProject.renkin.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.data.UnsavedSessionMarker
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
class LostUnsavedSessionDialogTest {
    @get:Rule val compose = createComposeRule()
    private var refreshed = 0
    private var dismissed = 0
    private fun string(id: Int, vararg args: Any) = RuntimeEnvironment.getApplication().getString(id, *args)

    private fun show(marker: UnsavedSessionMarker, profileName: String? = "Main", canRefresh: Boolean = false) =
        compose.setContent {
            LostUnsavedSessionDialog(marker, profileName, canRefresh, { refreshed++ }, { dismissed++ })
        }

    @Test fun namesBothKindsOfLostIconsAndTheProfile() {
        show(UnsavedSessionMarker("old", 1, editedCount = 1, refreshedCount = 45), canRefresh = true)

        compose.onNodeWithText("Renkin was closed before 1 edited icon and 45 refreshed icons in Main could be built or saved.")
            .assertIsDisplayed()
        compose.onNodeWithText(string(R.string.lostUnsavedRefresh)).performClick()
        assertEquals(1, refreshed)
        assertEquals(0, dismissed)
    }

    @Test fun deletedProfileUsesTheGenericText() {
        show(UnsavedSessionMarker("old", 9, editedCount = 2, refreshedCount = 0), profileName = null)

        compose.onNodeWithText("Renkin was closed before 2 edited icons could be built or saved.").assertIsDisplayed()
    }

    @Test fun okOnlyDismissesAndRefreshIsHiddenWhenUnavailable() {
        show(UnsavedSessionMarker("old", 1, editedCount = 0, refreshedCount = 3))

        compose.onNodeWithText(string(R.string.lostUnsavedRefresh)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.ok)).performClick()
        assertEquals(1, dismissed)
        assertEquals(0, refreshed)
    }
}
