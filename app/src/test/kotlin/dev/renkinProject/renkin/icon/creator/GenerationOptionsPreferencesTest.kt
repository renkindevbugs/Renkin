package dev.renkinProject.renkin.icon.creator

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.datastore.preferences.core.preferencesOf
import androidx.test.core.app.ApplicationProvider
import dev.renkinProject.renkin.data.DarkMode
import dev.renkinProject.renkin.data.DarkModeKey
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.data.GlobalColorizeKey
import dev.renkinProject.renkin.data.GlobalColorizeColorKey
import dev.renkinProject.renkin.data.GlobalColorizerGradientColorKey
import dev.renkinProject.renkin.data.GlobalColorizerGradientTypeKey
import dev.renkinProject.renkin.data.GlobalColorizerModeKey
import dev.renkinProject.renkin.data.GlobalIconScaleKey
import dev.renkinProject.renkin.data.GlobalShapeKey
import dev.renkinProject.renkin.data.OutlineAddKey
import dev.renkinProject.renkin.data.SecondaryImageEditKey
import dev.renkinProject.renkin.data.IncludeVectorKey
import dev.renkinProject.renkin.data.MonochromeKey
import dev.renkinProject.renkin.data.PrimaryImageEditKey
import dev.renkinProject.renkin.data.PrimarySourceKey
import dev.renkinProject.renkin.data.Source
import dev.renkinProject.renkin.data.TextFontKey
import dev.renkinProject.renkin.data.getDefaultIconColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GenerationOptionsPreferencesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun hiddenPathOptions_doNotAffectGeneration() {
        val prefs = preferencesOf(
            PrimarySourceKey to Source.APPLICATION_ICON.ordinal,
            PrimaryImageEditKey to ImageEdit.NONE.ordinal,
            IncludeVectorKey to true,
            MonochromeKey to true
        )

        val options = GenerationOptions.fromPreferences(prefs, context)

        assertFalse(options.vector)
        assertFalse(options.materialYou)
        assertEquals(ApplicationIconVariant.DEFAULT, options.applicationIconVariant)
    }

    @Test
    fun pathOptions_remainEnabledWhenRelevant() {
        val prefs = preferencesOf(
            PrimarySourceKey to Source.APPLICATION_ICON.ordinal,
            PrimaryImageEditKey to ImageEdit.PATH.ordinal,
            IncludeVectorKey to true,
            MonochromeKey to true
        )

        val options = GenerationOptions.fromPreferences(prefs, context)

        assertTrue(options.vector)
        assertTrue(options.materialYou)
    }

    @Test
    fun staleFontPath_usesBundledDefault() {
        val prefs = preferencesOf(TextFontKey to "/font/that/does/not/exist.ttf")

        assertEquals("", GenerationOptions.fromPreferences(prefs, context).textFontPath)
        assertEquals("", FontCatalog.usablePathOrDefault(prefs[TextFontKey]!!))
    }

    @Test
    fun explicitTheme_controlsDefaultVectorColor() {
        val dark = preferencesOf(DarkModeKey to DarkMode.DARK.ordinal)
        val light = preferencesOf(DarkModeKey to DarkMode.LIGHT.ordinal)

        assertEquals(Color.White, dark.getDefaultIconColor(context))
        assertEquals(Color.Black, light.getDefaultIconColor(context))
    }

    @Test
    fun globalModifiers_areASeparateFinalLayer() {
        val prefs = preferencesOf(
            PrimaryImageEditKey to ImageEdit.PATH.ordinal,
            SecondaryImageEditKey to ImageEdit.EDGE.ordinal,
            GlobalColorizeKey to true,
            GlobalColorizeColorKey to "#FF336699",
            GlobalIconScaleKey to 80,
            GlobalShapeKey to IconShape.CIRCLE.ordinal,
            OutlineAddKey to true
        )

        val source = GenerationOptions.fromPreferences(prefs, context)
        val global = globalModifierOptions(prefs)

        assertEquals(ImageEdit.PATH, source.primaryImageEdit)
        assertEquals(ImageEdit.EDGE, source.secondaryImageEdit)
        assertEquals(OutlineMode.NONE, source.outlineMode)
        assertEquals(ImageEdit.COLORIZE, global.primaryImageEdit)
        assertEquals(0.8f, global.iconScale)
        assertEquals(IconShape.CIRCLE, global.iconShape)
        assertEquals(OutlineMode.ADD, global.outlineMode)
        assertTrue(global.hasVisibleModifierEffect())
    }

    @Test
    fun globalModifierStyle_readsLegacySecondGradientColor() {
        val prefs = preferencesOf(
            GlobalColorizerModeKey to ColorizerMode.GRADIENT.ordinal,
            GlobalColorizerGradientTypeKey to GradientType.RADIAL.ordinal,
            GlobalColorizeColorKey to "#FF336699",
            GlobalColorizerGradientColorKey to "#FFCC5500"
        )

        val global = globalModifierOptions(prefs)

        assertEquals(ColorizerMode.GRADIENT, global.colorizerMode)
        assertEquals(GradientType.RADIAL, global.colorizerGradientType)
        assertEquals(0xFF336699.toInt(), global.color)
        assertEquals(listOf(0xFFCC5500.toInt()), global.colorizerGradientColors)
    }
}
