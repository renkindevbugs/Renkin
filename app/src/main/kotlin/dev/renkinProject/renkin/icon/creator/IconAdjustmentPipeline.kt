package dev.renkinProject.renkin.icon.creator

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import dev.renkinProject.renkin.drawable.BitmapIconDrawable
import dev.renkinProject.renkin.drawable.IconPackDrawable
import dev.renkinProject.renkin.drawable.ImageVectorDrawable
import dev.renkinProject.renkin.drawable.InsetIconDrawable
import dev.renkinProject.renkin.extension.newArgbBitmap
import dev.renkinProject.renkin.extension.scaleFromCenter
import dev.renkinProject.renkin.extension.translated

/**
 * Applies the source-independent geometry and output treatments from the Modifier tab.
 * It deliberately has no icon-pack or package-manager dependencies, so previews and future
 * callers can reuse this part of generation without constructing an [IconGenerator].
 */
internal class IconAdjustmentPipeline(
    private val resources: Resources,
    private val options: GenerationOptions
) {
    fun apply(icon: IconPackDrawable): IconPackDrawable {
        val offset = options.iconOffsetX != 0f || options.iconOffsetY != 0f
        val shaped = options.iconShape != IconShape.NONE
        val outlined = options.outlineMode != OutlineMode.NONE
        if (!offset && options.iconScale == 1f && !shaped && !outlined) return icon

        val vectorAdjusted = modifierVector(icon)?.withModifierTransform(
            options.iconScale,
            options.iconOffsetX,
            options.iconOffsetY
        )
        if (vectorAdjusted != null && !shaped && !outlined) return vectorAdjusted

        var bitmap = vectorAdjusted?.toModifierBitmap() ?: icon.toBitmap()
        if (bitmap.width <= 0 || bitmap.height <= 0) return icon

        val source = icon as? BitmapIconDrawable
        val previewScaleToBake = previewScaleToBakeForShape(icon, shaped)
        if (previewScaleToBake != 1f) {
            bitmap = bitmap.scaleFromCenter(previewScaleToBake)
        }

        if (vectorAdjusted == null && offset) {
            bitmap = bitmap.translated(
                options.iconOffsetX * bitmap.width,
                options.iconOffsetY * bitmap.height
            )
        }
        if (vectorAdjusted == null && options.iconScale != 1f) {
            val sourceBitmap = bitmap
            bitmap = newArgbBitmap(sourceBitmap.width, sourceBitmap.height) { canvas ->
                canvas.scale(
                    options.iconScale,
                    options.iconScale,
                    sourceBitmap.width / 2f,
                    sourceBitmap.height / 2f
                )
                canvas.drawBitmap(
                    sourceBitmap,
                    0f,
                    0f,
                    Paint(Paint.FILTER_BITMAP_FLAG)
                )
            }
        }
        if (outlined) {
            val preOutline = bitmap
            bitmap = IconOutline.apply(
                preOutline,
                options.outlineMode,
                options.outlineWidth * maxOf(preOutline.width, preOutline.height) / 256f,
                options.outlineColor,
                options.outlineStyle
            )
            options.outlineEraseMask?.let { mask ->
                bitmap = IconOutline.eraseOutline(bitmap, preOutline, mask)
            }
        }
        if (shaped) {
            bitmap = applyShape(bitmap)
        }

        return BitmapIconDrawable(
            resources,
            bitmap,
            exportAsAdaptiveIcon = if (shaped) false else source?.isAdaptiveIcon() ?: false,
            previewScale = if (shaped) 1f else source?.previewScale ?: 1f
        )
    }

    internal fun modifierVector(icon: IconPackDrawable): ImageVectorDrawable? {
        return when (icon) {
            is ImageVectorDrawable -> icon.deepCopy()
            is InsetIconDrawable -> {
                val child = icon.drawable as? ImageVectorDrawable ?: return null
                val left: Float
                val top: Float
                val right: Float
                val bottom: Float
                if (icon.isFractionsNotEmpty) {
                    left = icon.fractions.left
                    top = icon.fractions.top
                    right = icon.fractions.right
                    bottom = icon.fractions.bottom
                } else {
                    left = icon.dimensions.left / child.viewportWidth
                    top = icon.dimensions.top / child.viewportHeight
                    right = icon.dimensions.right / child.viewportWidth
                    bottom = icon.dimensions.bottom / child.viewportHeight
                }
                child.withViewportInset(left, top, right, bottom)
            }
            else -> null
        }
    }

    private fun applyShape(source: Bitmap): Bitmap {
        val size = maxOf(source.width, source.height, 256)
        val sizeF = size.toFloat()
        val path = IconShapes.path(options.iconShape, sizeF * options.iconShapeScale)
            ?: return source
        path.offset(
            sizeF * (1f - options.iconShapeScale) / 2f,
            sizeF * (1f - options.iconShapeScale) / 2f
        )
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        val content = newArgbBitmap(size, size) { canvas ->
            if (!options.iconShapeCrop) {
                paint.shader = options.backgroundShader(size, size)
                paint.color = options.bgColor
                canvas.drawPath(path, paint)
                paint.shader = null
                paint.color = -0x1
            }
            canvas.drawBitmap(source, null, RectF(0f, 0f, sizeF, sizeF), paint)
        }

        return newArgbBitmap(size, size) { canvas ->
            val mask = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            canvas.drawPath(path, mask)
            mask.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            canvas.drawBitmap(content, 0f, 0f, mask)
        }
    }
}
