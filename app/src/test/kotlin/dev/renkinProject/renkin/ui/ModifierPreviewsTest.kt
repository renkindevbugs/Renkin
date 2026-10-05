package dev.renkinProject.renkin.ui

import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.data.Source
import dev.renkinProject.renkin.data.TextType
import dev.renkinProject.renkin.icon.creator.ColorizerMode
import dev.renkinProject.renkin.icon.creator.ColorizerStyle
import dev.renkinProject.renkin.icon.creator.EffectModifier
import dev.renkinProject.renkin.icon.creator.GenerationOptions
import dev.renkinProject.renkin.icon.creator.withExport
import dev.renkinProject.renkin.icon.creator.IconExportOptions
import dev.renkinProject.renkin.icon.creator.IconSourceOptions
import dev.renkinProject.renkin.icon.creator.GradientType
import dev.renkinProject.renkin.icon.creator.ModifierStack
import dev.renkinProject.renkin.icon.creator.ColorFill
import dev.renkinProject.renkin.icon.creator.IconShape
import dev.renkinProject.renkin.icon.creator.ShapeModifier
import org.junit.Assert.assertEquals
import org.junit.Test

class ModifierPreviewsTest {

    @Test
    fun withShapeStyle_changesOnlyShapeBackground() {
        val original = options()
        val style = ColorizerStyle(
            mode = ColorizerMode.GRADIENT,
            gradientType = GradientType.RADIAL,
            firstColor = 0xFF123456.toInt(),
            gradientStops = listOf(0xFFABCDEF.toInt())
        )

        val updated = original.withShapeStyle(style)

        assertEquals(style.firstColor, updated.export.background.color)
        assertEquals(style, updated.export.background.style)
        assertEquals(original.withExport { copy(background = ColorFill(style.firstColor, style)) }, updated)
    }

    @Test
    fun explicitShapeStylePreviewPreservesThePresentationBackground() {
        val base = options()
        val original = base.copy(modifiers = base.modifiers.copy(
            shape = ShapeModifier(IconShape.CIRCLE, false, 1f, ColorFill(0xFF00FF00.toInt()))
        ))
        val style = ColorizerStyle(firstColor = 0xFFFF0000.toInt())
        val updated = original.withShapeStyle(style)
        assertEquals(original.export.background.color, updated.export.background.color)
        assertEquals(original.export.background.style, updated.export.background.style)
        assertEquals(ColorFill(style.firstColor, style), updated.modifiers.shape?.fill)
        assertEquals(original.modifiers.effect, updated.modifiers.effect)
    }

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
            background = ColorFill(0xFFFFFFFF.toInt())
        ),
        override = true,
        modifiers = ModifierStack(
            effect = EffectModifier(imageEdit = ImageEdit.NONE, colorizer = ColorizerStyle(firstColor = 0xFF000000.toInt())),
        )
    )
}
