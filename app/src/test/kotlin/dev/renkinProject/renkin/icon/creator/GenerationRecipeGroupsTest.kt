package dev.renkinProject.renkin.icon.creator

import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.data.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationRecipeGroupsTest {
    private val pack = MaterialYouPackModifier(
        foreground = 0xFF123456.toInt(), background = 0xFF654321.toInt(),
        selectedScheme = 2, strokeScale = 1.4f,
        customForeground = ColorizerStyle(firstColor = 0xFFABCDEF.toInt()),
        customBackground = ColorizerStyle(firstColor = 0xFF102030.toInt())
    )
    private val original = GenerationOptions(
        source = IconSourceOptions(Source.ICON_PACK, primaryIconPack = "test.pack"),
        modifiers = ModifierStack(
            EffectModifier(ImageEdit.EDGE, ColorizerStyle(firstColor = 0xFF556677.toInt()),
                edges = EdgeModifier(1f, 3f, 2f, true)),
            transform = TransformModifier(offsetX = 0.2f),
            materialYouPack = pack
        ),
        export = IconExportOptions(vector = true, themed = true),
        override = false
    )

    @Test fun sourceUpdateKeepsTheEntireRenderRecipeAndReplacementPolicy() {
        val changed = original.withSource { copy(primarySource = Source.APPLICATION_NAME, textCustom = "R") }
        assertEquals(Source.APPLICATION_NAME, changed.source.primarySource)
        assertEquals("R", changed.source.textCustom)
        assertSame(original.modifiers, changed.modifiers)
        assertSame(original.export, changed.export)
        assertFalse(changed.override)
        assertEquals(Source.ICON_PACK, original.source.primarySource)
    }

    @Test fun pathMonochromeAndApplicationVariantCanChangeIndependently() {
        val path = original.withExport { copy(materialYou = true) }
        assertTrue(path.export.materialYou)
        assertSame(original.source, path.source)
        assertSame(original.modifiers, path.modifiers)
        assertEquals(ApplicationIconVariant.DEFAULT, path.source.applicationIconVariant)
        val variant = original.withSource { copy(applicationIconVariant = ApplicationIconVariant.MATERIAL_YOU) }
        assertFalse(variant.export.materialYou)
        assertSame(original.export, variant.export)
        assertSame(original.modifiers, variant.modifiers)
    }

    @Test fun effectUpdateKeepsGeometryPackControlsAndOtherEffectParameters() {
        val changed = original.withModifiers {
            withEffect { copy(imageEdit = ImageEdit.COLORIZE, colorizer = colorizer.copy(flat = true)) }
        }
        assertEquals(ImageEdit.COLORIZE, changed.modifiers.effect.imageEdit)
        assertTrue(changed.modifiers.effect.colorizer.flat)
        assertSame(original.modifiers.effect.edges, changed.modifiers.effect.edges)
        assertSame(original.modifiers.transform, changed.modifiers.transform)
        assertSame(pack, changed.modifiers.materialYouPack)
        assertSame(original.source, changed.source)
        assertSame(original.export, changed.export)
        assertEquals(ImageEdit.EDGE, original.modifiers.effect.imageEdit)
    }

    @Test fun packRestylingIsVisibleWithoutForcingGeometryRasterization() {
        val unchanged = MaterialYouPackModifier()
        val cases = listOf(
            unchanged to false,
            unchanged.copy(foreground = 0xFF123456.toInt()) to true,
            unchanged.copy(background = 0xFF654321.toInt()) to true,
            unchanged.copy(strokeScale = 1.2f) to true,
            unchanged.copy(selectedScheme = 2, customForeground = pack.customForeground) to false
        )
        for ((config, visible) in cases) {
            val stack = ModifierStack(EffectModifier(ImageEdit.NONE, ColorizerStyle(firstColor = 0)), materialYouPack = config)
            assertEquals(config.toString(), visible, stack.hasVisibleEffect)
            assertFalse(stack.hasAdjustments)
            assertFalse(stack.requiresRasterization)
            assertFalse(stack.flattensOutput)
        }
    }
}
