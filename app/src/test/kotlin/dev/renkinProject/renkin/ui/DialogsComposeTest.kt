package dev.renkinProject.renkin.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.apk.IconLockManager.MissingPack
import dev.renkinProject.renkin.data.VERDICT_PAID
import dev.renkinProject.renkin.data.VERDICT_UNKNOWN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Compose tests for the shared dialogs, run locally on Robolectric. They pin the contract
 * the flows rely on — which callback fires from which button — not the visual styling
 * (Robolectric draws nothing, so pixels are out of scope by design).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DialogsComposeTest {

    @get:Rule
    val compose = createComposeRule()

    private fun string(id: Int, vararg args: Any): String =
        RuntimeEnvironment.getApplication().getString(id, *args)

    // ---- ConfirmDialog ---------------------------------------------------------------

    @Test
    fun confirmDialog_confirmButtonFiresOnConfirmOnly() {
        var confirmed = false
        var dismissed = false
        compose.setContent {
            ConfirmDialog(
                title = "Delete profile",
                text = "Removes **everything** in it",
                onConfirm = { confirmed = true },
                onDismiss = { dismissed = true }
            )
        }

        // Bold markers must render as styling, never as literal asterisks.
        compose.onNodeWithText("Removes everything in it").assertIsDisplayed()

        compose.onNodeWithText(string(R.string.confirm)).performClick()
        assertTrue(confirmed)
        assertFalse(dismissed)
    }

    @Test
    fun confirmDialog_dismissButtonFiresOnDismissOnly() {
        var confirmed = false
        var dismissed = false
        compose.setContent {
            ConfirmDialog(
                title = "Delete profile",
                text = "Removes everything",
                onConfirm = { confirmed = true },
                onDismiss = { dismissed = true }
            )
        }

        compose.onNodeWithText(string(R.string.dismiss)).performClick()
        assertTrue(dismissed)
        assertFalse(confirmed)
    }

    // ---- MissingPacksDialog ----------------------------------------------------------

    private val paidPack = MissingPack("com.pack.paid", "Paid Icons", VERDICT_PAID, 12)
    private val pendingPack = MissingPack("com.pack.new", "Fresh Icons", VERDICT_UNKNOWN, 3)

    @Test
    fun missingPacksDialog_listsEveryPackWithItsLockedCount() {
        compose.setContent {
            MissingPacksDialog(listOf(paidPack, pendingPack)) {}
        }

        compose.onNodeWithText("Paid Icons").assertIsDisplayed()
        compose.onNodeWithText(string(R.string.missingPackPaid, 12)).assertIsDisplayed()
        compose.onNodeWithText("Fresh Icons").assertIsDisplayed()
        compose.onNodeWithText(string(R.string.missingPackPending, 3)).assertIsDisplayed()
    }

    @Test
    fun missingPacksDialog_okReportsDontShowAgainUnchangedByDefault() {
        var dontShowAgain: Boolean? = null
        compose.setContent {
            MissingPacksDialog(listOf(paidPack)) { dontShowAgain = it }
        }

        compose.onNodeWithText(string(R.string.ok)).performClick()
        assertEquals(false, dontShowAgain)
    }

    @Test
    fun missingPacksDialog_okReportsTickedDontShowAgain() {
        var dontShowAgain: Boolean? = null
        compose.setContent {
            MissingPacksDialog(listOf(paidPack)) { dontShowAgain = it }
        }

        compose.onNode(isToggleable()).performClick()
        compose.onNodeWithText(string(R.string.ok)).performClick()
        assertEquals(true, dontShowAgain)
    }

    // ---- MissingPacksBanner ----------------------------------------------------------

    @Test
    fun missingPacksBanner_showsCountsAndOpensOnTap() {
        var opened = false
        compose.setContent {
            MissingPacksBanner(packCount = 2, iconCount = 15) { opened = true }
        }

        compose.onNodeWithText(string(R.string.missingPacksBanner, 2, 15)).performClick()
        assertTrue(opened)
    }
}
