package dev.renkinProject.renkin.ui

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.renkinProject.renkin.EditorAutosaveState
import dev.renkinProject.renkin.EditorAutosaveStatus
import dev.renkinProject.renkin.EditorSessionLifetime
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
class EditorAutosaveNoticeTest {
    @get:Rule val compose = createComposeRule()
    private val lifetime = EditorSessionLifetime(1, "editor")
    private fun string(id: Int, vararg args: Any) = RuntimeEnvironment.getApplication().getString(id, *args)

    @Test fun routineStatusesStaySilent() {
        val status = mutableStateOf(EditorAutosaveStatus.WAITING)
        compose.setContent { EditorAutosaveNotice(EditorAutosaveState(lifetime, status.value), {}, {}) }
        for (next in listOf(EditorAutosaveStatus.WAITING, EditorAutosaveStatus.SAVING, EditorAutosaveStatus.SAVED)) {
            compose.runOnIdle { status.value = next }
            compose.onNodeWithText(string(R.string.editorRecoveryRetry)).assertDoesNotExist()
            compose.onNodeWithText(string(R.string.editorRecoveryDiscard)).assertDoesNotExist()
        }
    }

    @Test fun blockedDraftNamesItsOwnerAndRequiresConfirmationBeforeDiscard() {
        val shown = EditorAutosaveState(lifetime, EditorAutosaveStatus.BLOCKED,
            EditorDraftCheckpoint("generation", "draft"), draftApplicationName = "Other app")
        var discarded: EditorAutosaveState? = null
        compose.setContent { EditorAutosaveNotice(shown, {}, { discarded = it }) }
        compose.onNodeWithText(string(R.string.editorAutosaveBlocked)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.editorRecoveryDiscard)).performClick()
        assertNull(discarded)
        compose.onNodeWithText(string(R.string.editorAutosaveDiscardNamedText, "Other app")).assertIsDisplayed()
        compose.onNode(hasText(string(R.string.editorRecoveryDiscard)) and hasAnyAncestor(isDialog())).performClick()
        assertSame(shown, discarded)
    }

    @Test fun failedNoticeRetriesExactShownStateAndDisablesUnsafeDiscard() {
        val shown = EditorAutosaveState(lifetime, EditorAutosaveStatus.FAILED)
        var retried: EditorAutosaveState? = null
        compose.setContent { EditorAutosaveNotice(shown, { retried = it }, { error("Unsafe discard") }) }
        compose.onNodeWithText(string(R.string.editorAutosaveFailed)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.editorRecoveryDiscard)).assertIsNotEnabled()
        compose.onNodeWithText(string(R.string.editorRecoveryRetry)).performClick()
        assertSame(shown, retried)
    }
}
