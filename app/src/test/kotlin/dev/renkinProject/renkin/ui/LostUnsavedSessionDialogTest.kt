package dev.renkinProject.renkin.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.apk.LostUnsavedWork
import dev.renkinProject.renkin.data.UnsavedIconJournal
import dev.renkinProject.renkin.data.UnsavedIconJournalEntry
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

    private var restored = 0
    private var later = 0
    private var discarded = 0
    private val offer = UnsavedIconJournal(
        "old", 1, listOf(UnsavedIconJournalEntry("com.a/com.a.Main", null), UnsavedIconJournalEntry("com.b/com.b.Main", null)),
        droppedCount = 1
    )

    private fun showOffer(refreshedCount: Int) = compose.setContent {
        RestoreLostIconsDialog(
            LostUnsavedWork(UnsavedSessionMarker("old", 1, 3, refreshedCount), offer), "Main",
            { restored++ }, { refreshed++ }, { later++ }, { discarded++ }
        )
    }

    @Test fun restoreOfferNamesKeptDroppedAndRefreshedIcons() {
        showOffer(refreshedCount = 45)

        compose.onNodeWithText("Renkin was closed before 2 edited icons in Main could be built or saved. They can be restored as unsaved changes.")
            .assertIsDisplayed()
        compose.onNodeWithText("1 edited icon couldn't be kept (too large).").assertIsDisplayed()
        compose.onNodeWithText(string(R.string.lostUnsavedRefresh)).performClick()
        assertEquals(1, refreshed)
        compose.onNodeWithText(string(R.string.lostIconsRestore)).performClick()
        assertEquals(1, restored)
    }

    @Test fun restoreOfferAgreesWithASingleIcon() {
        val single = UnsavedIconJournal("old", 1, listOf(UnsavedIconJournalEntry("com.a/com.a.Main", null)), droppedCount = 0)
        compose.setContent {
            RestoreLostIconsDialog(
                LostUnsavedWork(UnsavedSessionMarker("old", 1, 1, 1), single), "Main",
                { restored++ }, { refreshed++ }, { later++ }, { discarded++ }
            )
        }

        compose.onNodeWithText("Renkin was closed before 1 edited icon in Main could be built or saved. It can be restored as an unsaved change.")
            .assertIsDisplayed()
        compose.onNodeWithText("1 refreshed icon wasn't kept. Refresh again restores the icons above and regenerates it.")
            .assertIsDisplayed()
    }

    @Test fun unbuiltChangesLabelAgreesWithItsCount() {
        val resources = RuntimeEnvironment.getApplication().resources

        assertEquals("1 change not built yet", resources.getQuantityString(R.plurals.unbuiltChangesCount, 1, 1))
        assertEquals("2 changes not built yet", resources.getQuantityString(R.plurals.unbuiltChangesCount, 2, 2))
    }

    @Test fun refreshIsOfferedOnlyWithRefreshedIcons() {
        showOffer(refreshedCount = 0)

        compose.onNodeWithText(string(R.string.lostUnsavedRefresh)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.editorRecoveryLater)).performClick()
        assertEquals(1, later)
        assertEquals(0, discarded)
    }

    @Test fun discardNeedsConfirmation() {
        showOffer(refreshedCount = 0)

        compose.onNodeWithText(string(R.string.editorRecoveryDiscard)).performClick()
        assertEquals(0, discarded)
        compose.onNodeWithText(string(R.string.lostIconsDiscardTitle)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.editorRecoveryDiscard)).performClick()
        assertEquals(1, discarded)
    }

    @Test fun okOnlyDismissesAndRefreshIsHiddenWhenUnavailable() {
        show(UnsavedSessionMarker("old", 1, editedCount = 0, refreshedCount = 3))

        compose.onNodeWithText(string(R.string.lostUnsavedRefresh)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.ok)).performClick()
        assertEquals(1, dismissed)
        assertEquals(0, refreshed)
    }
}
