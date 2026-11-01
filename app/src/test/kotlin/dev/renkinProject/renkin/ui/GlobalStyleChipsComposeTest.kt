package dev.renkinProject.renkin.ui

import android.app.Application
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.compose.ui.test.junit4.createComposeRule
import dev.renkinProject.renkin.data.GlobalShadowBlurKey
import dev.renkinProject.renkin.data.GlobalShadowKey
import dev.renkinProject.renkin.data.GlobalShadowOpacityKey
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlobalStyleChipsComposeTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aDisabledShadowIsNotListed() {
        assertEquals(listOf("No effects"), chips(emptyPreferences()))
    }

    @Test
    fun aPresetShadowIsListedByItsPresetName() {
        assertEquals(listOf("Shadow: Soft"), chips(mutablePreferencesOf(GlobalShadowKey to true)))
    }

    @Test
    fun aCustomShadowIsListedByBlurAndOpacity() {
        val recipe = mutablePreferencesOf(GlobalShadowKey to true, GlobalShadowBlurKey to 22, GlobalShadowOpacityKey to 65)
        assertEquals(listOf("Shadow: 22 px · 65%"), chips(recipe))
    }

    private fun chips(recipe: Preferences): List<String> {
        var result = emptyList<String>()
        compose.setContent { result = globalStyleChips(recipe) }
        compose.waitForIdle()
        return result
    }
}
