package dev.renkinProject.renkin.ui

import android.app.Application
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.data.Source
import dev.renkinProject.renkin.data.TextType
import dev.renkinProject.renkin.icon.creator.ColorFill
import dev.renkinProject.renkin.icon.creator.ColorizerMode
import dev.renkinProject.renkin.icon.creator.ColorizerStyle
import dev.renkinProject.renkin.icon.creator.EffectModifier
import dev.renkinProject.renkin.icon.creator.GenerationOptions
import dev.renkinProject.renkin.icon.creator.MaterialYouPackModifier
import dev.renkinProject.renkin.icon.creator.withModifiers
import dev.renkinProject.renkin.icon.creator.IconExportOptions
import dev.renkinProject.renkin.icon.creator.IconSourceOptions
import dev.renkinProject.renkin.icon.creator.GradientType
import dev.renkinProject.renkin.icon.creator.IconShape
import dev.renkinProject.renkin.icon.creator.ModifierStack
import dev.renkinProject.renkin.icon.creator.OutlineMode
import dev.renkinProject.renkin.icon.creator.OutlineModifier
import dev.renkinProject.renkin.icon.creator.SHADOW_ANGLE_DEFAULT
import dev.renkinProject.renkin.icon.creator.SHADOW_BLUR_DEFAULT
import dev.renkinProject.renkin.icon.creator.SHADOW_DISTANCE_DEFAULT
import dev.renkinProject.renkin.icon.creator.SHADOW_OPACITY_DEFAULT
import dev.renkinProject.renkin.icon.creator.SegmentLayer
import dev.renkinProject.renkin.icon.creator.ShadowModifier
import dev.renkinProject.renkin.icon.creator.ShapeModifier
import dev.renkinProject.renkin.icon.creator.TransformModifier
import dev.renkinProject.renkin.icon.creator.encodeColorizerStyle
import dev.renkinProject.renkin.icon.creator.withColorizerStyle
import dev.renkinProject.renkin.icon.creator.withModifierPreset
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class ModifierColorStyleTest {
    private val scope = SaverScope { true }
    private val gradient = ColorizerStyle(
        mode = ColorizerMode.GRADIENT,
        gradientType = GradientType.RADIAL,
        firstColor = 0x80123456.toInt(),
        gradientStops = listOf(0xFFABCDEF.toInt(), 0x40224466),
        gradientPositions = listOf(0f, 0.35f, 0.9f),
        gradientAngle = 247f,
        flat = true,
        lighten = true,
        monochrome = true,
        inverse = true
    )

    private fun options() = GenerationOptions(
        source = IconSourceOptions(
            primarySource = Source.APPLICATION_ICON,
            primaryTextType = TextType.FULL_NAME,
            primaryIconPack = ""
        ),
        export = IconExportOptions(
            vector = false,
            materialYou = false,
            themed = false,
            background = ColorFill(Color.White.toArgb())
        ),
        override = true,
        modifiers = ModifierStack(
            effect = EffectModifier(
                imageEdit = ImageEdit.REMOVE_BACKGROUND,
                colorizer = ColorizerStyle(firstColor = Color.Red.toArgb()),
                segments = listOf(SegmentLayer(listOf(1, 2), style = gradient))
            ),
            transform = TransformModifier(scale = 0.8f),
            shape = ShapeModifier(shape = IconShape.COOKIE, crop = true, scale = 1f),
            outline = OutlineModifier(mode = OutlineMode.ADD, width = 6f, fill = ColorFill(color = android.graphics.Color.BLACK, style = null)),
            shadow = ShadowModifier(
                blur = SHADOW_BLUR_DEFAULT,
                distance = SHADOW_DISTANCE_DEFAULT,
                angle = SHADOW_ANGLE_DEFAULT,
                allDirections = false,
                style = ColorizerStyle(firstColor = android.graphics.Color.BLACK),
                opacity = SHADOW_OPACITY_DEFAULT
            ),
        )
    )

    private fun roundTrip(state: AdjustmentState): AdjustmentState {
        val saved = with(AdjustmentState.Saver) { scope.save(state) }
        return requireNotNull(AdjustmentState.Saver.restore(requireNotNull(saved)))
    }

    @Test fun saverPreservesEveryFieldOfFourIndependentStylesAndSegmentLayers() {
        val state = AdjustmentState().apply {
            colorizerStyle = gradient
            shapeColorStyle = gradient.copy(firstColor = Color.Blue.toArgb(), gradientAngle = 95f)
            outlineColorStyle = gradient.copy(firstColor = Color.Green.toArgb(), gradientType = GradientType.LINEAR)
            shadowColorStyle = gradient.copy(firstColor = Color.Yellow.toArgb(), gradientPositions = listOf(0f, 0.6f, 1f))
            colorizeLayers = listOf(SegmentLayer(listOf(3, 5), 0.24f, gradient))
            shadowEnabled = true
            shadowBlur = 12f
            shadowDistance = 8f
            shadowAngle = 70f
            shadowAllDirections = true
            shadowOpacity = 0.4f
        }
        val restored = roundTrip(state)
        assertEquals(state.colorizerStyle, restored.colorizerStyle)
        assertEquals(state.shapeColorStyle, restored.shapeColorStyle)
        assertEquals(state.outlineColorStyle, restored.outlineColorStyle)
        assertEquals(state.shadowColorStyle, restored.shadowColorStyle)
        assertEquals(state.colorizeLayers, restored.colorizeLayers)
        assertTrue(restored.shadowEnabled)
        assertEquals(state.shadowBlur, restored.shadowBlur)
        assertEquals(state.shadowDistance, restored.shadowDistance)
        assertEquals(state.shadowAngle, restored.shadowAngle)
        assertEquals(state.shadowAllDirections, restored.shadowAllDirections)
        assertEquals(state.shadowOpacity, restored.shadowOpacity)
    }

    @Test fun oldKeyedStylesRestoreWithoutLosingGradientPositionsOrFlags() {
        val saved = arrayListOf<Any>(
            "colorizerMode", 1, "colorizerGradientType", 1,
            "colorizerGradientColor", Color.Blue.toArgb(), "colorizerGradientPositions", listOf(0f, 0.7f),
            "colorizerGradientAngle", 180f, "colorizeFlat", true, "colorizeLighten", true,
            "colorizeMonochrome", true, "colorizeInverse", true
        )
        for ((prefix, color) in listOf("shape" to Color.Red, "outline" to Color.Green, "shadow" to Color.Yellow)) {
            saved.addAll(listOf(
                "${prefix}Color", color.toArgb(), "${prefix}ColorizerMode", 1,
                "${prefix}GradientType", 1, "${prefix}GradientColors", gradient.gradientStops,
                "${prefix}GradientPositions", gradient.gradientPositions, "${prefix}GradientAngle", 247f
            ))
        }
        val restored = requireNotNull(AdjustmentState.Saver.restore(saved))
        assertEquals(gradient.copy(
            firstColor = Color.White.toArgb(), gradientStops = listOf(Color.Blue.toArgb()),
            gradientPositions = listOf(0f, 0.7f), gradientAngle = 180f
        ), restored.colorizerStyle)
        val fill = gradient.copy(flat = false, lighten = false, monochrome = false, inverse = false)
        assertEquals(fill.copy(firstColor = Color.Red.toArgb()), restored.shapeColorStyle)
        assertEquals(fill.copy(firstColor = Color.Green.toArgb()), restored.outlineColorStyle)
        assertEquals(fill.copy(firstColor = Color.Yellow.toArgb()), restored.shadowColorStyle)
    }

    @Test fun newStyleTakesPrecedenceOverLegacyFieldsAndMalformedStylesFallBack() {
        val saved = arrayListOf<Any>(
            "shapeStyle", encodeColorizerStyle(gradient), "shapeColor", Color.Blue.toArgb(),
            "outlineStyle", "damaged", "outlineColor", Color.Green.toArgb(),
            "outlineColorizerMode", 1000, "outlineGradientType", -1,
            "shadowStyle", "damaged"
        )
        val restored = requireNotNull(AdjustmentState.Saver.restore(saved))
        assertEquals(gradient, restored.shapeColorStyle)
        assertEquals(ColorizerStyle(firstColor = Color.Green.toArgb()), restored.outlineColorStyle)
        assertEquals(ColorizerStyle(firstColor = Color.Black.toArgb()), restored.shadowColorStyle)
    }

    @Test fun changingOneControlPreservesTheRestOfItsStyleAndOtherGroups() {
        val state = AdjustmentState().apply {
            colorizerStyle = gradient
            shapeColorStyle = gradient
            outlineColorStyle = gradient
            shadowColorStyle = gradient
        }
        state.shapeColor = Color.Blue
        state.colorizeLighten = false
        assertEquals(gradient.copy(firstColor = Color.Blue.toArgb()), state.shapeColorStyle)
        assertEquals(gradient.copy(lighten = false), state.colorizerStyle)
        assertEquals(gradient, state.outlineColorStyle)
        assertEquals(gradient, state.shadowColorStyle)
    }

    @Test fun sharedGenerationAdapterPreservesSelectedEditLayersAndGeometry() {
        val original = options()
        val updated = original.withColorizerStyle(gradient)
        assertEquals(gradient, updated.modifiers.effect.colorizer)
        assertEquals(original.modifiers.effect.imageEdit, updated.modifiers.effect.imageEdit)
        assertEquals(original.modifiers.effect.segments, updated.modifiers.effect.segments)
        assertEquals(original.modifiers.shape?.shape, updated.modifiers.shape?.shape)
        assertEquals(original.modifiers.transform.scale, updated.modifiers.transform.scale)
        assertEquals(original.modifiers.outline?.mode, updated.modifiers.outline?.mode)
        assertEquals(original.modifiers.shadow?.enabled, updated.modifiers.shadow?.enabled)
        assertEquals(original.export.background.color, updated.export.background.color)
    }

    @Test fun colorSheetPreviewForcesColorizeAndClearsSegmentsWithoutChangingOriginal() {
        val base = options()
        val original = base.copy(modifiers = base.modifiers.copy(
            effect = base.modifiers.effect.copy(imageEdit = ImageEdit.COLORIZE_SEGMENTS)
        ))
        val updated = original.asColorizePreview(gradient)
        assertEquals(ImageEdit.COLORIZE, updated.modifiers.effect.imageEdit)
        assertTrue(updated.modifiers.effect.segments.isEmpty())
        assertEquals(gradient, updated.modifiers.effect.colorizer)
        assertEquals(original.modifiers.shape?.shape, updated.modifiers.shape?.shape)
        assertEquals(ImageEdit.COLORIZE_SEGMENTS, original.modifiers.effect.imageEdit)
        assertFalse(original.modifiers.effect.segments.isEmpty())
    }

    @Test fun editorMappingUsesHoistedFirstColorAndKeepsSegmentsOnlyForTheirEdit() {
        val state = AdjustmentState().apply {
            colorizerStyle = gradient
            colorizeLayers = options().modifiers.effect.segments
        }
        val original = options()
        val segmented = original.withModifierAdjustments(state, ImageEdit.COLORIZE_SEGMENTS, null)
        assertEquals(gradient.copy(firstColor = original.modifiers.effect.colorizer.firstColor), segmented.modifiers.effect.colorizer)
        assertEquals(state.colorizeLayers, segmented.modifiers.effect.segments)
        assertTrue(original.withModifierAdjustments(state, ImageEdit.COLORIZE, null).modifiers.effect.segments.isEmpty())
    }

    @Test fun presetRoundTripKeepsAllStylesAndDoesNotReplaceIconSpecificGeometry() {
        val source = AdjustmentState().apply {
            colorizerStyle = gradient
            shapeColorStyle = gradient.copy(gradientAngle = 15f)
            outlineColorStyle = gradient.copy(gradientAngle = 35f)
            shadowColorStyle = gradient.copy(gradientAngle = 65f)
            iconShape = IconShape.COOKIE
            outlineMode = OutlineMode.ADD
            shadowEnabled = true
        }
        val payload = captureModifierPreset(source, ImageEdit.COLORIZE, Color.Blue, ModifierPresetGroup.entries.toSet())
        val target = AdjustmentState().apply { iconOffsetX = 0.2f; bgRemovalTargets = listOf(5, 7) }
        val application = applyModifierPreset(payload, target)
        assertEquals(Color.Blue, application.iconColor)
        assertEquals(gradient.copy(firstColor = Color.Blue.toArgb()), target.colorizerStyle)
        assertEquals(source.shapeColorStyle, target.shapeColorStyle)
        assertEquals(source.outlineColorStyle, target.outlineColorStyle)
        assertEquals(source.shadowColorStyle, target.shadowColorStyle)
        assertEquals(0.2f, target.iconOffsetX)
        assertEquals(listOf(5, 7), target.bgRemovalTargets)
        val generated = options().withModifierPreset(payload)
        assertEquals(target.colorizerStyle, generated.modifiers.effect.colorizer)
        assertEquals(source.outlineColorStyle, generated.modifiers.outline?.fill?.style)
        assertEquals(source.shadowColorStyle, generated.modifiers.shadow?.style)
    }
    @Test fun editorAdjustmentsAndPortablePresetsRetainPackSpecificControls() {
        val pack = MaterialYouPackModifier(
            foreground = Color.Blue.toArgb(), background = Color.Yellow.toArgb(),
            selectedScheme = 2, strokeScale = 1.6f,
            customForeground = gradient, customBackground = gradient.copy(inverse = false)
        )
        val original = options().withModifiers { copy(materialYouPack = pack) }
        val state = AdjustmentState().apply {
            colorizerStyle = gradient
            iconShape = IconShape.CIRCLE
            shapeCrop = false
            shadowEnabled = true
        }
        val edited = original.withModifierAdjustments(state, ImageEdit.COLORIZE, null)
        assertSame(pack, edited.modifiers.materialYouPack)
        assertEquals(IconShape.CIRCLE, edited.modifiers.shape?.shape)
        assertTrue(edited.modifiers.shadow?.enabled == true)
        assertSame(original.source, edited.source)
        val payload = captureModifierPreset(state, ImageEdit.COLORIZE, Color.Blue, ModifierPresetGroup.entries.toSet())
        assertSame(pack, edited.withModifierPreset(payload).modifiers.materialYouPack)
        assertSame(pack, edited.asColorizePreview(gradient).modifiers.materialYouPack)
        assertSame(pack, edited.withShapeStyle(gradient).modifiers.materialYouPack)
    }

}
