package dev.renkinProject.renkin.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.renkinProject.renkin.EditorDraftRecoveryState
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.data.editor.EditorDraftCheckpoint
import org.junit.Assert.*
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
class EditorDraftRecoveryDialogTest {
    @get:Rule val compose = createComposeRule()
    private val checkpoint = EditorDraftCheckpoint("generation", "draft")
    private val offer = EditorDraftRecoveryState.Offer(checkpoint, "Example", 1)
    private var restored: EditorDraftRecoveryState? = null
    private var discarded: EditorDraftRecoveryState? = null
    private var postponed: EditorDraftRecoveryState? = null
    private var retried: EditorDraftRecoveryState? = null
    private fun string(id: Int, vararg args: Any) = RuntimeEnvironment.getApplication().getString(id, *args)
    private fun show(state: EditorDraftRecoveryState = offer) = compose.setContent {
        EditorDraftRecoveryDialog(state, { restored = it }, { retried = it }, { discarded = it }, { postponed = it })
    }

    @Test fun restoreUsesTheDisplayedOfferOnly() {
        show()
        compose.onNodeWithText(string(R.string.editorRecoveryText, "Example")).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.editorRecoveryRestore)).performClick()
        assertSame(offer, restored)
        assertNull(discarded); assertNull(postponed)
    }

    @Test fun notNowPostponesWithoutDeleting() {
        show()
        compose.onNodeWithText(string(R.string.editorRecoveryLater)).performClick()
        assertSame(offer, postponed)
        assertNull(restored); assertNull(discarded)
    }

    @Test fun discardRequiresConfirmation() {
        show()
        compose.onNodeWithText(string(R.string.editorRecoveryDiscard)).performClick()
        compose.onNodeWithText(string(R.string.editorRecoveryDiscardTitle)).assertIsDisplayed()
        assertNull(discarded)
        compose.onNodeWithText(string(R.string.editorRecoveryDiscard)).performClick()
        assertSame(offer, discarded)
        assertNull(restored); assertNull(postponed)
    }

    @Test fun cancellingDiscardKeepsTheOffer() {
        show()
        compose.onNodeWithText(string(R.string.editorRecoveryDiscard)).performClick()
        compose.onNodeWithText(string(R.string.dismiss)).performClick()
        compose.onNodeWithText(string(R.string.editorRecoveryRestore)).assertIsDisplayed()
        assertNull(discarded)
    }

    @Test fun failureWithoutCheckpointOrLeaseDisablesDiscardButAllowsRetry() {
        val failed = EditorDraftRecoveryState.Failed(null, null)
        show(failed)
        compose.onNodeWithText(string(R.string.editorRecoveryDiscard)).assertIsNotEnabled()
        compose.onNodeWithText(string(R.string.editorRecoveryRetry)).performClick()
        assertSame(failed, retried)
        assertNull(discarded)
    }

    @Test fun cleanupFailureExplainsRetryAndKeepsCheckpointDiscardAvailable() {
        val failed = EditorDraftRecoveryState.Failed(checkpoint, null, closingEditor = true)
        show(failed)
        compose.onNodeWithText(string(R.string.editorRecoveryCleanupFailed)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.editorRecoveryRetry)).performClick()
        assertSame(failed, retried)
    }

    @Test fun otherProfileNamesItsOwnerAndCanBePostponed() {
        val other = EditorDraftRecoveryState.NeedsProfile(checkpoint, 42, "Work")
        show(other)
        compose.onNodeWithText(string(R.string.editorRecoveryOtherProfile, "Work")).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.editorRecoveryLater)).performClick()
        assertSame(other, postponed)
        assertNull(restored); assertNull(discarded)
    }
}
