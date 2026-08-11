package dev.renkinProject.renkin.icon.creator

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import dev.alembiconsProject.imagetracer.ImageTracer
import dev.alembiconsProject.tgCannyEdgeCompose.CannyEdgeDetector
import dev.alembiconsProject.tgCannyEdgeCompose.DetectionOptions
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.drawable.BitmapIconDrawable
import dev.renkinProject.renkin.drawable.IconPackDrawable
import dev.renkinProject.renkin.drawable.ImageVectorDrawable
import dev.renkinProject.renkin.drawable.InsetIconDrawable
import dev.renkinProject.renkin.drawable.toImageVectorDrawable
import dev.renkinProject.renkin.extension.changeBackgroundColor
import dev.renkinProject.renkin.extension.emptyLike
import dev.renkinProject.renkin.extension.removeBackground
import dev.renkinProject.renkin.vector.VectorEditor.Companion.editPaths
import dev.renkinProject.renkin.vector.VectorEditor.Companion.editPathColors
import dev.renkinProject.renkin.vector.VectorEditor.Companion.resizeAndCenter
import dev.renkinProject.renkin.vector.VectorEditor.Companion.setReferenceColorPaths

/** Applies source-independent image edits, followed by geometry/output adjustments. */
internal class IconImageEditPipeline(
    private val resources: Resources,
    private val options: GenerationOptions,
    private val adjustments: IconAdjustmentPipeline = IconAdjustmentPipeline(resources, options)
) {
    private val colorizeMode
        get() = if (options.colorizeFlat && !options.colorizeMonochrome) {
            PorterDuff.Mode.SRC_IN
        } else {
            PorterDuff.Mode.MULTIPLY
        }

    private val colorizeColor
        get() = if (options.colorizeInverse) invertArgb(options.color) else options.color

    fun apply(icon: IconPackDrawable, imageEdit: ImageEdit): IconPackDrawable =
        adjustments.apply(applyEdit(icon, imageEdit))

    fun applyPrimary(icon: IconPackDrawable): IconPackDrawable =
        apply(icon, options.primaryImageEdit)

    internal fun applyEdit(icon: IconPackDrawable, imageEdit: ImageEdit): IconPackDrawable {
        if (imageEdit == ImageEdit.NONE) return icon

        if (imageEdit == ImageEdit.COLORIZE &&
            options.colorizerMode == ColorizerMode.SINGLE_COLOR &&
            !options.colorizeMonochrome
        ) {
            adjustments.modifierVector(icon)?.let { vector ->
                vector.root.setReferenceColorPaths(SolidColor(Color(colorizeColor)))
                vector.tintColor = Color.Unspecified
                return vector
            }
        }

        if (icon is ImageVectorDrawable) {
            val copy = ImageVectorDrawable(icon.toImageVector())
            return when (imageEdit) {
                ImageEdit.NONE -> icon
                ImageEdit.COLORIZE_SEGMENTS -> colorize(copy.toBitmap(), colorizeMode)
                ImageEdit.COLORIZE -> {
                    if (options.colorizerMode == ColorizerMode.GRADIENT ||
                        options.colorizeMonochrome
                    ) {
                        colorize(copy.toBitmap(), colorizeMode)
                    } else {
                        copy.root.setReferenceColorPaths(SolidColor(Color(colorizeColor)))
                        copy.tintColor = Color.Unspecified
                        copy
                    }
                }
                ImageEdit.PATH -> trace(copy.toBitmap())
                ImageEdit.EDGE -> detectEdges(copy.toBitmap())
                ImageEdit.REMOVE_BACKGROUND -> removeBackground(copy.toBitmap())
            }
        }

        val modified = applyToBitmap(icon.toBitmap(), imageEdit, colorizeMode)
        return preserveBitmapPresentation(icon, modified)
    }

    internal fun applyToBitmap(
        bitmap: Bitmap,
        imageEdit: ImageEdit,
        mode: PorterDuff.Mode
    ): IconPackDrawable = when (imageEdit) {
        ImageEdit.NONE -> BitmapIconDrawable(resources, bitmap)
        ImageEdit.PATH -> trace(bitmap)
        ImageEdit.EDGE -> detectEdges(bitmap)
        ImageEdit.COLORIZE, ImageEdit.COLORIZE_SEGMENTS -> colorize(bitmap, mode)
        ImageEdit.REMOVE_BACKGROUND -> removeBackground(bitmap)
    }

    internal fun colorize(bitmap: Bitmap, mode: PorterDuff.Mode): IconPackDrawable {
        if (options.colorizeLayers.isNotEmpty()) {
            return BitmapIconDrawable(
                resources,
                addBackground(applySegmentLayers(bitmap, options.colorizeLayers))
            )
        }
        if (options.colorizerMode == ColorizerMode.GRADIENT) {
            return BitmapIconDrawable(resources, colorizeWithGradient(bitmap))
        }
        if (options.colorizeMonochrome) {
            return defaultBitmap(monochromeBitmap(bitmap, options.colorizeInverse))
        }
        return BitmapIconDrawable(resources, colorizeBitmap(bitmap, mode))
    }

    internal fun colorizeVector(vector: ImageVectorDrawable): ImageVectorDrawable {
        vector.root.editPathColors(
            SolidColor(Color.Unspecified),
            SolidColor(Color(colorizeColor))
        )
        vector.tintColor = Color.Unspecified
        return vector
    }

    internal fun trace(bitmap: Bitmap): IconPackDrawable {
        val imageVector = ImageTracer.imageToVector(
            bitmap.asImageBitmap(),
            ImageTracer.TracingOptions().apply { numberOfColors = 8 }
        )
        val vector = imageVector.toImageVectorDrawable()
        recolorVectorStrokes(vector)
        vector.resizeAndCenter()
        return if (options.themed) vectorToInset(vector) else vector
    }

    private fun detectEdges(bitmap: Bitmap): IconPackDrawable {
        val detector = CannyEdgeDetector()
        detector.process(
            bitmap.asImageBitmap(),
            options.color,
            DetectionOptions().apply {
                lowThreshold = options.edgeLowThreshold
                highThreshold = options.edgeHighThreshold
                gaussianKernelRadius = options.edgeGaussianRadius
                contrastNormalized = options.edgeContrastNormalized
            }
        )
        return if (options.themed) {
            bitmapToInset(detector.edgesImage)
        } else {
            BitmapIconDrawable(resources, detector.edgesImage)
        }
    }

    private fun removeBackground(bitmap: Bitmap): IconPackDrawable {
        val cleaned = if (options.bgRemovalTargets.isNotEmpty()) {
            removeSegmentColors(bitmap, options.bgRemovalTargets, options.bgRemovalTolerance)
        } else {
            bitmap.removeBackground(options.bgRemovalTolerance)
        }
        // Hand strokes come last: they are corrections to whatever the colour match decided, and
        // restoring reads from the untouched artwork rather than from the cleaned result.
        return defaultBitmap(
            cleaned.applyBackgroundBrush(
                original = bitmap,
                operations = options.backgroundBrushOperations
            )
        )
    }

    private fun colorizeBitmap(icon: Bitmap, mode: PorterDuff.Mode): Bitmap {
        val coloredIcon = icon.emptyLike()
        val paint = Paint().apply {
            colorFilter = PorterDuffColorFilter(options.color, mode)
        }
        val canvas = Canvas(coloredIcon)
        if (options.themed) {
            canvas.scale(0.5f, 0.5f, icon.width * 0.5f, icon.height * 0.5f)
        }
        canvas.drawBitmap(icon, 0f, 0f, paint)
        val result = addBackground(coloredIcon)
        return if (options.colorizeInverse) invertBitmapColors(result) else result
    }

    private fun colorizeWithGradient(icon: Bitmap): Bitmap {
        val centerX = icon.width / 2f
        val centerY = icon.height / 2f
        val base = if (options.colorizeMonochrome) {
            monochromeBitmap(icon, options.colorizeInverse)
        } else {
            icon
        }
        val gradient = buildColorizerShader(
            listOf(options.color) + options.colorizerGradientColors,
            options.colorizerGradientType,
            options.colorizerGradientAngle,
            icon.width,
            icon.height,
            options.colorizerGradientPositions
        )
        val coloredIcon = icon.emptyLike()
        val canvas = Canvas(coloredIcon)
        val solidFill = options.colorizeFlat && !options.colorizeMonochrome
        val drawMask = {
            val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                if (solidFill) xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            }
            if (options.themed) {
                canvas.save()
                canvas.scale(0.5f, 0.5f, centerX, centerY)
                canvas.drawBitmap(base, 0f, 0f, maskPaint)
                canvas.restore()
            } else {
                canvas.drawBitmap(base, 0f, 0f, maskPaint)
            }
        }
        val drawGradient = {
            canvas.drawRect(
                0f,
                0f,
                icon.width.toFloat(),
                icon.height.toFloat(),
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = gradient
                    if (!solidFill) xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
                }
            )
        }
        if (solidFill) {
            drawGradient()
            drawMask()
        } else {
            drawMask()
            drawGradient()
        }
        val tinted = if (options.colorizeInverse && !options.colorizeMonochrome) {
            invertBitmapColors(coloredIcon)
        } else {
            coloredIcon
        }
        return addBackground(tinted)
    }

    private fun addBackground(image: Bitmap): Bitmap {
        if (!options.themed) return image
        val shader = options.backgroundShader(image.width, image.height)
            ?: return image.changeBackgroundColor(options.bgColor)
        val result = image.emptyLike()
        Canvas(result).apply {
            drawRect(
                0f,
                0f,
                image.width.toFloat(),
                image.height.toFloat(),
                Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader }
            )
            drawBitmap(image, 0f, 0f, null)
        }
        image.recycle()
        return result
    }

    private fun defaultBitmap(bitmap: Bitmap): IconPackDrawable =
        if (options.themed) bitmapToInset(bitmap) else BitmapIconDrawable(resources, bitmap)

    private fun preserveBitmapPresentation(
        source: IconPackDrawable,
        modified: IconPackDrawable
    ): IconPackDrawable {
        val bitmapSource = source as? BitmapIconDrawable ?: return modified
        if (!bitmapSource.isAdaptiveIcon() && bitmapSource.previewScale == 1f) return modified
        return BitmapIconDrawable(
            resources,
            modified.toBitmap(),
            exportAsAdaptiveIcon = bitmapSource.isAdaptiveIcon(),
            previewScale = bitmapSource.previewScale
        )
    }

    private fun recolorVectorStrokes(vector: ImageVectorDrawable) {
        val stroke = vector.viewportHeight / 48
        vector.root.editPaths(
            stroke,
            SolidColor(Color.Unspecified),
            SolidColor(Color(options.color))
        )
        vector.tintColor = Color.Unspecified
    }

    private fun vectorToInset(vector: ImageVectorDrawable, scale: Float = 0.25f): InsetIconDrawable {
        val x = vector.viewportWidth * scale
        val y = vector.viewportHeight * scale
        return InsetIconDrawable(
            vector,
            Rect(x.toInt(), y.toInt(), x.toInt(), y.toInt()),
            RectF(scale, scale, scale, scale)
        )
    }

    private fun bitmapToInset(bitmap: Bitmap, scale: Float = 0.25f): InsetIconDrawable {
        val x = bitmap.width * scale
        val y = bitmap.height * scale
        return InsetIconDrawable(
            BitmapIconDrawable(resources, bitmap),
            Rect(x.toInt(), y.toInt(), x.toInt(), y.toInt()),
            RectF(scale, scale, scale, scale)
        )
    }
}
