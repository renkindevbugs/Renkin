package dev.renkinProject.renkin.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.icon.creator.IconShape
import dev.renkinProject.renkin.icon.creator.ModifierPresetPayload
import dev.renkinProject.renkin.icon.creator.ModifierPresetShape
import org.junit.Assert.assertEquals
import org.junit.Test

class ModifierPresetMapperTest {

    @Test
    fun applyPartialPreset_preservesSettingsOwnedByTheCurrentIcon() {
        val adjustments = AdjustmentState().apply {
            iconOffsetX = 0.25f
            iconOffsetY = -0.2f
            bgRemovalTargets = listOf(0xFF112233.toInt())
            iconScale = 1.1f
        }
        val payload = ModifierPresetPayload(
            shape = ModifierPresetShape(
                shape = IconShape.PEBBLE,
                crop = true,
                scale = 0.75f,
                style = adjustments.shapeStyle()
            )
        )

        val application = applyModifierPreset(payload, adjustments)

        assertEquals(IconShape.PEBBLE, adjustments.iconShape)
        assertEquals(0.75f, adjustments.shapeScale)
        assertEquals(1.1f, adjustments.iconScale)
        assertEquals(0.25f, adjustments.iconOffsetX)
        assertEquals(-0.2f, adjustments.iconOffsetY)
        assertEquals(listOf(0xFF112233.toInt()), adjustments.bgRemovalTargets)
        assertEquals(null, application.imageEdit)
    }

    @Test
    fun captureSegmentsAsReusableColorize_withoutIconSpecificTargets() {
        val adjustments = AdjustmentState().apply {
            colorizeLayers = listOf()
            bgRemovalTargets = listOf(0xFFABCDEF.toInt())
        }

        val payload = captureModifierPreset(
            adjustments = adjustments,
            imageEdit = ImageEdit.COLORIZE_SEGMENTS,
            iconColor = Color.Red,
            groups = setOf(ModifierPresetGroup.EFFECT)
        )

        assertEquals(ImageEdit.COLORIZE, payload.effect?.imageEdit)
        assertEquals(Color.Red.toArgb(), payload.effect!!.colorizerStyle.firstColor)
    }
}
