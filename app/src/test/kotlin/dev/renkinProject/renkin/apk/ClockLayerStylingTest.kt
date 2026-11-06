package dev.renkinProject.renkin.apk

import android.app.Application
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.data.Source
import dev.renkinProject.renkin.icon.creator.ColorizerStyle
import dev.renkinProject.renkin.icon.creator.EffectModifier
import dev.renkinProject.renkin.icon.creator.GenerationOptions
import dev.renkinProject.renkin.icon.creator.IconExportOptions
import dev.renkinProject.renkin.icon.creator.IconShape
import dev.renkinProject.renkin.icon.creator.IconSourceOptions
import dev.renkinProject.renkin.icon.creator.ModifierStack
import dev.renkinProject.renkin.icon.creator.ShapeModifier
import dev.renkinProject.renkin.icon.creator.TransformModifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class ClockLayerStylingTest {

    private fun options(
        imageEdit: ImageEdit = ImageEdit.NONE,
        transform: TransformModifier = TransformModifier(),
        shape: ShapeModifier? = null,
        themed: Boolean = false
    ) = GenerationOptions(
        source = IconSourceOptions(Source.ICON_PACK),
        modifiers = ModifierStack(
            EffectModifier(imageEdit, ColorizerStyle(firstColor = 0xFFFF6600.toInt())),
            transform = transform,
            shape = shape
        ),
        export = IconExportOptions(themed = themed),
        override = true
    )

    @Test
    fun colorizeScaleAndPosition_keepTheClockLayersApart() {
        assertTrue(options(ImageEdit.COLORIZE).keepsClockLayers())
        assertTrue(options(transform = TransformModifier(scale = 0.85f, offsetX = 0.1f)).keepsClockLayers())
    }

    @Test
    fun flatteningModifiers_turnTheClockIntoAStillImage() {
        assertFalse(options(shape = ShapeModifier(IconShape.CIRCLE, crop = true, scale = 1f)).keepsClockLayers())
        assertFalse(options(ImageEdit.PATH).keepsClockLayers())
        assertFalse(options(ImageEdit.EDGE).keepsClockLayers())
        assertFalse(options(ImageEdit.COLORIZE, themed = true).keepsClockLayers())
    }

    @Test
    fun handPivot_movesWithTheLayerTransform() {
        val centred = DynamicClockExporter.Rotation(300f, 5300f, 0.5f, 0.5f)
        val offCentre = DynamicClockExporter.Rotation(300f, 5300f, 0.8f, 0.5f)

        val scaled = centred.movedBy(TransformModifier(scale = 0.5f))
        assertEquals(0.5f, scaled.pivotX, 0.0001f)
        assertEquals(0.5f, scaled.pivotY, 0.0001f)

        // Adaptive layers shift by the offset divided by the 108/72 adaptive scale.
        val shifted = centred.movedBy(TransformModifier(offsetX = 0.15f, offsetY = -0.15f))
        assertEquals(0.6f, shifted.pivotX, 0.0001f)
        assertEquals(0.4f, shifted.pivotY, 0.0001f)

        assertEquals(0.65f, offCentre.movedBy(TransformModifier(scale = 0.5f)).pivotX, 0.0001f)
        assertEquals(300f, shifted.fromDegrees)
        assertEquals(5300f, shifted.toDegrees)
    }

    @Test
    fun rotationDegrees_followTheLevelLinearly() {
        val minuteHand = DynamicClockExporter.Rotation(60f, 60060f, 0.5f, 0.5f)

        assertEquals(60f, minuteHand.degreesAt(0), 0.001f)
        assertEquals(30060f, minuteHand.degreesAt(5000), 0.001f)
    }
}
