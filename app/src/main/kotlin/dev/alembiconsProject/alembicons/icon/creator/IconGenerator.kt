package dev.alembiconsProject.alembicons.icon.creator

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.VectorDrawable
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.alembiconsProject.alembicons.constants.SuppressSameParameterValue
import dev.alembiconsProject.alembicons.data.ImageEdit
import dev.alembiconsProject.alembicons.data.Source
import dev.alembiconsProject.alembicons.data.TextType
import dev.alembiconsProject.alembicons.drawable.BaseTextDrawable
import dev.alembiconsProject.alembicons.drawable.BitmapIconDrawable
import dev.alembiconsProject.alembicons.drawable.ForegroundIconDrawable
import dev.alembiconsProject.alembicons.drawable.IconPackDrawable
import dev.alembiconsProject.alembicons.drawable.ImageVectorDrawable
import dev.alembiconsProject.alembicons.drawable.InsetIconDrawable
import dev.alembiconsProject.alembicons.drawable.ResourceDrawable
import dev.alembiconsProject.alembicons.drawable.foregroundVectorOrNull
import dev.alembiconsProject.alembicons.drawable.haveMonochrome
import dev.alembiconsProject.alembicons.drawable.isAdaptiveIconDrawable
import dev.alembiconsProject.alembicons.drawable.shrinkIfBiggerThan
import dev.alembiconsProject.alembicons.extension.changeBackgroundColor
import dev.alembiconsProject.alembicons.extension.clone
import dev.alembiconsProject.alembicons.extension.scaleFromCenter
import dev.alembiconsProject.alembicons.icon.parser.IconParser
import dev.alembiconsProject.alembicons.packages.ApplicationManager
import dev.alembiconsProject.alembicons.packages.PackageInfoStruct
import dev.alembiconsProject.alembicons.drawable.toImageVectorDrawable
import dev.alembiconsProject.alembicons.packages.PackageVersion
import dev.alembiconsProject.alembicons.vector.PathConverter.Companion.toNodes
import dev.alembiconsProject.alembicons.vector.VectorEditor.Companion.applyAndRemoveGroup
import dev.alembiconsProject.alembicons.vector.VectorEditor.Companion.editPathColors
import dev.alembiconsProject.alembicons.vector.VectorEditor.Companion.editStrokePaths
import dev.alembiconsProject.alembicons.vector.VectorEditor.Companion.editPaths
import dev.alembiconsProject.alembicons.vector.VectorEditor.Companion.resizeAndCenter
import dev.alembiconsProject.alembicons.vector.VectorEditor.Companion.scaleAtCenter
import dev.alembiconsProject.alembicons.vector.VectorEditor.Companion.setReferenceColorPaths
import dev.alembiconsProject.imagetracer.ImageTracer
import dev.alembiconsProject.tgCannyEdgeCompose.CannyEdgeDetector
import dev.alembiconsProject.tgCannyEdgeCompose.DetectionOptions

