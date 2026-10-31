package dev.renkinProject.renkin.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import dev.renkinProject.renkin.apk.EditorGlobalLayer
import dev.renkinProject.renkin.data.GlobalIconScaleKey
import dev.renkinProject.renkin.drawable.BitmapIconDrawable
import dev.renkinProject.renkin.drawable.IconPackDrawable
import dev.renkinProject.renkin.icon.creator.globalModifierOptions
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class KeptGlobalPreviewTest {

    private val draft = icon()
    private val layer = layer(emptyPreferences())

    @Test
    fun `no result yet is pending`() {
        assertTrue(null.isPendingFor(draft, layer))
    }

    @Test
    fun `result for the same draft and layer is done`() {
        assertFalse(KeptGlobalPreview(draft, layer, icon()).isPendingFor(draft, layer))
    }

    @Test
    fun `result for an earlier draft is pending`() {
        assertTrue(KeptGlobalPreview(draft, layer, icon()).isPendingFor(icon(), layer))
    }

    @Test
    fun `a rebuilt layer with equal values is done`() {
        assertFalse(KeptGlobalPreview(draft, layer, icon()).isPendingFor(draft, layer(emptyPreferences())))
    }

    @Test
    fun `result for a different layer is pending`() {
        val scaled = layer(mutablePreferencesOf(GlobalIconScaleKey to 150))
        assertTrue(KeptGlobalPreview(draft, layer, icon()).isPendingFor(draft, scaled))
    }

    @Test
    fun `nothing is pending without a layer or a draft`() {
        assertFalse(null.isPendingFor(draft, null))
        assertFalse(null.isPendingFor(null, layer))
    }

    private fun icon(): IconPackDrawable =
        BitmapIconDrawable(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888))

    private fun layer(recipe: Preferences) = EditorGlobalLayer(recipe, globalModifierOptions(recipe))
}
