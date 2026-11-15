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
import dev.renkinProject.renkin.packages.PackageVersion
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.drawable.ADAPTIVE_ICON_SCALE
import dev.renkinProject.renkin.drawable.BitmapIconDrawable
import dev.renkinProject.renkin.drawable.AdaptiveIconPackDrawable
import dev.renkinProject.renkin.drawable.MaterialYouPackEditState
import dev.renkinProject.renkin.drawable.IconPackDrawable
import dev.renkinProject.renkin.drawable.ImageVectorDrawable
import dev.renkinProject.renkin.drawable.InsetIconDrawable
import dev.renkinProject.renkin.drawable.toImageVectorDrawable
import dev.renkinProject.renkin.drawable.ThemedGlyphDrawable
import dev.renkinProject.renkin.extension.changeBackgroundColor
import dev.renkinProject.renkin.extension.emptyLike
import dev.renkinProject.renkin.extension.newArgbBitmap
import dev.renkinProject.renkin.extension.removeBackground
import dev.renkinProject.renkin.vector.VectorEditor.Companion.editPaths
import dev.renkinProject.renkin.vector.VectorEditor.Companion.editPathColors
import dev.renkinProject.renkin.vector.VectorEditor.Companion.resizeAndCenter
import dev.renkinProject.renkin.vector.VectorEditor.Companion.setReferenceColorPaths

