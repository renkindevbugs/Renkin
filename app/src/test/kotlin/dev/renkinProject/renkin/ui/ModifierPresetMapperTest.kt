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

    @Test
    fun theFirstOfferedNameIsNumberedOne() {
        assertEquals("Preset 1", defaultModifierPresetName(emptyList(), PREFIX))
    }

    @Test
    fun theOfferedNumberSkipsTheOnesAlreadyTaken() {
        val existing = listOf("Preset 1", "Preset 2")

        assertEquals("Preset 3", defaultModifierPresetName(existing, PREFIX))
    }

    @Test
    fun aDeletedNumberIsOfferedAgain() {
        // Saving three and deleting the middle one must reuse the gap rather than keep counting:
        // the number is a convenience, not an identifier.
        val existing = listOf("Preset 1", "Preset 3")

        assertEquals("Preset 2", defaultModifierPresetName(existing, PREFIX))
    }

    @Test
    fun namesOfTheUsersOwnMakingDoNotConsumeNumbers() {
        // Only the exact "<prefix> N" shape counts, so renaming a preset frees its number and
        // unrelated names never push the counter forward.
        val existing = listOf("Dark squircle", "Preset", "Presets 1", "Preset one", "Preset 1x")

        assertEquals("Preset 1", defaultModifierPresetName(existing, PREFIX))
    }

    @Test
    fun theNumberIsIndependentOfTheOrderTheLibraryIsIn() {
        val existing = listOf("Preset 3", "Preset 1", "Preset 2")

        assertEquals("Preset 4", defaultModifierPresetName(existing, PREFIX))
    }

    private companion object {
        const val PREFIX = "Preset"
    }
}
