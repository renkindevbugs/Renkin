package dev.renkinProject.renkin.icon.creator

import android.app.Application
import dev.renkinProject.renkin.data.ImageEdit
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class ModifierRangesTest {

    // Stored recipes, presets and drafts were written within these bounds; changing them changes
    // what existing data restores to.
    @Test fun rangesStayWhereStoredDataExpectsThem() {
        assertEquals(0.5f..1.5f, MODIFIER_SCALE_RANGE)
        assertEquals(0.5f..2f, MATERIAL_YOU_STROKE_SCALE_RANGE)
    }

    private fun importedScales(scale: Float): Pair<Float, Float?> {
        val stack = ModifierStack(
            EffectModifier(ImageEdit.NONE, ColorizerStyle(firstColor = 0xff000000.toInt())),
            transform = TransformModifier(scale = scale),
            shape = ShapeModifier(IconShape.CIRCLE, crop = false, scale = scale, fill = ColorFill(0xffffffff.toInt()))
        )
        val decoded = requireNotNull(ModifierRecipeCodec.decode(ModifierRecipeCodec.encode(stack)))
        return decoded.transform.scale to decoded.shape?.scale
    }

    @Test fun importedRecipesKeepBoundaryScalesAndClampBeyondThem() {
        assertEquals(0.5f to 0.5f, importedScales(MODIFIER_SCALE_RANGE.start))
        assertEquals(1.5f to 1.5f, importedScales(MODIFIER_SCALE_RANGE.endInclusive))
        assertEquals(1.5f to 1.5f, importedScales(1.75f))
        assertEquals(0.5f to 0.5f, importedScales(0.25f))
    }

    @Test fun materialYouStrokeScaleClampsToItsRange() {
        assertEquals(0.5f, effectiveMaterialYouPackStrokeScale(0.1f))
        assertEquals(0.5f, effectiveMaterialYouPackStrokeScale(0.5f))
        assertEquals(2f, effectiveMaterialYouPackStrokeScale(2f))
        assertEquals(2f, effectiveMaterialYouPackStrokeScale(3f))
    }
}
