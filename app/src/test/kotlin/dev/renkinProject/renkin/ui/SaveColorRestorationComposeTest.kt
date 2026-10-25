package dev.renkinProject.renkin.ui

import android.app.Application
import android.graphics.Color
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.feature.presets.ColorPresetStore
import dev.renkinProject.renkin.feature.presets.LocalColorPresets
import dev.renkinProject.renkin.feature.presets.SaveColorPresetDialog
import dev.renkinProject.renkin.icon.creator.ColorizerMode
import dev.renkinProject.renkin.icon.creator.ColorizerStyle
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Saving a colour must survive an Activity recreation as a whole: the open name prompt, the typed
 * name and the edited colour it saves. Losing only the draft would silently save the old colour.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SaveColorRestorationComposeTest {

    @get:Rule
    val compose = createComposeRule()

    private fun string(id: Int): String = RuntimeEnvironment.getApplication().getString(id)

    private val saved = mutableListOf<Pair<String, ColorizerStyle>>()
    private val store = object : ColorPresetStore {
        override val presets = emptyList<dev.renkinProject.renkin.data.ColorPreset>()
        override fun save(name: String, style: ColorizerStyle) { saved += name to style }
        override fun delete(id: Long) = Unit
    }

    @Test
    fun typedPresetNameSurvivesRecreation() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CompositionLocalProvider(LocalColorPresets provides store) {
                SaveColorPresetDialog(style = ColorizerStyle(firstColor = Color.RED), onDismiss = {})
            }
        }
        compose.onNode(hasSetTextAction()).performTextReplacement("Sunset")

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithText("Sunset").assertIsDisplayed()
    }

    @Test
    fun openSaveDialogKeepsTheEditedColourAcrossRecreation() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CompositionLocalProvider(LocalColorPresets provides store, LocalToaster provides Toaster()) {
                ColorStyleSheet(
                    title = "Outline colour",
                    initialStyle = ColorizerStyle(firstColor = Color.BLACK),
                    sampleBitmap = null,
                    onDismiss = {},
                    onApply = {}
                )
            }
        }
        compose.onNodeWithText(string(R.string.colorizerGradient)).performClick()
        compose.onNodeWithContentDescription(string(R.string.savedColorsSaveTitle)).performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("Edited")

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithText("Edited").assertIsDisplayed()
        compose.onNodeWithText(string(R.string.saveAction)).performClick()
        assertEquals("Edited", saved.single().first)
        assertEquals(ColorizerMode.GRADIENT, saved.single().second.mode)
    }
}
