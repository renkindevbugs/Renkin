package dev.renkinProject.renkin.icon.creator

import android.app.Application
import android.graphics.Bitmap
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.data.Source
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class StoredIconModifierRecipeTest {
    private fun options() = GenerationOptions(
        source = IconSourceOptions(Source.ICON_PACK),
        modifiers = ModifierStack(
            EffectModifier(ImageEdit.NONE, ColorizerStyle(firstColor = 0xff000000.toInt())),
            shape = ShapeModifier(IconShape.CIRCLE, crop = false, scale = 1f)
        ),
        export = IconExportOptions(background = ColorFill(0xff123456.toInt())),
        override = true
    )

    @Test fun capturesInheritedPlateFillWithoutDependingOnLaterExportSettings() {
        val options = options()
        val encoded = StoredIconModifierRecipe.fromRenderedOptions(options)
        val decoded = ModifierRecipeCodec.decode(checkNotNull(encoded))!!
        assertEquals(options.export.background, decoded.shape!!.fill)
        assertEquals(encoded, StoredIconModifierRecipe.normalize(encoded))
    }

    @Test fun unsupportedBrushGeometryAndLossyRangesHaveNoMetadata() {
        val options = options()
        val mask = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        assertNull(StoredIconModifierRecipe.fromRenderedOptions(options.copy(
            modifiers = options.modifiers.copy(outline = OutlineModifier(OutlineMode.ADD, .1f, ColorFill(0), mask))
        )))
        assertNull(StoredIconModifierRecipe.fromRenderedOptions(options.copy(
            modifiers = options.modifiers.copy(effect = options.modifiers.effect.copy(
                backgroundRemoval = BackgroundRemovalModifier(brushOperations = listOf(BackgroundBrushOperation(BrushAction.ERASE, mask)))
            ))
        )))
        assertNull(StoredIconModifierRecipe.fromRenderedOptions(options.copy(
            modifiers = options.modifiers.copy(transform = TransformModifier(scale = 100f))
        )))
    }

    @Test fun invalidAndOversizedImportedRecipesAreDiscarded() {
        assertNull(StoredIconModifierRecipe.normalize(null))
        assertNull(StoredIconModifierRecipe.normalize(""))
        assertNull(StoredIconModifierRecipe.normalize("recipe.version=999"))
        assertNull(StoredIconModifierRecipe.normalize("recipe.version=1\nunknown=" + "x".repeat(1_000_000)))
    }
}
