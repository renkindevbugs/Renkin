package dev.renkinProject.renkin.ui

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.renkinProject.renkin.MainViewModel
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.data.transfer.BackupManager
import dev.renkinProject.renkin.feature.backup.BackupRestorePreviewDialog
import dev.renkinProject.renkin.feature.profiles.SaveBeforeSwitchDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Contracts of the dialogs built on the shared MessageDialog layout. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MessageDialogsComposeTest {

    @get:Rule
    val compose = createComposeRule()

    private fun string(id: Int, vararg args: Any): String =
        RuntimeEnvironment.getApplication().getString(id, *args)

    private fun outcome(kind: MainViewModel.BuildOutcome) =
        MainViewModel.BuildOutcomeInfo(kind, "Pastel", "System installer", savingProfile = false, profileSaveFailed = false)

    @Test
    fun firstInstall_leadsWithThePackAndListsGenericSetupSteps() {
        var dismissed = 0
        compose.setContent { BuildOutcomeDialog(outcome(MainViewModel.BuildOutcome.FIRST_INSTALL)) { dismissed++ } }

        compose.onNodeWithText("Pastel").assertIsDisplayed()
        compose.onNodeWithText(string(R.string.installDoneVia, "System installer")).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.installDoneNextTitle)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Choose “Pastel”").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(string(R.string.installDoneConfirm)).performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun update_explainsTheCacheAndListsRefreshSteps() {
        compose.setContent { BuildOutcomeDialog(outcome(MainViewModel.BuildOutcome.UPDATE)) {} }

        compose.onNodeWithText(string(R.string.buildUpdatedTitle)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.installDoneRefreshTitle)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Switch back to “Pastel”").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(string(R.string.installDoneNextTitle)).assertDoesNotExist()
    }

    @Test
    fun replacePack_showsBothStepsAndConfirmsOnlyFromItsAction() {
        var confirmed = 0
        var dismissed = 0
        compose.setContent { InstallFallbackDialog(signatureMismatch = true, { confirmed++ }, { dismissed++ }) }

        compose.onNodeWithText(string(R.string.installFallbackSignatureText)).assertIsDisplayed()
        compose.onNodeWithText("Renkin uninstalls the existing pack").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Then it installs the new one").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(string(R.string.installFallbackAction)).performClick()
        assertEquals(1, confirmed)
        assertEquals(0, dismissed)
    }

    @Test
    fun saveBeforeSwitch_keepsDontSaveLeftOfSave() {
        var saved = 0
        var discarded = 0
        compose.setContent { SaveBeforeSwitchDialog({ saved++ }, { discarded++ }, {}) }

        val dontSave = compose.onNodeWithText(string(R.string.discardAction)).getUnclippedBoundsInRoot()
        val save = compose.onNodeWithText(string(R.string.saveAction)).getUnclippedBoundsInRoot()
        assertTrue("Don't save must sit left of Save", dontSave.right <= save.left)
        assertEquals(dontSave.top, save.top)
        compose.onNodeWithText(string(R.string.saveAction)).performClick()
        assertEquals(1, saved)
        assertEquals(0, discarded)
    }

    @Test
    fun backupPreview_expandsToNameEveryProfile() {
        val preview = BackupManager.BackupPreview(
            appVersion = "2026.10.03",
            exportedAt = 0L,
            profiles = listOf(BackupManager.ProfilePreview("Default", 10), BackupManager.ProfilePreview("Work", 4)),
            activeWatchRules = 1,
            completedWatchRules = 2,
            savedStyles = 0,
            uploadedImages = 0
        )
        compose.setContent { BackupRestorePreviewDialog(preview, {}, {}) }

        compose.onNodeWithText("Work").assertDoesNotExist()
        compose.onNodeWithText(string(R.string.backupPreviewProfiles)).performScrollTo().performClick()
        compose.onNodeWithText("Work").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Default").assertIsDisplayed()
    }

    @Test
    fun confirmDialog_namedTargetLeadsAndActionBecomesTheEyebrow() {
        var confirmed = 0
        compose.setContent {
            CompositionLocalProvider(LocalToaster provides Toaster()) {
                ConfirmDialog(
                    title = "Work", text = null, eyebrow = "Delete profile", confirmLabel = "Delete",
                    onConfirm = { confirmed++ }, onDismiss = {}
                )
            }
        }

        compose.onNodeWithText("Work").assertIsDisplayed()
        compose.onNodeWithText("Delete profile").assertIsDisplayed()
        compose.onNodeWithText("Delete").performClick()
        assertEquals(1, confirmed)
    }
}
