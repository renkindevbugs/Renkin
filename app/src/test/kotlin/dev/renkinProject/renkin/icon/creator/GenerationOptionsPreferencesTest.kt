package dev.renkinProject.renkin.icon.creator

import android.app.Application

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import dev.renkinProject.renkin.extension.toHexString
import androidx.datastore.preferences.core.preferencesOf
import androidx.test.core.app.ApplicationProvider
import dev.renkinProject.renkin.data.AppIconFullIconKey
import dev.renkinProject.renkin.data.AppIconInvertMonochromeKey
import dev.renkinProject.renkin.data.AppIconMaterialYouBackgroundKey
import dev.renkinProject.renkin.data.AppIconMaterialYouForegroundKey
import dev.renkinProject.renkin.data.AppIconMaterialYouSchemeKey
import dev.renkinProject.renkin.data.AppIconVariantKey
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
import dev.renkinProject.renkin.data.GlobalShadowAllDirectionsKey
import dev.renkinProject.renkin.data.GlobalShadowAngleKey
import dev.renkinProject.renkin.data.GlobalShadowBlurKey
import dev.renkinProject.renkin.data.GlobalShadowColorKey
import dev.renkinProject.renkin.data.GlobalShadowDistanceKey
import dev.renkinProject.renkin.data.GlobalShadowKey
import dev.renkinProject.renkin.data.GlobalShadowOpacityKey
import dev.renkinProject.renkin.data.OutlineAddKey
import dev.renkinProject.renkin.data.SecondaryImageEditKey
import dev.renkinProject.renkin.data.SecondarySourceKey
import dev.renkinProject.renkin.data.IncludeVectorKey
import dev.renkinProject.renkin.data.MonochromeKey
import dev.renkinProject.renkin.data.PrimaryImageEditKey
import dev.renkinProject.renkin.data.PrimarySourceKey
import dev.renkinProject.renkin.data.Source
import dev.renkinProject.renkin.data.TextFontKey
import dev.renkinProject.renkin.data.getDefaultIconColor
import dev.renkinProject.renkin.ui.GlobalModifierState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
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

        assertFalse(options.export.vector)
        assertFalse(options.export.materialYou)
        assertEquals(ApplicationIconVariant.DEFAULT, options.source.applicationIconVariant)
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

        assertTrue(options.export.vector)
        assertTrue(options.export.materialYou)
        assertEquals(ApplicationIconVariant.MATERIAL_YOU, options.source.applicationIconVariant)
    }

    @Test
    fun staleFontPath_usesBundledDefault() {
        val prefs = preferencesOf(TextFontKey to "/font/that/does/not/exist.ttf")

        assertEquals("", GenerationOptions.fromPreferences(prefs, context).source.textFontPath)
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

        assertEquals(ImageEdit.PATH, source.modifiers.effect.imageEdit)
        assertEquals(ImageEdit.EDGE, source.source.secondaryImageEdit)
        assertEquals(OutlineMode.NONE, source.modifiers.outline?.mode ?: OutlineMode.NONE)
        assertEquals(ImageEdit.COLORIZE, global.modifiers.effect.imageEdit)
        assertEquals(0.8f, global.modifiers.transform.scale)
        assertEquals(IconShape.CIRCLE, global.modifiers.shape?.shape)
        assertEquals(OutlineMode.ADD, global.modifiers.outline?.mode)
        assertTrue(global.hasVisibleModifierEffect())
    }

    @Test
    fun globalShadow_isADisabledSoftPresetWhenNothingIsStored() {
        val shadow = requireNotNull(globalModifierOptions(preferencesOf()).modifiers.shadow)

        assertFalse(shadow.enabled)
        assertFalse(shadow.isVisible)
        assertEquals(14f, shadow.blur)
        assertEquals(7f, shadow.distance)
        assertEquals(135f, shadow.angle)
        assertEquals(0.32f, shadow.opacity)
        assertEquals(Color.Black.toArgb(), shadow.style.firstColor)
    }

    @Test
    fun globalShadow_readsWholeUnitsAndClampsToTheEditorRanges() {
        val prefs = preferencesOf(
            GlobalShadowKey to true,
            GlobalShadowBlurKey to 99,
            GlobalShadowDistanceKey to 12,
            GlobalShadowAngleKey to 200,
            GlobalShadowAllDirectionsKey to true,
            GlobalShadowOpacityKey to 150,
            GlobalShadowColorKey to Color.Red.toHexString()
        )

        val global = globalModifierOptions(prefs)
        val shadow = requireNotNull(global.modifiers.shadow)

        assertEquals(SHADOW_BLUR_MAX, shadow.blur)
        assertEquals(12f, shadow.distance)
        assertEquals(200f, shadow.angle)
        assertTrue(shadow.allDirections)
        assertEquals(SHADOW_OPACITY_MAX, shadow.opacity)
        assertEquals(Color.Red.toArgb(), shadow.style.firstColor)
        assertTrue(global.hasVisibleShadow())
        assertTrue(global.hasVisibleModifierEffect())
    }

    @Test
    fun globalShadow_draftWritesThePreferencesARefreshReadsBack() {
        val draft = GlobalModifierState().apply {
            seedFrom(preferencesOf())
            updateShadow(shadow.copy(
                enabled = true, blur = 1.4f, distance = 11.6f, angle = 135f, opacity = 0.654f,
                style = ColorizerStyle(firstColor = Color.Blue.toArgb())
            ))
        }

        val reread = requireNotNull(globalModifierOptions(draft.overlay(preferencesOf())).modifiers.shadow)

        assertTrue(reread.enabled)
        assertEquals(Color.Blue.toArgb(), reread.style.firstColor)
        assertEquals(1f, reread.blur)
        assertEquals(12f, reread.distance)
        assertEquals(0.65f, reread.opacity)
        assertEquals(reread, draft.toModifierOptions().modifiers.shadow)
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

        assertEquals(ColorizerMode.GRADIENT, global.modifiers.effect.colorizer.mode)
        assertEquals(GradientType.RADIAL, global.modifiers.effect.colorizer.gradientType)
        assertEquals(0xFF336699.toInt(), global.modifiers.effect.colorizer.firstColor)
        assertEquals(listOf(0xFFCC5500.toInt()), global.modifiers.effect.colorizer.gradientStops)
    }
    @Test fun secondaryPathRetainsItsOwnEditAndLegacyMonochromeSelection() {
        val prefs = preferencesOf(
            PrimarySourceKey to Source.ICON_PACK.ordinal,
            PrimaryImageEditKey to ImageEdit.COLORIZE.ordinal,
            SecondarySourceKey to Source.APPLICATION_ICON.ordinal,
            SecondaryImageEditKey to ImageEdit.PATH.ordinal,
            IncludeVectorKey to true,
            MonochromeKey to true
        )
        val options = GenerationOptions.fromPreferences(prefs, context)
        assertEquals(ImageEdit.COLORIZE, options.modifiers.effect.imageEdit)
        assertEquals(ImageEdit.PATH, options.source.secondaryImageEdit)
        assertTrue(options.export.vector)
        assertTrue(options.export.materialYou)
        assertEquals(ApplicationIconVariant.MATERIAL_YOU, options.source.applicationIconVariant)
    }


    @Test
    fun appIconStyleAppliesWhenTheAppsOwnIconIsThePrimarySource() {
        val prefs = preferencesOf(
            PrimarySourceKey to Source.APPLICATION_ICON.ordinal,
            AppIconVariantKey to ApplicationIconVariant.MONOCHROME.ordinal,
            AppIconInvertMonochromeKey to true,
            AppIconFullIconKey to true
        )

        val source = GenerationOptions.fromPreferences(prefs, context).source

        assertEquals(ApplicationIconVariant.MONOCHROME, source.applicationIconVariant)
        assertTrue(source.invertMonochrome)
        assertTrue(source.useFullApplicationIcon)
    }

    @Test
    fun appIconStyleIsIgnoredForIconPackSources() {
        val prefs = preferencesOf(
            PrimarySourceKey to Source.ICON_PACK.ordinal,
            AppIconVariantKey to ApplicationIconVariant.MATERIAL_YOU.ordinal,
            AppIconFullIconKey to true
        )

        val source = GenerationOptions.fromPreferences(prefs, context).source

        assertEquals(ApplicationIconVariant.DEFAULT, source.applicationIconVariant)
        assertFalse(source.useFullApplicationIcon)
    }

    @Test
    fun pathTracingKeepsItsOwnMaterialYouSwitchOverTheAppIconStyle() {
        val prefs = preferencesOf(
            PrimarySourceKey to Source.APPLICATION_ICON.ordinal,
            PrimaryImageEditKey to ImageEdit.PATH.ordinal,
            AppIconVariantKey to ApplicationIconVariant.MONOCHROME.ordinal
        )

        val source = GenerationOptions.fromPreferences(prefs, context).source

        assertEquals(ApplicationIconVariant.DEFAULT, source.applicationIconVariant)
    }

    @Test
    fun materialYouPaintsWithTheSelectedWallpaperScheme() {
        val (foreground, background) = materialYouSchemes(context)[1]
        val prefs = preferencesOf(
            PrimarySourceKey to Source.APPLICATION_ICON.ordinal,
            AppIconVariantKey to ApplicationIconVariant.MATERIAL_YOU.ordinal,
            AppIconMaterialYouSchemeKey to 1
        )

        val options = GenerationOptions.fromPreferences(prefs, context)

        assertEquals(ApplicationIconVariant.MATERIAL_YOU, options.source.applicationIconVariant)
        assertEquals(foreground.toArgb(), options.modifiers.effect.colorizer.firstColor)
        assertEquals(background.toArgb(), options.export.background.color)
    }

    @Test
    fun materialYouPastTheLastSchemeUsesTheCustomColours() {
        val custom = materialYouSchemes(context).size
        val prefs = preferencesOf(
            PrimarySourceKey to Source.APPLICATION_ICON.ordinal,
            AppIconVariantKey to ApplicationIconVariant.MATERIAL_YOU.ordinal,
            AppIconMaterialYouSchemeKey to custom,
            AppIconMaterialYouForegroundKey to Color.Red.toHexString(),
            AppIconMaterialYouBackgroundKey to Color.Blue.toHexString()
        )

        val options = GenerationOptions.fromPreferences(prefs, context)

        assertEquals(Color.Red.toArgb(), options.modifiers.effect.colorizer.firstColor)
        assertEquals(Color.Blue.toArgb(), options.export.background.color)
        assertEquals(Color.Red.toArgb(), options.export.foregroundStyle?.firstColor)
    }

    @Test
    fun nonMaterialYouVariantsKeepTheRegularColours() {
        val prefs = preferencesOf(
            PrimarySourceKey to Source.APPLICATION_ICON.ordinal,
            AppIconVariantKey to ApplicationIconVariant.MONOCHROME.ordinal,
            AppIconMaterialYouSchemeKey to 1
        )

        val options = GenerationOptions.fromPreferences(prefs, context)

        assertEquals(null, options.export.foregroundStyle)
        assertEquals(prefs.getDefaultIconColor(context).toArgb(), options.modifiers.effect.colorizer.firstColor)
    }
}
