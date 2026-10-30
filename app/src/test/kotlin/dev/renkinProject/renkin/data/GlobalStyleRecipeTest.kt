package dev.renkinProject.renkin.data

import android.app.Application
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.preferencesOf
import dev.renkinProject.renkin.extension.toNullableColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class GlobalStyleRecipeTest {

    @Test
    fun theRecipeKeepsOnlyTheGlobalScreenKeys() {
        val recipe = mutablePreferencesOf(
            GlobalShapeKey to 2,
            PrimaryIconPackKey to "pack.one",
            DarkModeKey to 1,
            IncludeVectorKey to true
        ).globalStyleRecipe()

        assertEquals(2, recipe[GlobalShapeKey])
        assertEquals("pack.one", recipe[PrimaryIconPackKey])
        assertNull(recipe[DarkModeKey])
        assertNull(recipe[IncludeVectorKey])
    }

    @Test
    fun anUnsetColourStaysUnsetInsteadOfBecomingAnUnreadableEmptyString() {
        val recipe = preferencesOf().globalStyleRecipe()

        assertFalse(GlobalShapeStyleKeys.firstColor in recipe.asMap())
        assertFalse(OutlineStyleKeys.firstColor in recipe.asMap())
        assertFalse(GlobalColorizerStyleKeys.firstColor in recipe.asMap())
    }

    @Test
    fun aRecipeOverwritesTheGlobalKeysAndRemovesColoursItLeavesUnset() {
        val current = preferencesOf(
            GlobalShapeKey to 3,
            OutlineStyleKeys.firstColor to "#ff112233",
            DarkModeKey to 1
        )

        val merged = current.withGlobalStyle(preferencesOf(GlobalShapeKey to 1))

        assertEquals(1, merged[GlobalShapeKey])
        assertNull(merged[OutlineStyleKeys.firstColor])
        assertEquals(1, merged[DarkModeKey])
    }

    @Test
    fun aRecipeSurvivesTheProfileStyleDocument() {
        val recipe = preferencesOf(
            GlobalShapeKey to 1,
            GlobalShapeCropKey to false,
            GlobalShapeStyleKeys.firstColor to "#ffffffff",
            OutlineAddKey to true,
            OutlineWidthKey to 6,
            GlobalApplyCustomKey to true,
            PrimaryIconPackKey to "pack.one"
        ).globalStyleRecipe()

        val decoded = ProfileStylePreferences.toPreferences(
            ProfileStyleCodec.decode(ProfileStyleCodec.encode(ProfileStylePreferences.capture(recipe)))
        ).globalStyleRecipe()

        assertEquals(recipe, decoded)
        assertTrue(decoded[OutlineAddKey] == true)
    }

    @Test
    fun anEmptyColourStringReadsAsNoColour() {
        assertNull("".toNullableColor())
        assertNull("not a colour".toNullableColor())
    }
}