class IconGenerator(
    private val ctx: Context,
    private val options: GenerationOptions,
    private val primaryIconPackApplications: IconPackContainer,
    private val secondaryIconPackApplications: IconPackContainer
) {
    private val adaptiveIconScale = 1.5f // 108dp / 72dp

    fun generateIcon(application: PackageInfoStruct,
                     onUpdate: (application: PackageInfoStruct, icon: IconPackDrawable?) -> Unit) {
        generateIcons(listOf(application), onUpdate)
    }

    fun generateIcon(application: PackageInfoStruct,
                     customIcon: ResourceDrawable?,
                     onUpdate: (application: PackageInfoStruct, icon: IconPackDrawable?) -> Unit)  {
        if (options.primarySource == Source.NONE) {
            return
        }

        if (applicationShouldBeSkipped(application)) {
            return
        }

        val icon = generateIcon(
            application,
            options.primarySource,
            options.primaryImageEdit,
            options.primaryTextType,
            primaryIconPackApplications,
            customIcon
        )

        onUpdate(application, icon?.let { applyAdjustments(it) })
    }

    fun generateIcons(applications: List<PackageInfoStruct>
                      , onUpdate: (application: PackageInfoStruct, icon: IconPackDrawable?) -> Unit) {
        if (options.primarySource == Source.NONE) {
            return
        }

        for (app in applications) {
            if (applicationShouldBeSkipped(app)) {
                continue
            }

            val icon = generateIcon(
                app,
                options.primarySource,
                options.primaryImageEdit,
                options.primaryTextType,
                primaryIconPackApplications
            )
                ?: generateIcon(
                    app,
                    options.secondarySource,
                    options.secondaryImageEdit,
                    options.secondaryTextType,
                    secondaryIconPackApplications
                )

            onUpdate(app, icon)
        }
    }

    /**
     * Applies an image modifier to an already-built icon (e.g. a hand-edited
     * vector), instead of regenerating one from a source. Vectors are colourised
     * in place to stay crisp; path/edge detection rasterise first since they are
     * bitmap operations. Works on a copy — colourising and [toBitmap] mutate the
     * vector in place.
     */
    fun applyModifier(icon: IconPackDrawable, imageEdit: ImageEdit): IconPackDrawable {
        return applyAdjustments(applyModifierInner(icon, imageEdit))
    }

    private fun applyModifierInner(icon: IconPackDrawable, imageEdit: ImageEdit): IconPackDrawable {
        if (imageEdit == ImageEdit.NONE) return icon

        if (icon is ImageVectorDrawable) {
            val copy = ImageVectorDrawable(icon.toImageVector())
            return when (imageEdit) {
                ImageEdit.NONE -> icon
                ImageEdit.COLORIZE -> {
                    // Recolour while keeping each path's fill-vs-stroke nature (#117),
                    // unlike colorizeVector which forces everything to a stroke
                    copy.root.setReferenceColorPaths(SolidColor(Color(options.color)))
                    copy.tintColor = Color.Unspecified
                    copy
                }
                ImageEdit.PATH -> generatePathTracing(copy.toBitmap(), null)
                ImageEdit.EDGE -> generateCannyEdgeDetection(copy.toBitmap(), null)
            }
        }

        // Non-vector icons rasterise, then run through the shared modifier dispatch
        // (NONE is already handled by the early return at the top of this method).
        return generateImage(icon.toBitmap(), null, imageEdit, PorterDuff.Mode.MULTIPLY)
    }

    fun colorizeFromIconPack(iconPackName: String, icon: ResourceDrawable): IconPackDrawable? {
        val bitmapIcon = getIconBitmap(icon.drawable) ?: return null
        val parsedIcon = exportIconPackXML(iconPackName, icon)

        return if (options.primaryImageEdit == ImageEdit.COLORIZE)
            colorizeImage(bitmapIcon, parsedIcon, PorterDuff.Mode.MULTIPLY)
        else
            getDefaultIcon(bitmapIcon, parsedIcon)
    }

    private fun generateIcon(
        application: PackageInfoStruct,
        source: Source,
        imageEdit: ImageEdit,
        textType: TextType,
        iconPack: IconPackContainer,
        customIcon: ResourceDrawable? = null
    ): IconPackDrawable? {
        return when (source) {
            Source.NONE -> null
            Source.ICON_PACK -> generateImageFromIconPack(application, imageEdit, iconPack, customIcon)
            Source.APPLICATION_ICON -> generateImageFromApplication(application, imageEdit)
            Source.APPLICATION_NAME -> generateText(application.appName, textType)
        }
    }

    private fun generateImageFromIconPack(
        application: PackageInfoStruct,
        imageEdit: ImageEdit,
        iconPack: IconPackContainer,
        customIcon: ResourceDrawable? = null
    ): IconPackDrawable? {
        val resIcon = customIcon ?: iconPack.getApplicationIcon(application.packageName) ?: return null

        val bitmapIcon = getIconBitmap(resIcon.drawable) ?: return null
        val parsedIcon = exportIconPackXML(iconPack.iconPackName, resIcon)

        return generateImage(bitmapIcon, parsedIcon, imageEdit, PorterDuff.Mode.MULTIPLY)
    }

    private fun generateImageFromApplication(
        application: PackageInfoStruct,
        imageEdit: ImageEdit): IconPackDrawable? {

        val bitmapIcon = getAppIconBitmap(application) ?: return null
        val parsedIcon = parseApplicationIcon(application)

        return generateImage(bitmapIcon, parsedIcon, imageEdit, PorterDuff.Mode.MULTIPLY)
    }

    /**
     * Single dispatch point for applying an [ImageEdit] to a (bitmap, parsedIcon)
     * pair. Adding a new modifier means adding one [ImageEdit] entry and one branch
     * here — every "generate from a source" path routes through this method.
     */
    private fun generateImage(
        bitmapIcon: Bitmap,
        parsedIcon: Drawable?,
        imageEdit: ImageEdit,
        mode: PorterDuff.Mode): IconPackDrawable {
        if (parsedIcon != null && parsedIcon.isAdaptiveIconDrawable()) {
            fixAdaptiveIconSize(parsedIcon as AdaptiveIconDrawable)
        }

        return when (imageEdit) {
            ImageEdit.NONE -> getDefaultIcon(bitmapIcon, parsedIcon)
            ImageEdit.PATH -> generatePathTracing(bitmapIcon, parsedIcon)
            ImageEdit.EDGE -> generateCannyEdgeDetection(bitmapIcon, parsedIcon)
            ImageEdit.COLORIZE -> colorizeImage(bitmapIcon, parsedIcon, mode)
        }
    }

    private fun generateText(applicationName: String, textType: TextType): IconPackDrawable {
        val size = 256
        val strokeWidth = size / 48F
        val textGenerator = LetterGenerator(ctx)

        val newIcon = when(textType) {
            TextType.FULL_NAME -> {
                val draw = textGenerator.generateAppName(applicationName, options.color, size)
                createVectorForMultiLineText(draw as BaseTextDrawable, options.color, size)
            }
            TextType.ONE_LETTER -> {
                val draw = textGenerator.generateFirstLetter(applicationName, options.color, strokeWidth, size)
                createVectorForText(draw as BaseTextDrawable, options.color, strokeWidth, size)
            }
            TextType.TWO_LETTERS -> {
                val draw = textGenerator.generateTwoLetters(applicationName, options.color, strokeWidth, size)
                createVectorForText(draw as BaseTextDrawable, options.color, strokeWidth, size)
            }
        }

        if (options.themed)
            return vectorToInset(newIcon.toImageVectorDrawable())

        return newIcon.toImageVectorDrawable()
    }

    private fun parseApplicationIcon(application: PackageInfoStruct): Drawable? {
        val appMan = ApplicationManager(ctx)

        if (isVectorDrawable(application.icon) && options.vector) {
            val res = appMan.getResources(application.packageName) ?: return null
            return IconParser.parseDrawable(res, application.icon, application.iconID)
        }

        return null
    }

    private fun generateCannyEdgeDetection(bitmapIcon: Bitmap, parsedIcon: Drawable?): IconPackDrawable {
        val edgeDetector = CannyEdgeDetector()

        edgeDetector.process(
            bitmapIcon.asImageBitmap(),
            options.color,
            DetectionOptions().apply {
                lowThreshold = options.edgeLowThreshold
                highThreshold = options.edgeHighThreshold
                gaussianKernelRadius = options.edgeGaussianRadius
                contrastNormalized = options.edgeContrastNormalized
            }
        )

        if (parsedIcon != null) {
            if (parsedIcon.isAdaptiveIconDrawable()) {
                parsedIcon as AdaptiveIconDrawable
                if (parsedIcon.foreground is InsetIconDrawable) {
                    val foreground = parsedIcon.foreground as InsetIconDrawable
                    return foreground.newDrawable(BitmapIconDrawable(ctx.resources, edgeDetector.edgesImage))
                }
            }

            if (parsedIcon is InsetIconDrawable) {
                return parsedIcon.newDrawable(BitmapIconDrawable(ctx.resources, edgeDetector.edgesImage))
            }
        }

        return if (options.themed) {
            bitmapToInset(edgeDetector.edgesImage)
        } else {
            BitmapIconDrawable(ctx.resources, edgeDetector.edgesImage)
        }
    }

    private fun generatePathTracing(bitmapIcon: Bitmap, parsedIcon: Drawable?): IconPackDrawable {
        return if (parsedIcon != null) {
            generatePathFromXML(bitmapIcon, parsedIcon)
        } else {
            generateColorQuantizationDetection(bitmapIcon)
        }
    }

    private fun generatePathFromXML(bitmapIcon: Bitmap, parsedIcon: Drawable): IconPackDrawable {
        var vectorIcon = parsedIcon

        if (parsedIcon.isAdaptiveIconDrawable()) {
            parsedIcon as AdaptiveIconDrawable

            if (parsedIcon.foreground is ImageVectorDrawable) {
                vectorIcon = parsedIcon.foreground
            }

            if (parsedIcon.foreground is InsetIconDrawable) {
                val inset = parsedIcon.foreground as InsetIconDrawable
                if (inset.drawable is ImageVectorDrawable) {
                    vectorIcon = inset.drawable

                    recolorVectorStrokes(vectorIcon)
                    return inset
                }
            }

            if (parsedIcon.haveMonochrome() && options.monochrome) {
                vectorIcon = parsedIcon.monochrome!!
            }
        }

        if (parsedIcon is InsetIconDrawable) {
            if (parsedIcon.drawable is ImageVectorDrawable) {
                vectorIcon = parsedIcon.drawable

                recolorVectorStrokes(vectorIcon)
                return parsedIcon
            }
        }

        if (vectorIcon !is ImageVectorDrawable) {
            return generateColorQuantizationDetection(bitmapIcon)
        }

        recolorVectorStrokes(vectorIcon)

        if (options.themed) {
            return vectorToInset(vectorIcon)
        }

        return vectorIcon
    }

    private fun vectorToInset(vector: ImageVectorDrawable, scale: Float = 0.25f): InsetIconDrawable {
        val x = vector.viewportWidth * scale
        val y = vector.viewportHeight * scale

        val dims = android.graphics.Rect(x.toInt(), y.toInt(), x.toInt(), y.toInt())
        val fractions = RectF(scale, scale, scale, scale)

        return InsetIconDrawable(vector, dims, fractions)
    }

    private fun bitmapToInset(bitmap: Bitmap, scale: Float = 0.25f): InsetIconDrawable {
        val x = bitmap.width * scale
        val y = bitmap.height * scale

        val dims = android.graphics.Rect(x.toInt(), y.toInt(), x.toInt(), y.toInt())
        val fractions = RectF(scale, scale, scale, scale)

        return InsetIconDrawable(BitmapIconDrawable(ctx.resources, bitmap), dims, fractions)
    }

    private fun generateColorQuantizationDetection(bitmapIcon: Bitmap): IconPackDrawable {
        val imageVector = ImageTracer.imageToVector(bitmapIcon.asImageBitmap()
            , ImageTracer.TracingOptions())

        val vector = imageVector.toImageVectorDrawable()
        recolorVectorStrokes(vector)
        vector.resizeAndCenter()

        if (options.themed) {
            return vectorToInset(vector)
        }

        return vector
    }

    private fun getAppIconBitmap(app: PackageInfoStruct, maxSize: Int = 500): Bitmap? {
        var newIcon = app.icon

        if (newIcon.isAdaptiveIconDrawable()) {
            val adaptiveIcon = newIcon as AdaptiveIconDrawable
            if (adaptiveIcon.foreground is BitmapDrawable || adaptiveIcon.foreground is VectorDrawable) {
                newIcon = ForegroundIconDrawable(adaptiveIcon.foreground)
            }

            if (PackageVersion.is33OrMore() && adaptiveIcon.monochrome != null && options.monochrome) {
                if (adaptiveIcon.monochrome is BitmapDrawable || adaptiveIcon.monochrome is VectorDrawable) {
                    newIcon = ForegroundIconDrawable(adaptiveIcon.monochrome!!)
                }
            }
        }

        return newIcon.shrinkIfBiggerThan(maxSize)
    }

    private fun getDefaultIcon(bitmapIcon: Bitmap, parsedIcon: Drawable?): IconPackDrawable {
        if (parsedIcon != null) {
            if (parsedIcon.isAdaptiveIconDrawable()) {
                parsedIcon as AdaptiveIconDrawable
                return getDefaultIcon(bitmapIcon, parsedIcon.foreground)
            }
        }

        return when (parsedIcon) {
            is InsetIconDrawable -> parsedIcon
            is ImageVectorDrawable -> getDefaultVectorIcon(parsedIcon)
            else -> getDefaultBitmapIcon(bitmapIcon)
        }
    }

    private fun getDefaultBitmapIcon(bitmap: Bitmap): IconPackDrawable {
        return if (options.themed) {
            bitmapToInset(bitmap)
        } else {
            BitmapIconDrawable(ctx.resources, bitmap)
        }
    }

    private fun getDefaultVectorIcon(vectorIcon: ImageVectorDrawable): IconPackDrawable {
        return if (options.themed) {
            vectorToInset(vectorIcon)
        } else {
            vectorIcon
        }
    }

    private fun isVectorDrawable(image: Drawable): Boolean {
        if (image is VectorDrawable)
            return true

        if (image.isAdaptiveIconDrawable()) {
            image as AdaptiveIconDrawable
            if (image.foreground is VectorDrawable) {
                return true
            }

            if (image.foreground is InsetDrawable) {
                val inset = image.foreground as InsetDrawable
                if (inset.drawable is VectorDrawable) {
                    return true
                }
            }

            if (PackageVersion.is33OrMore() && options.monochrome) {
                if (image.monochrome is VectorDrawable) {
                    return true
                }
            }
        }

        return false
    }

    @Suppress(SuppressSameParameterValue)
    private fun createVectorForText(drawable: BaseTextDrawable, color: Int, strokeWidth: Float, size: Int): ImageVector {
        val builder = ImageVector.Builder(defaultWidth = size.dp
            , defaultHeight = size.dp, viewportWidth = size.toFloat(), viewportHeight = size.toFloat())

        val paths = drawable.getPaths()
        for (path in paths) {
            val cPath = path.asComposePath()
            builder.addPath(cPath.toNodes()
                , stroke = SolidColor(Color(color))
                , strokeLineWidth = strokeWidth)
        }

        return builder.build()
    }

    @Suppress(SuppressSameParameterValue)
    private fun createVectorForMultiLineText(drawable: BaseTextDrawable, color: Int, size: Int): ImageVector {
        val builder = ImageVector.Builder(defaultWidth = size.dp
            , defaultHeight = size.dp, viewportWidth = size.toFloat(), viewportHeight = size.toFloat())

        val paths = drawable.getPaths()
        for (path in paths) {
            val cPath = path.asComposePath()
            builder.addPath(cPath.toNodes()
                , fill = SolidColor(Color(color)))
        }

        return builder.build()
    }

    private fun getIconBitmap(icon: Drawable, maxSize: Int = 500): Bitmap? {
        return if (icon.isAdaptiveIconDrawable()) {
            icon as AdaptiveIconDrawable
            // An adaptive icon's foreground only fills the inner 72/108 safe zone,
            // so the raw bitmap renders too small. Scale it up to fill the frame —
            // the bitmap analogue of fixAdaptiveIconSize for vector foregrounds (#80).
            if (icon.foreground is InsetDrawable) {
                val inset = icon.foreground as InsetDrawable
                inset.drawable?.shrinkIfBiggerThan(maxSize)?.scaleFromCenter(adaptiveIconScale)
            } else {
                icon.foreground.shrinkIfBiggerThan(maxSize)?.scaleFromCenter(adaptiveIconScale)
            }
        } else {
            if (icon is InsetDrawable) {
                icon.drawable?.shrinkIfBiggerThan(maxSize)
            } else {
                icon.shrinkIfBiggerThan(maxSize)
            }
        }
    }

    private fun colorizeBitmap(icon: Bitmap, mode: PorterDuff.Mode): Bitmap {
        val coloredIcon = icon.clone()
        val paint = Paint()

        paint.colorFilter = PorterDuffColorFilter(options.color, mode)
        val canvas = Canvas(coloredIcon)
        if (options.themed) canvas.scale(0.5f, 0.5f, icon.width * 0.5f, icon.height * 0.5f)
        canvas.drawBitmap(icon, 0F, 0F, paint)

        return addBackground(coloredIcon)
    }

    private fun addBackground(image: Bitmap): Bitmap {
        return if (options.themed) image.changeBackgroundColor(options.bgColor) else image
    }

    private fun exportIconPackXML(iconPackName: String, iconDrawable: ResourceDrawable): Drawable? {
        // Parsing only extracts an editable vector — a malformed adaptive icon XML
        // in a third-party pack must fall back to the plain bitmap, not crash (#119)
        return try {
            parseIconPackXML(iconPackName, iconDrawable)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseIconPackXML(iconPackName: String, iconDrawable: ResourceDrawable): Drawable? {
        if (!isVectorDrawable(iconDrawable.drawable)) return null

        val res = ApplicationManager(ctx).getResources(iconPackName) ?: return null
        val icon = IconParser.parseDrawable(res, iconDrawable.drawable, iconDrawable.resourceId)

        if (!icon.isAdaptiveIconDrawable()) return null

        val vectorIcon = icon.foregroundVectorOrNull() ?: return null

        val stroke = vectorIcon.viewportHeight / 48 //1F at 48
        vectorIcon.root.editStrokePaths(stroke)

        return icon
    }

    private fun colorizeImage(bitmapIcon: Bitmap, parsedIcon: Drawable?, mode: PorterDuff.Mode): IconPackDrawable {
        return when (parsedIcon) {
            is InsetIconDrawable -> {
                parsedIcon.newDrawable(colorizeImage(bitmapIcon, parsedIcon.drawable, mode))
            }
            is ImageVectorDrawable -> colorizeVector(parsedIcon)
            else -> BitmapIconDrawable(ctx.resources, colorizeBitmap(bitmapIcon, mode))
        }
    }

    /**
     * Applies the per-icon Modifier-tab adjustments (background removal, scale) on top
     * of an already-built icon. No-op with the defaults (removeBackground=false,
     * iconScale=1f), so it's safe to run on every generation path.
     */
    private fun applyAdjustments(icon: IconPackDrawable): IconPackDrawable {
        var result = icon
        if (options.removeBackground) result = removeIconBackground(result)
        if (options.iconScale < 1f) result = scaleWithPadding(result, options.iconScale)
        return result
    }

    /** Pads the icon inside its frame by [scale] (1f = none, 0.5f = half size, centred). */
    private fun scaleWithPadding(icon: IconPackDrawable, scale: Float): IconPackDrawable {
        val fraction = ((1f - scale) / 2f).coerceIn(0f, 0.49f)
        val fractions = RectF(fraction, fraction, fraction, fraction)
        return InsetIconDrawable(icon, Rect(), fractions)
    }

    /**
     * Removes a solid background by flood-filling from the four corners: pixels matching
     * a corner's colour (within tolerance) become transparent. Layered/adaptive icons
     * already have transparent corners, so nothing is removed there — covering both the
     * "separate background layer" and "flat icon with baked-in background" cases.
     */
    private fun removeIconBackground(icon: IconPackDrawable): IconPackDrawable {
        val bitmap = icon.toBitmap().copy(Bitmap.Config.ARGB_8888, true)
        floodFillCornersTransparent(bitmap)
        return BitmapIconDrawable(ctx.resources, bitmap)
    }

    private fun floodFillCornersTransparent(bitmap: Bitmap, tolerance: Int = 32) {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return

        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val visited = BooleanArray(w * h)
        val stack = ArrayDeque<Int>()

        for (corner in intArrayOf(0, w - 1, (h - 1) * w, w * h - 1)) {
            val seed = pixels[corner]
            // A transparent corner means there's no solid background to strip here
            if (android.graphics.Color.alpha(seed) == 0 || visited[corner]) continue

            stack.addLast(corner)
            while (stack.isNotEmpty()) {
                val i = stack.removeLast()
                if (visited[i] || !colorClose(pixels[i], seed, tolerance)) continue
                visited[i] = true
                pixels[i] = 0 // transparent

                val x = i % w
                val y = i / w
                if (x > 0) stack.addLast(i - 1)
                if (x < w - 1) stack.addLast(i + 1)
                if (y > 0) stack.addLast(i - w)
                if (y < h - 1) stack.addLast(i + w)
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    private fun colorClose(a: Int, b: Int, tolerance: Int): Boolean {
        if (android.graphics.Color.alpha(a) == 0) return false
        return kotlin.math.abs(android.graphics.Color.red(a) - android.graphics.Color.red(b)) <= tolerance &&
            kotlin.math.abs(android.graphics.Color.green(a) - android.graphics.Color.green(b)) <= tolerance &&
            kotlin.math.abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b)) <= tolerance
    }

    /**
     * Re-strokes every path in [vector] with the user's colour and clears the tint
     * so the stroke colour shows through. Stroke width is normalised to 1f on a
     * 48-unit viewport.
     */
    private fun recolorVectorStrokes(vector: ImageVectorDrawable) {
        val stroke = vector.viewportHeight / 48 // 1F at 48
        vector.root.editPaths(stroke, SolidColor(Color.Unspecified), SolidColor(Color(options.color)))
        vector.tintColor = Color.Unspecified
    }

    private fun colorizeVector(vectorIcon: ImageVectorDrawable): ImageVectorDrawable {
        vectorIcon.root.editPathColors(SolidColor(Color.Unspecified), SolidColor(Color(options.color)))
        vectorIcon.tintColor = Color.Unspecified

        return vectorIcon
    }

    private fun applicationShouldBeSkipped(app: PackageInfoStruct): Boolean {
        return !options.override && app.createdIcon != null
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun fixAdaptiveIconSize(adaptiveIconDrawable: AdaptiveIconDrawable) {
        val vector = adaptiveIconDrawable.foregroundVectorOrNull()
        vector?.resizeAndCenter()?.applyAndRemoveGroup()?.scaleAtCenter(adaptiveIconScale)
    }
}