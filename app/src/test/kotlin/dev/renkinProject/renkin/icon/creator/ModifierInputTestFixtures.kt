package dev.renkinProject.renkin.icon.creator

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.data.Source
import dev.renkinProject.renkin.drawable.IconPackDrawable

internal fun modifierInputTestOptions() = GenerationOptions(
    source = IconSourceOptions(primarySource = Source.ICON_PACK),
    modifiers = ModifierStack(
        effect = EffectModifier(ImageEdit.NONE, ColorizerStyle(firstColor = 0xff123456.toInt())),
        transform = TransformModifier(scale = 1.2f, offsetX = .1f, offsetY = -.1f),
        shape = ShapeModifier(IconShape.CIRCLE, crop = false, scale = 1f, fill = ColorFill(0xffabcdef.toInt()))
    ),
    export = IconExportOptions(background = ColorFill(0xffabcdef.toInt())),
    override = true
)

internal class ModifierInputTestIcon(private val payload: String = "retained-source") : IconPackDrawable() {
    var encodeCalls = 0
        private set
    @Composable override fun getPainter(): Painter = error("No painter needed")
    override fun toBitmap(): Bitmap = error("No bitmap needed")
    override fun toDbString(): String { encodeCalls++; return payload }
    override fun draw(canvas: Canvas) = Unit
    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("deprecated in Drawable")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}
