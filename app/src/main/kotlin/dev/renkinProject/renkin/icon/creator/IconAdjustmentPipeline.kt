package dev.renkinProject.renkin.icon.creator

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import dev.renkinProject.renkin.drawable.BitmapIconDrawable
import dev.renkinProject.renkin.drawable.ADAPTIVE_ICON_SCALE
import dev.renkinProject.renkin.drawable.AdaptiveIconPackDrawable
import dev.renkinProject.renkin.drawable.IconPackDrawable
import dev.renkinProject.renkin.drawable.ImageVectorDrawable
import dev.renkinProject.renkin.drawable.InsetIconDrawable
import dev.renkinProject.renkin.extension.newArgbBitmap
import dev.renkinProject.renkin.extension.scaleFromCenter

/**
 * Applies the source-independent geometry and output treatments from the Modifier tab.
 * It deliberately has no icon-pack or package-manager dependencies, so previews and future
 * callers can reuse this part of generation without constructing an [IconGenerator].
 */
internal class IconAdjustmentPipeline(
    private val resources: Resources,
    private val modifiers: ModifierStack
) {
    constructor(resources: Resources, options: GenerationOptions) : this(resources, options.modifiers)

    fun apply(icon: IconPackDrawable): IconPackDrawable {
        if (!modifiers.hasAdjustments) return icon
        val transform = modifiers.transform

        if (icon is AdaptiveIconPackDrawable && !modifiers.requiresRasterization) {
            val layerModifiers = modifiers.copy(transform = transform.copy(
                offsetX = transform.offsetX / ADAPTIVE_ICON_SCALE,
                offsetY = transform.offsetY / ADAPTIVE_ICON_SCALE
            ))
            val layerAdjustments = IconAdjustmentPipeline(resources, layerModifiers)
            fun transformLayer(bitmap: Bitmap): Bitmap = layerAdjustments.apply(BitmapIconDrawable(bitmap)).toBitmap()
            return icon.withForeground(transformLayer(icon.foreground), icon.monochrome?.let(::transformLayer))
        }

        val vectorAdjusted = modifierVector(icon)?.withModifierTransform(
            transform.scale,
            transform.offsetX,
            transform.offsetY
        )
        if (vectorAdjusted != null && !modifiers.requiresRasterization) return vectorAdjusted

        var bitmap = vectorAdjusted?.toModifierBitmap() ?: icon.toBitmap()
        if (bitmap.width <= 0 || bitmap.height <= 0) return icon

        val source = icon as? BitmapIconDrawable
        val previewScaleToBake = previewScaleToBakeForShape(icon, modifiers.flattensOutput)
        if (previewScaleToBake != 1f) {
            bitmap = bitmap.scaleFromCenter(previewScaleToBake)
        }

        if (vectorAdjusted == null && !transform.isIdentity) {
            val sourceBitmap = bitmap
            // Keep position and scale in one raster pass. Translating into an intermediate bitmap
            // clipped off-canvas artwork before scaling could bring it back into view.
            bitmap = newArgbBitmap(sourceBitmap.width, sourceBitmap.height) { canvas ->
                canvas.scale(
                    transform.scale,
                    transform.scale,
                    sourceBitmap.width / 2f,
                    sourceBitmap.height / 2f
                )
                canvas.drawBitmap(
                    sourceBitmap,
                    transform.offsetX * sourceBitmap.width,
                    transform.offsetY * sourceBitmap.height,
                    Paint(Paint.FILTER_BITMAP_FLAG)
                )
            }
        }
        modifiers.outline?.let { outline ->
            val preOutline = bitmap
            bitmap = IconOutline.apply(
                preOutline,
                outline.mode,
                outline.width * maxOf(preOutline.width, preOutline.height) / 256f,
                outline.fill.color,
                outline.fill.style
            )
            outline.eraseMask?.let { mask ->
                bitmap = IconOutline.eraseOutline(bitmap, preOutline, mask)
            }
        }
        modifiers.shape?.let { shape -> bitmap = applyShape(bitmap, shape) }
        modifiers.shadow?.takeIf { it.isVisible }?.let { shadow ->
            bitmap = IconShadow.apply(
                source = bitmap,
                blur = shadow.blur,
                distance = shadow.distance,
                angle = shadow.angle,
                allDirections = shadow.allDirections,
                style = shadow.style,
                opacity = shadow.opacity
            )
        }

        return BitmapIconDrawable(
            resources,
            bitmap,
            exportAsAdaptiveIcon = if (modifiers.flattensOutput) false else source?.isAdaptiveIcon() ?: false,
            previewScale = if (modifiers.flattensOutput) 1f else source?.previewScale ?: 1f
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

    private fun applyShape(source: Bitmap, shape: ShapeModifier): Bitmap {
        val size = maxOf(source.width, source.height, 256)
        val sizeF = size.toFloat()
        val path = IconShapes.path(shape.shape, sizeF * shape.scale)
            ?: return source
        path.offset(
            sizeF * (1f - shape.scale) / 2f,
            sizeF * (1f - shape.scale) / 2f
        )
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        val content = newArgbBitmap(size, size) { canvas ->
            if (!shape.crop) {
                paint.shader = shape.fill.shader(size, size)
                paint.color = shape.fill.color
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