/** Applies source-independent image edits, followed by geometry/output adjustments. */
internal class IconImageEditPipeline(
    private val resources: Resources,
    modifiers: ModifierStack,
    private val export: IconExportOptions,
    private val adjustments: IconAdjustmentPipeline = IconAdjustmentPipeline(resources, modifiers, export.background)
) {
    constructor(
        resources: Resources,
        options: GenerationOptions,
        adjustments: IconAdjustmentPipeline = IconAdjustmentPipeline(resources, options)
    ) : this(resources, options.modifiers, options.export, adjustments)

    private val materialYouPack = modifiers.materialYouPack
    private val effect = modifiers.effect
    private val colorizer = effect.colorizer

    private val colorizeColor
        get() = if (colorizer.inverse) invertArgb(colorizer.firstColor) else colorizer.firstColor

    fun apply(icon: IconPackDrawable, imageEdit: ImageEdit): IconPackDrawable {
        val source = if (icon is AdaptiveIconPackDrawable) restyleMaterialYouPackIcon(icon) else icon
        return adjustments.apply(applyEdit(source, imageEdit))
    }

    fun applyPrimary(icon: IconPackDrawable): IconPackDrawable =
        apply(icon, effect.imageEdit)

    internal fun applyEdit(icon: IconPackDrawable, imageEdit: ImageEdit): IconPackDrawable {
        if (imageEdit == ImageEdit.NONE) return icon
        if (imageEdit == ImageEdit.THEMED) return themedGlyph(icon)
        // Its colours are the launcher's: a later colour edit (a Global style layer) would only
        // bake the current wallpaper colours into a plain bitmap.
        if (icon is ThemedGlyphDrawable) return icon

        if (icon is AdaptiveIconPackDrawable) {
            if (export.themed) {
                return applyEdit(BitmapIconDrawable(resources, icon.foreground), imageEdit)
            }
            if (imageEdit == ImageEdit.COLORIZE && effect.segments.isEmpty()) {
                val foreground = colorizeAdaptiveForeground(icon.foreground)
                return icon.withForeground(foreground)
            }
            return applyToBitmap(adjustments.rasterFrame(icon), imageEdit, colorizer.blendMode)
        }

        if (imageEdit == ImageEdit.COLORIZE &&
            colorizer.mode == ColorizerMode.SINGLE_COLOR &&
            !colorizer.lighten &&
            !colorizer.monochrome
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
                ImageEdit.COLORIZE_SEGMENTS -> colorize(copy.toBitmap(), colorizer.blendMode)
                ImageEdit.COLORIZE -> {
                    if (colorizer.lighten ||
                        colorizer.mode == ColorizerMode.GRADIENT ||
                        colorizer.monochrome
                    ) {
                        colorize(copy.toBitmap(), colorizer.blendMode)
                    } else {
                        copy.root.setReferenceColorPaths(SolidColor(Color(colorizeColor)))
                        copy.tintColor = Color.Unspecified
                        copy
                    }
                }
                ImageEdit.PATH -> trace(copy.toBitmap())
                ImageEdit.EDGE -> detectEdges(copy.toBitmap())
                ImageEdit.REMOVE_BACKGROUND -> removeBackground(copy.toBitmap())
                ImageEdit.THEMED -> themedGlyph(icon)
            }
        }

        val modified = applyToBitmap(icon.toBitmap(), imageEdit, colorizer.blendMode)
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
        ImageEdit.THEMED -> ThemedGlyphDrawable(flatIconGlyph(bitmap))
    }

    /**
     * The icon as a themed glyph. A pack's adaptive icon brings its own glyph in its layer: the
     * Material You (monochrome) layer when it has one, the foreground otherwise. Flat artwork has
     * its logo separated from its plate the way the generated Material You variant does it.
     */
    private fun themedGlyph(icon: IconPackDrawable): ThemedGlyphDrawable {
        if (icon is ThemedGlyphDrawable) return icon
        if (icon is AdaptiveIconPackDrawable) {
            // The foreground keeps the pack's own glyph size (Lawnicons insets it 32%, its
            // monochrome layer only 28%, which grew the glyph). The monochrome layer is only for
            // a foreground that is full artwork rather than a glyph.
            val foreground = icon.foreground
            val monochrome = if (foreground.hasTransparentBorder()) null else icon.monochrome
            val mask = glyphMask(monochrome ?: foreground, ThemedGlyphDrawable.MASK_SIZE, scale = 1f)
            foreground.recycle()
            monochrome?.recycle()
            return ThemedGlyphDrawable(mask)
        }
        return ThemedGlyphDrawable(flatIconGlyph(adjustments.rasterFrame(icon)))
    }

    // Flat artwork fills the launcher's 72dp viewport; as a themed glyph it starts at the size
    // Renkin's themed export gives icons (half the 108dp layer). Scale in the Modifier tab resizes it.
    private fun flatIconGlyph(frame: Bitmap): Bitmap =
        glyphMask(frame, ThemedGlyphDrawable.MASK_SIZE, scale = THEMED_GLYPH_SCALE)

    private fun glyphMask(source: Bitmap, size: Int, scale: Float): Bitmap {
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        // Artwork without a plate already is the glyph. The two-tone split would treat a light
        // glyph on transparency (Lawnicons in dark mode) as background and erase it.
        if (hasTransparentBorder(pixels, source.width, source.height)) {
            for (index in pixels.indices) pixels[index] = (pixels[index] and ALPHA_MASK) or WHITE_RGB
        } else {
            val coverage = materialYouForegroundCoverage(pixels, source.width, source.height)
            for (index in pixels.indices) {
                pixels[index] = android.graphics.Color.argb((coverage[index] * 255).toInt().coerceIn(0, 255), 255, 255, 255)
            }
        }
        val glyph = Bitmap.createBitmap(pixels, source.width, source.height, Bitmap.Config.ARGB_8888)
        val side = size * scale
        val offset = (size - side) / 2f
        return newArgbBitmap(size, size) { canvas ->
            canvas.drawBitmap(glyph, null, RectF(offset, offset, offset + side, offset + side), Paint(Paint.FILTER_BITMAP_FLAG))
        }.also { glyph.recycle() }
    }

    private fun Bitmap.hasTransparentBorder(): Boolean {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        return hasTransparentBorder(pixels, width, height)
    }

    private fun hasTransparentBorder(pixels: IntArray, width: Int, height: Int): Boolean {
        val band = maxOf(1, minOf(width, height) / 20)
        var transparent = 0
        var total = 0
        for (y in 0 until height) for (x in 0 until width) {
            if (x >= band && y >= band && x < width - band && y < height - band) continue
            total++
            if ((pixels[y * width + x] ushr 24) < 16) transparent++
        }
        return total > 0 && transparent >= total * 0.9f
    }

    private fun colorizeAdaptiveForeground(source: Bitmap): Bitmap {
        if (colorizer.mode != ColorizerMode.GRADIENT) {
            return colorize(source, colorizer.blendMode).toBitmap()
        }
        val gradient = requireNotNull(gradientPixels(colorizer, source.width, source.height))
        val base = if (colorizer.monochrome) monochromeBitmap(source, colorizer.inverse) else source
        val pixels = IntArray(source.width * source.height)
        base.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        val solid = colorizer.blendMode == PorterDuff.Mode.SRC_IN
        val lighten = colorizer.blendMode == PorterDuff.Mode.SCREEN
        for (index in pixels.indices) {
            val original = pixels[index]
            val tint = gradient[index]
            val tintAlpha = android.graphics.Color.alpha(tint) / 255f
            fun channel(shift: Int): Int {
                val value = (original ushr shift) and 255
                val color = (tint ushr shift) and 255
                return when {
                    solid -> color
                    lighten ->
                        (value + (255 - value) * tintAlpha * color / 255f).toInt()
                    else -> (value * (1f - tintAlpha + tintAlpha * color / 255f)).toInt()
                }
            }
            // Canvas MULTIPLY over a transparent layer would paint the entire gradient rectangle.
            val alpha = android.graphics.Color.alpha(original)
            val colored = android.graphics.Color.argb(
                if (solid) (alpha * tintAlpha).toInt() else alpha,
                channel(16), channel(8), channel(0)
            )
            pixels[index] = if (colorizer.inverse && !colorizer.monochrome) invertArgb(colored) else colored
        }
        return Bitmap.createBitmap(pixels, source.width, source.height, Bitmap.Config.ARGB_8888).apply {
            density = source.density
        }
    }

    /**
     * A retained pack icon already carries the colours of its edit state. Recolouring it again would
     * round its anti-aliased layers differently, so only an edited state is applied.
     */
    internal fun restyleRetainedPackIcon(icon: IconPackDrawable): IconPackDrawable {
        if (icon !is AdaptiveIconPackDrawable || icon.materialYouEditState == null) return icon
        if (materialYouPack.editState() == icon.materialYouEditState) return icon
        return restyleMaterialYouPackIcon(icon)
    }

    private fun restyleMaterialYouPackIcon(icon: AdaptiveIconPackDrawable): AdaptiveIconPackDrawable {
        if (icon.materialYouEditState == null) return icon
        val state = materialYouPack.editState() ?: return icon
        if (state.selectedScheme < 0) return icon.restoreOriginalMaterialYouLayers(state)

        val foregroundColor = materialYouPack.foreground ?: return icon
        val backgroundColor = materialYouPack.background ?: return icon
        val foreground = tintAlphaLayer(icon.foreground, foregroundColor)
        val background = newArgbBitmap(AdaptiveIconPackDrawable.LAYER_SIZE, AdaptiveIconPackDrawable.LAYER_SIZE) {
            it.drawColor(backgroundColor)
        }
        return icon.withMaterialYouLayers(foreground, background, state)
    }

    private fun tintAlphaLayer(source: Bitmap, color: Int): Bitmap =
        newArgbBitmap(source.width, source.height) { canvas ->
            canvas.drawBitmap(source, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
            canvas.drawColor(color, PorterDuff.Mode.SRC_IN)
        }

    internal fun colorize(bitmap: Bitmap, mode: PorterDuff.Mode): IconPackDrawable {
        if (effect.segments.isNotEmpty()) {
            return colorizedBitmap(
                addBackground(
                    applySegmentLayers(themedColorizeSource(bitmap), effect.segments)
                )
            )
        }
        if (colorizer.mode == ColorizerMode.GRADIENT) {
            return colorizedBitmap(colorizeWithGradient(bitmap))
        }
        if (colorizer.monochrome) {
            return defaultBitmap(monochromeBitmap(bitmap, colorizer.inverse))
        }
        return colorizedBitmap(colorizeBitmap(bitmap, mode))
    }

    /** Themed pixels already contain their export inset; only their UI preview gets launcher zoom. */
    private fun colorizedBitmap(bitmap: Bitmap): BitmapIconDrawable = BitmapIconDrawable(
        resources,
        bitmap,
        exportAsAdaptiveIcon = export.themed,
        previewScale = if (export.themed) ADAPTIVE_ICON_SCALE else 1f
    )

    internal fun colorizeVector(vector: ImageVectorDrawable): IconPackDrawable {
        if (colorizer.lighten) return colorize(vector.toBitmap(), colorizer.blendMode)
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
        return if (export.themed) vectorToInset(vector) else vector
    }

    private fun detectEdges(bitmap: Bitmap): IconPackDrawable {
        val detector = CannyEdgeDetector()
        detector.process(
            bitmap.asImageBitmap(),
            colorizer.firstColor,
            DetectionOptions().apply {
                lowThreshold = effect.edges.lowThreshold
                highThreshold = effect.edges.highThreshold
                gaussianKernelRadius = effect.edges.smoothing
                contrastNormalized = effect.edges.normalizeContrast
            }
        )
        return if (export.themed) {
            bitmapToInset(detector.edgesImage)
        } else {
            BitmapIconDrawable(resources, detector.edgesImage)
        }
    }

    private fun removeBackground(bitmap: Bitmap): IconPackDrawable {
        val cleaned = bitmap.removeMatchedBackground(
            targets = effect.backgroundRemoval.targets,
            tolerance = effect.backgroundRemoval.tolerance
        )
        // Hand strokes come last: they are corrections to whatever the colour match decided, and
        // restoring reads from the untouched artwork rather than from the cleaned result.
        return defaultBitmap(
            cleaned.applyBackgroundBrush(
                original = bitmap,
                operations = effect.backgroundRemoval.brushOperations
            )
        )
    }

    private fun colorizeBitmap(icon: Bitmap, mode: PorterDuff.Mode): Bitmap {
        val source = themedColorizeSource(icon)
        val coloredIcon = if (mode == PorterDuff.Mode.SCREEN) {
            screenColorizeBitmap(source, colorizer.firstColor)
        } else {
            source.emptyLike().also { result ->
                Canvas(result).drawBitmap(
                    source,
                    0f,
                    0f,
                    Paint().apply { colorFilter = PorterDuffColorFilter(colorizer.firstColor, mode) }
                )
            }
        }
        if (source !== icon) source.recycle()
        val result = addBackground(coloredIcon)
        return if (colorizer.inverse) invertBitmapColors(result) else result
    }

    private fun themedColorizeSource(icon: Bitmap): Bitmap {
        if (!export.themed) return icon
        return icon.emptyLike().also { scaled ->
            Canvas(scaled).apply {
                scale(0.5f, 0.5f, icon.width * 0.5f, icon.height * 0.5f)
                drawBitmap(icon, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
            }
        }
    }

    private fun colorizeWithGradient(icon: Bitmap): Bitmap {
        val centerX = icon.width / 2f
        val centerY = icon.height / 2f
        val base = if (colorizer.monochrome) {
            monochromeBitmap(icon, colorizer.inverse)
        } else {
            icon
        }
        val style = colorizer
        val gradient = buildColorizerShader(
            style.allGradientColors,
            style.gradientType,
            style.gradientAngle,
            icon.width,
            icon.height,
            style.gradientPositions
        )
        val coloredIcon = icon.emptyLike()
        val canvas = Canvas(coloredIcon)
        val solidFill = colorizer.flat && !colorizer.monochrome
        val drawMask = {
            val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                if (solidFill) xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            }
            if (export.themed) {
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
                    if (!solidFill) {
                        xfermode = PorterDuffXfermode(
                            if (colorizer.lighten) PorterDuff.Mode.SCREEN
                            else PorterDuff.Mode.MULTIPLY
                        )
                    }
                }
            )
        }
        if (solidFill) {
            drawGradient()
            drawMask()
        } else {
            drawMask()
            drawGradient()
            if (colorizer.lighten) {
                val alphaMask = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                }
                if (export.themed) {
                    canvas.save()
                    canvas.scale(0.5f, 0.5f, centerX, centerY)
                    canvas.drawBitmap(base, 0f, 0f, alphaMask)
                    canvas.restore()
                } else {
                    canvas.drawBitmap(base, 0f, 0f, alphaMask)
                }
            }
        }
        val tinted = if (colorizer.inverse && !colorizer.monochrome) {
            invertBitmapColors(coloredIcon)
        } else {
            coloredIcon
        }
        return addBackground(tinted)
    }

    private fun addBackground(image: Bitmap): Bitmap {
        if (!export.themed) return image
        val shader = export.background.shader(image.width, image.height)
        // A solid themed background is the adaptive icon's own background layer from Android 8 on,
        // and it follows the wallpaper. Baking it into the glyph left a square of the old colour
        // there after a wallpaper change, and kept the glyph from exporting as a tintable mask.
        if (shader == null && PackageVersion.is26OrMore()) return image
        shader ?: return image.changeBackgroundColor(export.background.color)
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
        return result
    }

    private fun defaultBitmap(bitmap: Bitmap): IconPackDrawable =
        if (export.themed) bitmapToInset(bitmap) else BitmapIconDrawable(resources, bitmap)

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
            SolidColor(Color(colorizer.firstColor))
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

/** Zero tolerance deliberately leaves colour removal off so brush-only corrections are possible. */
internal fun Bitmap.removeMatchedBackground(targets: List<Int>, tolerance: Float): Bitmap = when {
    tolerance <= 0f -> this
    targets.isNotEmpty() -> removeSegmentColors(this, targets, tolerance)
    else -> removeBackground(tolerance)
}

// Share of the 108dp layer a flat icon's glyph starts at; the pack-wide themed export uses the same.
private const val THEMED_GLYPH_SCALE = 0.5f
private const val ALPHA_MASK = 0xFF000000.toInt()
private const val WHITE_RGB = 0x00FFFFFF
