package dev.renkinProject.renkin.ui

import android.app.Application
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import dev.renkinProject.renkin.data.GlobalColorizeKey
import dev.renkinProject.renkin.data.GlobalColorizeFlatKey
import dev.renkinProject.renkin.data.GlobalColorizeLightenKey
import dev.renkinProject.renkin.data.GlobalColorizeMonochromeKey
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.data.GlobalIconScaleKey
import dev.renkinProject.renkin.data.GlobalShapeScaleKey
import dev.renkinProject.renkin.data.OutlineWidthKey
import dev.renkinProject.renkin.data.PrimaryIconPackKey
import dev.renkinProject.renkin.data.Source
import dev.renkinProject.renkin.icon.creator.ColorizerMode
import dev.renkinProject.renkin.icon.creator.ColorizerStyle
import dev.renkinProject.renkin.icon.creator.IconShape
import dev.renkinProject.renkin.icon.creator.OutlineMode
import dev.renkinProject.renkin.icon.creator.globalModifierOptions
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class GlobalModifierStateTest {
    private val gradient = ColorizerStyle(
        mode = ColorizerMode.GRADIENT, firstColor = 0x80123456.toInt(),
        gradientStops = listOf(0xFFABCDEF.toInt()), gradientPositions = listOf(0f, 0.7f),
        gradientAngle = 137.6f, inverse = true
    )

    @Test fun previewAndSavedPreferencesRenderAtIdenticalPersistedPrecision() {
        val state = GlobalModifierState().apply {
            updateTransform { copy(scale = 0.824f) }
            updateShape { copy(shape = IconShape.COOKIE, crop = false, scale = 1.256f, fill = gradient.asFill()) }
            updateOutline { copy(mode = OutlineMode.ADD, width = 9.4f, fill = gradient.asFill()) }
            updateEffect { copy(imageEdit = ImageEdit.COLORIZE, colorizer = gradient) }
        }
        val persisted = state.overlay(emptyPreferences())
        assertEquals(82, persisted[GlobalIconScaleKey])
        assertEquals(126, persisted[GlobalShapeScaleKey])
        assertEquals(9, persisted[OutlineWidthKey])
        val preview = state.toModifierOptions()
        val refreshed = globalModifierOptions(persisted)
        assertEquals(refreshed, preview)
        assertEquals(0.82f, preview.modifiers.transform.scale)
        assertEquals(1.26f, preview.modifiers.shape!!.scale)
        assertEquals(9f, preview.modifiers.outline!!.width)
        assertEquals(138f, preview.modifiers.effect.colorizer.gradientAngle)
        assertEquals(138f, preview.export.background.style!!.gradientAngle)
        assertNull(preview.modifiers.shape!!.fill)
    }

    @Test fun allFlagCombinationsHaveOneEffectiveModeAtEveryGlobalBoundary() {
        for (flat in listOf(false, true)) for (lighten in listOf(false, true)) for (mono in listOf(false, true)) {
            val prefs = preferencesOf(GlobalColorizeKey to true, GlobalColorizeFlatKey to flat,
                GlobalColorizeLightenKey to lighten, GlobalColorizeMonochromeKey to mono)
            val state = GlobalModifierState().apply { seedFrom(prefs) }
            state.updateEffect { copy(colorizer = gradient.copy(flat = flat, lighten = lighten, monochrome = mono)) }
            val expected = gradient.copy(flat = flat && !mono, lighten = lighten && !flat && !mono, monochrome = mono)
            assertEquals(expected, state.modifiers.effect.colorizer)
            assertEquals(expected.copy(gradientAngle = 138f), state.snapshot().modifiers.effect.colorizer)
            val stored = state.overlay(emptyPreferences())
            assertEquals(expected.flat, stored[GlobalColorizeFlatKey])
            assertEquals(expected.lighten, stored[GlobalColorizeLightenKey])
            assertEquals(state.toModifierOptions(), globalModifierOptions(stored))
            val reopened = GlobalModifierState().apply { seedFrom(stored) }
            assertEquals(state.snapshot(), reopened.snapshot())
        }
    }

    @Test fun ignoredFlagsDoNotCreateDirtyStateAndDoNotOverwriteOtherStyleFields() {
        val state = GlobalModifierState().apply { updateEffect { copy(colorizer = gradient.copy(flat = true)) } }
        val baseline = state.snapshot()
        state.updateEffect { copy(colorizer = colorizer.copy(lighten = true)) }
        assertEquals(baseline, state.snapshot())
        assertEquals(gradient.copy(flat = true), state.modifiers.effect.colorizer)
    }

    @Test fun inactivePlateStyleSurvivesSeedingAndOverlayDoesNotMutateItsInput() {
        val state = GlobalModifierState().apply {
            primarySource = Source.ICON_PACK
            primaryIconPack = "staged.pack"
            updateShape { copy(fill = gradient.copy(inverse = false).asFill()) }
            updateOutline { copy(fill = gradient.copy(inverse = false).asFill()) }
        }
        val original = preferencesOf(PrimaryIconPackKey to "original.pack")
        val stored = state.overlay(original)
        assertEquals("original.pack", original[PrimaryIconPackKey])
        assertEquals("staged.pack", stored[PrimaryIconPackKey])
        val reopened = GlobalModifierState().apply { seedFrom(stored) }
        assertEquals(state.snapshot(), reopened.snapshot())
        assertFalse(reopened.hasAnyEffect)
        assertEquals(android.graphics.Color.TRANSPARENT, reopened.toModifierOptions().export.background.color)
    }
}
