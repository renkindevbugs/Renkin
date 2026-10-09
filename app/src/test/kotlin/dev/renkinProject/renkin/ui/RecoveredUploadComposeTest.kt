package dev.renkinProject.renkin.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.renkinProject.renkin.R
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
class RecoveredUploadComposeTest {
    @get:Rule val compose = createComposeRule()

    private fun state() = UploadEditorState().apply {
        selectedImagePath = "/missing/recovered-gallery.png"
        asAdaptiveIcon = true
        zoomLevel = 0.7f
        ownedImage = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
        publishedSelection = selection()
    }

    @Test fun missingGalleryFileDoesNotClearOrRepublishRecoveredUploadAcrossTabRemount() {
        val state = state()
        val visible = mutableStateOf(true)
        var publications = 0
        compose.setContent {
            CompositionLocalProvider(LocalToaster provides Toaster()) {
                if (visible.value) UploadColumn(SnackbarHostState(), state) { _, _ -> publications++ }
            }
        }
        val message = RuntimeEnvironment.getApplication().getString(R.string.deadZone)
        compose.onNodeWithText(message).assertExists()
        compose.runOnIdle { visible.value = false }
        compose.runOnIdle { visible.value = true }
        compose.onNodeWithText(message).assertExists()
        compose.runOnIdle {
            assertEquals(0, publications)
            assertEquals("/missing/recovered-gallery.png", state.selectedImagePath)
            assertEquals(state.selection(), state.publishedSelection)
            assertFalse(state.ownedImage!!.isRecycled)
        }
    }

    @Test fun changingRecoveredZoomPublishesOwnedPixelsWithTheNewSelection() {
        val state = state()
        var publications = 0
        compose.setContent {
            CompositionLocalProvider(LocalToaster provides Toaster()) {
                UploadColumn(SnackbarHostState(), state) { icon, path ->
                    assertNotNull(icon); assertEquals(state.selectedImagePath, path)
                    publications++; state.publishedSelection = state.selection()
                }
            }
        }
        compose.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.deadZone)).assertExists()
        compose.runOnIdle { state.zoomLevel = 0.8f }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("Changed zoom must publish exactly once", 1, publications)
            assertEquals(state.selection(), state.publishedSelection)
        }
    }
}
