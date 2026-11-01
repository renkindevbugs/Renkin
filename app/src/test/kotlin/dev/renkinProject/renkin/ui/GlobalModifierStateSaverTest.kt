package dev.renkinProject.renkin.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import dev.renkinProject.renkin.data.FallbackSource
import dev.renkinProject.renkin.data.SOURCE_DEFAULT
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.data.Source
import dev.renkinProject.renkin.icon.creator.ColorizerMode
import dev.renkinProject.renkin.icon.creator.ColorizerStyle
import dev.renkinProject.renkin.icon.creator.GradientType
import dev.renkinProject.renkin.icon.creator.IconShape
import dev.renkinProject.renkin.icon.creator.OutlineMode
import dev.renkinProject.renkin.icon.creator.ModifierRecipeCodec
import dev.renkinProject.renkin.icon.creator.ColorFill
import dev.renkinProject.renkin.icon.creator.encodeColorizerStyle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalModifierStateSaverTest {

    private val saveableScope = SaverScope { true }

    @Test
    fun saver_roundTripsWholeColorStyles() {
        val original = populatedState()

        val saved = with(GlobalModifierState.Saver) { saveableScope.save(original) }
        val restored = GlobalModifierState.Saver.restore(requireNotNull(saved))

        assertNotNull(restored)
        assertTrue(requireNotNull(restored).initialized)
        assertEquals(original.snapshot(), restored.snapshot())
    }

    @Test
    fun baselineSaver_roundTripsDirtyCheckSnapshot() {
        val original = populatedState().snapshot()
        val baseline = mutableStateOf<GlobalModifierSnapshot?>(original)

        val saved = with(GlobalModifierState.BaselineSaver) { saveableScope.save(baseline) }
        val restored = GlobalModifierState.BaselineSaver.restore(requireNotNull(saved))

        assertEquals(original, restored?.value)
    }

    @Test
    fun legacyKeyedStylesRestoreWithTheSameBaselineNormalization() {
        val original = populatedState()
        val conflicting = original.modifiers.effect.colorizer.copy(flat = true, lighten = true)
        val saved = arrayListOf<Any>(
            "primarySource", Source.ICON_PACK.ordinal, "primaryIconPack", "primary.pack",
            "secondarySource", Source.ICON_PACK.ordinal, "secondaryIconPack", "secondary.pack",
            "fallbackSource", FallbackSource.SECONDARY.ordinal,
            "shape", IconShape.COOKIE.ordinal, "shapeCrop", false, "shapeScalePercent", 125,
            "shapeStyle", encodeColorizerStyle(requireNotNull(original.shape.fill?.style)),
            "iconScalePercent", 80, "outlineAdd", true, "outlineWidth", 9,
            "outlineStyle", encodeColorizerStyle(requireNotNull(original.outline.fill.style)),
            "colorize", true, "colorizerStyle", encodeColorizerStyle(conflicting),
            "applyGenerated", false, "applyExisting", true, "applyCustom", true, "includeEmpty", true
        )
        val state = requireNotNull(GlobalModifierState.Saver.restore(saved))
        val baseline = requireNotNull(GlobalModifierState.BaselineSaver.restore(saved))
        assertEquals(original.snapshot(), state.snapshot())
        assertEquals(state.snapshot(), baseline.value)
        assertFalse(state.modifiers.effect.colorizer.flat)
        assertFalse(state.modifiers.effect.colorizer.lighten)
    }

    @Test
    fun everyLegacyPositionalLengthRestoresWithoutCreatingFalseDirtyState() {
        for (length in listOf(17, 20, 21, 25, 27, 32)) {
            val saved = legacyValues().take(length).joinToString("|")
            val state = requireNotNull(GlobalModifierState.Saver.restore(saved))
            val baseline = requireNotNull(GlobalModifierState.BaselineSaver.restore(saved))
            assertEquals(state.snapshot(), baseline.value)
            assertEquals(IconShape.COOKIE, state.shape.shape)
            assertEquals(0.8f, state.modifiers.transform.scale)
            assertEquals(Color.Magenta.toArgb(), state.shape.fill!!.color)
            assertTrue(state.modifiers.effect.colorizer.monochrome)
            assertFalse(state.modifiers.effect.colorizer.flat)
        }
    }

    @Test
    fun shadowSurvivesSavedStateWithItsWholeConfiguration() {
        val original = populatedState().apply {
            updateShadow(shadow.copy(
                enabled = true, blur = 1f, distance = 12f, angle = 200f, allDirections = true, opacity = 0.65f,
                style = ColorizerStyle(firstColor = Color.Red.toArgb())
            ))
        }

        val saved = with(GlobalModifierState.Saver) { saveableScope.save(original) }
        val restored = requireNotNull(GlobalModifierState.Saver.restore(requireNotNull(saved)))

        assertEquals(original.snapshot(), restored.snapshot())
        assertTrue(restored.shadow.enabled)
        assertEquals(0.65f, restored.shadow.opacity)
        assertEquals(Color.Red.toArgb(), restored.shadow.style.firstColor)
    }

    @Test
    fun stateSavedBeforeShadowExistedRestoresTheDisabledDefaultWithoutFalseDirtyState() {
        val saved = legacyValues().joinToString("|")
        val state = requireNotNull(GlobalModifierState.Saver.restore(saved))
        val baseline = requireNotNull(GlobalModifierState.BaselineSaver.restore(saved))

        assertEquals(state.snapshot(), baseline.value)
        assertFalse(state.shadow.enabled)
        assertFalse(state.shadow.isVisible)
    }

    @Test
    fun partialRecipeRestoresConfiguredDefaultsAndPreservesFlatFillColor() {
        val original = populatedState().modifiers
        val partial = original.copy(shape = null,
            outline = original.outline!!.copy(fill = ColorFill(Color.Cyan.toArgb())))
        val saved = arrayListOf<Any>("modifiers", ModifierRecipeCodec.encode(partial))
        val state = requireNotNull(GlobalModifierState.Saver.restore(saved))
        val baseline = requireNotNull(GlobalModifierState.BaselineSaver.restore(saved))
        assertFalse(state.shape.isEnabled)
        assertEquals(Color.White.toArgb(), state.shape.fill!!.color)
        assertEquals(ColorizerStyle(firstColor = Color.Cyan.toArgb()), state.outline.fill.style)
        assertEquals(original.effect, state.modifiers.effect)
        assertEquals(original.transform, state.modifiers.transform)
        assertEquals(state.snapshot(), baseline.value)
    }

    @Test
    fun directRestoreRepairsMissingGroupsWithoutReplacingSourceOrEffect() {
        val snapshot = populatedState().snapshot()
        val state = GlobalModifierState().apply {
            restore(snapshot.copy(modifiers = snapshot.modifiers.copy(shape = null, outline = null)))
        }
        assertFalse(state.shape.isEnabled)
        assertFalse(state.outline.isEnabled)
        assertEquals(snapshot.primaryIconPack, state.primaryIconPack)
        assertEquals(snapshot.modifiers.effect, state.modifiers.effect)
        assertEquals(snapshot.modifiers.transform, state.modifiers.transform)
    }

    @Test
    fun disablingGroupsKeepsTheirConfigurationThroughSavedState() {
        val original = populatedState().apply {
            updateShape { copy(shape = IconShape.NONE) }
            updateOutline { copy(mode = OutlineMode.NONE) }
            updateEffect { copy(imageEdit = ImageEdit.NONE) }
        }
        val saved = requireNotNull(with(GlobalModifierState.Saver) { saveableScope.save(original) })
        val restored = requireNotNull(GlobalModifierState.Saver.restore(saved))
        assertEquals(original.snapshot(), restored.snapshot())
        assertEquals(original.shape.fill, restored.shape.fill)
        assertEquals(original.outline.fill, restored.outline.fill)
        assertEquals(original.modifiers.effect.colorizer, restored.modifiers.effect.colorizer)
    }

    @Test
    fun damagedRecipeDoesNotFallBackToPartialLegacyData() {
        for (saved in listOf(arrayListOf<Any>("modifiers", "damaged", "shapeStyle", "unused"),
            arrayListOf<Any>("modifiers", 42), "damaged legacy data")) {
            assertNull(GlobalModifierState.Saver.restore(saved))
            assertNull(requireNotNull(GlobalModifierState.BaselineSaver.restore(saved)).value)
        }
    }

    @Test
    fun saver_restoresLegacyPositionalSnapshot() {
        val legacy = legacyValues().joinToString("|")

        val restored = GlobalModifierState.Saver.restore(legacy)

        assertNotNull(restored)
        restored!!
        assertEquals(SOURCE_DEFAULT, restored.primarySource)
        assertEquals("", restored.primaryIconPack)
        assertEquals(SOURCE_DEFAULT, restored.secondarySource)
        assertEquals("", restored.secondaryIconPack)
        assertEquals(FallbackSource.NONE, restored.fallbackSource)
        assertEquals(IconShape.COOKIE, restored.shape.shape)
        assertEquals(1.25f, restored.snapshot().modifiers.shape!!.scale)
        assertEquals(0.8f, restored.snapshot().modifiers.transform.scale)
        assertEquals(Color.Magenta.toArgb(), requireNotNull(restored.shape.fill?.style).firstColor)
        assertEquals(ColorizerMode.GRADIENT, requireNotNull(restored.shape.fill?.style).mode)
        assertEquals(GradientType.RADIAL, requireNotNull(restored.shape.fill?.style).gradientType)
        assertEquals(listOf(Color.Green.toArgb()), requireNotNull(restored.shape.fill?.style).gradientStops)
        assertEquals(listOf(0f, 0.6f), requireNotNull(restored.shape.fill?.style).gradientPositions)
        assertEquals(210f, requireNotNull(restored.shape.fill?.style).gradientAngle)
        assertEquals(Color.Cyan.toArgb(), requireNotNull(restored.outline.fill.style).firstColor)
        assertEquals(listOf(Color.Blue.toArgb()), requireNotNull(restored.outline.fill.style).gradientStops)
        assertEquals(listOf(0f, 0.4f), requireNotNull(restored.outline.fill.style).gradientPositions)
        assertEquals(Color.Yellow.toArgb(), restored.modifiers.effect.colorizer.firstColor)
        assertEquals(listOf(Color.Red.toArgb()), restored.modifiers.effect.colorizer.gradientStops)
        assertEquals(listOf(0f, 0.7f), restored.modifiers.effect.colorizer.gradientPositions)
        assertFalse(restored.modifiers.effect.colorizer.flat)
        assertTrue(restored.modifiers.effect.colorizer.monochrome)
        assertTrue(restored.modifiers.effect.colorizer.inverse)
    }

    private fun legacyValues() = listOf(
            IconShape.COOKIE.ordinal,
            false,
            125,
            Color.Magenta.toArgb(),
            80,
            true,
            9,
            Color.Cyan.toArgb(),
            true,
            Color.Yellow.toArgb(),
            true,
            false,
            true,
            true,
            false,
            true,
            true,
            ColorizerMode.GRADIENT.ordinal,
            Color.Red.toArgb(),
            137,
            GradientType.RADIAL.ordinal,
            ColorizerMode.GRADIENT.ordinal,
            GradientType.LINEAR.ordinal,
            Color.Blue.toArgb(),
            45,
            "0.0,0.7",
            "0.0,0.4",
            ColorizerMode.GRADIENT.ordinal,
            GradientType.RADIAL.ordinal,
            Color.Green.toArgb(),
            "0.0,0.6",
            210
    )

    private fun populatedState() = GlobalModifierState().apply {
        primarySource = Source.ICON_PACK
        primaryIconPack = "primary.pack"
        secondarySource = Source.ICON_PACK
        secondaryIconPack = "secondary.pack"
        fallbackSource = FallbackSource.SECONDARY
        updateShape { copy(shape = IconShape.COOKIE, crop = false, scale = 1.25f) }
        val shapeStyle = ColorizerStyle(
            mode = ColorizerMode.GRADIENT,
            gradientType = GradientType.RADIAL,
            firstColor = Color.Magenta.toArgb(),
            gradientStops = listOf(Color.Green.toArgb()),
            gradientPositions = listOf(0f, 0.6f),
            gradientAngle = 210f
        )
        updateShape { copy(fill = shapeStyle.asFill()) }
        updateTransform { copy(scale = 0.8f) }
        updateOutline { copy(mode = OutlineMode.ADD, width = 9f) }
        val outlineStyle = ColorizerStyle(
            mode = ColorizerMode.GRADIENT,
            firstColor = Color.Cyan.toArgb(),
            gradientStops = listOf(Color.Blue.toArgb()),
            gradientPositions = listOf(0f, 0.4f),
            gradientAngle = 45f
        )
        updateOutline { copy(fill = outlineStyle.asFill()) }
        val colorizerStyle = ColorizerStyle(
            mode = ColorizerMode.GRADIENT,
            gradientType = GradientType.RADIAL,
            firstColor = Color.Yellow.toArgb(),
            gradientStops = listOf(Color.Red.toArgb()),
            gradientPositions = listOf(0f, 0.7f),
            gradientAngle = 137f,
            flat = true,
            monochrome = true,
            inverse = true
        )
        updateEffect { copy(imageEdit = ImageEdit.COLORIZE, colorizer = colorizerStyle) }
        applyGenerated = false
        applyExisting = true
        applyCustom = true
        includeEmpty = true
    }
}
