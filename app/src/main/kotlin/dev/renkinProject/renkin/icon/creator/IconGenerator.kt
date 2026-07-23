package dev.renkinProject.renkin.icon.creator

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
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
import dev.renkinProject.renkin.constants.SuppressSameParameterValue
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.data.Source
import dev.renkinProject.renkin.data.TextType
import dev.renkinProject.renkin.drawable.ADAPTIVE_ICON_SCALE
import dev.renkinProject.renkin.drawable.BaseTextDrawable
import dev.renkinProject.renkin.drawable.BitmapIconDrawable
import dev.renkinProject.renkin.drawable.ForegroundIconDrawable
import dev.renkinProject.renkin.drawable.IconPackDrawable
import dev.renkinProject.renkin.drawable.ImageVectorDrawable
import dev.renkinProject.renkin.drawable.InsetIconDrawable
import dev.renkinProject.renkin.drawable.ResourceDrawable
import dev.renkinProject.renkin.drawable.foregroundVectorOrNull
import dev.renkinProject.renkin.drawable.toSafeBitmapOrNull
import dev.renkinProject.renkin.data.IconPackFallback
import dev.renkinProject.renkin.drawable.haveMonochrome
import dev.renkinProject.renkin.drawable.isAdaptiveIconDrawable
import dev.renkinProject.renkin.drawable.shrinkIfBiggerThan
import dev.renkinProject.renkin.extension.changeBackgroundColor
import dev.renkinProject.renkin.extension.emptyLike
import dev.renkinProject.renkin.extension.translated
import dev.renkinProject.renkin.extension.newArgbBitmap
import dev.renkinProject.renkin.extension.removeBackground
import dev.renkinProject.renkin.extension.scaleFromCenter
import dev.renkinProject.renkin.icon.parser.IconParser
import dev.renkinProject.renkin.packages.ApplicationManager
import dev.renkinProject.renkin.packages.PackageInfoStruct
import dev.renkinProject.renkin.drawable.toImageVectorDrawable
import dev.renkinProject.renkin.util.Log
import dev.renkinProject.renkin.packages.PackageVersion
import dev.renkinProject.renkin.vector.PathConverter.Companion.toNodes
import dev.renkinProject.renkin.vector.VectorEditor.Companion.applyAndRemoveGroup
import dev.renkinProject.renkin.vector.VectorEditor.Companion.editPathColors
import dev.renkinProject.renkin.vector.VectorEditor.Companion.editStrokePaths
import dev.renkinProject.renkin.vector.VectorEditor.Companion.editPaths
import dev.renkinProject.renkin.vector.VectorEditor.Companion.resizeAndCenter
import dev.renkinProject.renkin.vector.VectorEditor.Companion.scaleAtCenter
import dev.renkinProject.renkin.vector.VectorEditor.Companion.setReferenceColorPaths
import dev.alembiconsProject.imagetracer.ImageTracer
import dev.alembiconsProject.tgCannyEdgeCompose.CannyEdgeDetector
import dev.alembiconsProject.tgCannyEdgeCompose.DetectionOptions

internal fun previewScaleToBakeForShape(icon: IconPackDrawable, shaped: Boolean): Float =
    if (shaped) (icon as? BitmapIconDrawable)?.previewScale ?: 1f else 1f

class IconGenerator(
    private val ctx: Context,
    private val options: GenerationOptions,
    private val primaryIconPackApplications: IconPackContainer,
    private val secondaryIconPackApplications: IconPackContainer,
    // The classic fallback styling applied to apps neither pack themes so they inherit a uniform
    // look instead of staying raw (issue #121). Empty = no fallback. [fallbackPackName] is the pack
    // those drawables (iconback/mask/upon) load from.
    private val primaryFallback: IconPackFallback = IconPackFallback(),
    private val fallbackPackName: String = ""
) {
    private val adaptiveIconScale = ADAPTIVE_ICON_SCALE
    private val appMan by lazy { ApplicationManager(ctx) }
    private val materialYouPackSupport = mutableMapOf<String, Boolean>()

    // Colorize blend for bitmap icons: SRC_IN replaces the icon's colours with the picked one (flat
    // fill), MULTIPLY tints them (mixes with the original). Vectors always recolour flat regardless.
    private val colorizeMode
        get() = if (options.colorizeFlat && !options.colorizeMonochrome) {
            PorterDuff.Mode.SRC_IN
        } else PorterDuff.Mode.MULTIPLY
    private val colorizeColor
        get() = if (options.colorizeInverse) invertArgb(options.color) else options.color

    fun generateIcon(application: PackageInfoStruct,
                     onUpdate: (application: PackageInfoStruct, icon: IconPackDrawable?, sourcePackName: String) -> Unit) {
        generateIcons(listOf(application)) { app, icon, _, source -> onUpdate(app, icon, source) }
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

        // A malformed pack drawable must not crash the preview — fall back to "no icon".
        val icon = try {
            generateIcon(
                application,
                options.primarySource,
                options.primaryImageEdit,
                options.primaryTextType,
                primaryIconPackApplications,
                customIcon
            )?.let { applyAdjustments(it) }
        } catch (e: Exception) {
            Log.error("IconGenerator", "Failed to generate icon for ${application.packageName}", e)
            null
        }

        onUpdate(application, icon)
    }

    fun generateIcons(applications: List<PackageInfoStruct>
                      , onUpdate: (application: PackageInfoStruct, icon: IconPackDrawable?, isFallback: Boolean, sourcePackName: String) -> Unit) {
        if (options.primarySource == Source.NONE) {
            return
        }

        for (app in applications) {
            if (applicationShouldBeSkipped(app)) {
                continue
            }

            // A malformed drawable in one (third-party) pack must not abort the whole refresh.
            // Skip the offending app, leaving its current icon untouched, and carry on.
            try {
                val primary = generateIcon(
                    app,
                    options.primarySource,
                    options.primaryImageEdit,
                    options.primaryTextType,
                    primaryIconPackApplications
                )

                // The bulk path applies the adjustments too: with preference-driven options only
                // the pack-wide outline can differ from the no-op defaults, but every icon the
                // refresh produces (fallback-styled ones included) must carry it.
                if (primary != null) {
                    onUpdate(app, applyAdjustments(primary), false, sourcePackNameFor(options.primarySource, primaryIconPackApplications))
                } else {
                    val secondary = generateIcon(
                        app,
                        options.secondarySource,
                        options.secondaryImageEdit,
                        options.secondaryTextType,
                        secondaryIconPackApplications
                    )

                    if (secondary != null) {
                        onUpdate(app, applyAdjustments(secondary), false, sourcePackNameFor(options.secondarySource, secondaryIconPackApplications))
                    } else {
                        // Neither pack themes this app — give it the primary pack's fallback styling.
                        // The result isn't a real pack icon, so it carries no source pack.
                        val fallback = generateFallback(app)?.let { applyAdjustments(it) }
                        onUpdate(app, fallback, fallback != null, "")
                    }
                }
            } catch (e: Exception) {
                Log.error("IconGenerator", "Failed to generate icon for ${app.packageName}", e)
            }
        }
    }

    // The pack an icon should be credited to: only icon-pack sources count, the others
    // (app icon, app name) don't come from a pack and so report an empty source.
    private fun sourcePackNameFor(source: Source, iconPack: IconPackContainer): String =
        if (source == Source.ICON_PACK) iconPack.iconPackName else ""

    /**
     * Composites the primary pack's classic fallback styling onto [app]'s own icon so an app
     * neither pack themes still inherits the pack's uniform frame: draw the iconback, the original
     * icon scaled down to sit inside it, cut everything outside the iconmask shape, then draw the
     * iconupon overlay. Exported as a plain bitmap so the baked pack shape survives (the launcher
     * doesn't re-mask legacy icons). Returns null when the pack declares no fallback.
     */
    /** Public entry for previewing the fallback styling on a sample app (Options card). */
    fun fallbackIcon(app: PackageInfoStruct): IconPackDrawable? = generateFallback(app)

    private fun generateFallback(app: PackageInfoStruct): IconPackDrawable? {
        if (primaryFallback.isEmpty) return null
        val packName = fallbackPackName
        if (packName.isEmpty()) return null

        fun load(name: String?): Bitmap? = name?.let { appMan.getDrawableByName(packName, it)?.toSafeBitmapOrNull() }

        // Pick a back deterministically per app so the choice is stable across rebuilds.
        val back = primaryFallback.backs
            .takeIf { it.isNotEmpty() }
            ?.let { it[(app.packageName.hashCode() and Int.MAX_VALUE) % it.size] }
            ?.let { load(it) }
        val mask = load(primaryFallback.mask)
        val upon = load(primaryFallback.upon)
        val original = getIconBitmap(app.icon) ?: return null

        val size = back?.width ?: original.width
        val full = Rect(0, 0, size, size)
        val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        if (back != null) canvas.drawBitmap(back, null, full, null)

        // The app's own icon, scaled down (by the pack's factor) to sit inside the frame.
        val inner = (size * primaryFallback.scale).toInt().coerceAtLeast(1)
        val offset = (size - inner) / 2
        canvas.drawBitmap(original, null, Rect(offset, offset, offset + inner, offset + inner), Paint(Paint.FILTER_BITMAP_FLAG))

        // Clip to the pack's shape: DST_OUT removes wherever the mask is opaque (outside the shape).
        if (mask != null) {
            val paint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
            canvas.drawBitmap(mask, null, full, paint)
        }

        if (upon != null) canvas.drawBitmap(upon, null, full, null)

        return BitmapIconDrawable(ctx.resources, result)
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

        if (imageEdit == ImageEdit.COLORIZE && !options.colorizeMonochrome) {
            modifierVector(icon)?.let { vector ->
                vector.root.setReferenceColorPaths(SolidColor(Color(colorizeColor)))
                vector.tintColor = Color.Unspecified
                return vector
            }
        }

        if (icon is ImageVectorDrawable) {
            val copy = ImageVectorDrawable(icon.toImageVector())
            return when (imageEdit) {
                ImageEdit.NONE -> icon
                ImageEdit.COLORIZE -> {
                    if (options.colorizeMonochrome) {
                        colorizeImage(copy.toBitmap(), null, colorizeMode)
                    } else {
                        // Recolour while keeping each path's fill-vs-stroke nature (#117),
                        // unlike colorizeVector which forces everything to a stroke.
                        copy.root.setReferenceColorPaths(SolidColor(Color(colorizeColor)))
                        copy.tintColor = Color.Unspecified
                        copy
                    }
                }
                ImageEdit.PATH -> generatePathTracing(copy.toBitmap(), null)
                ImageEdit.EDGE -> generateCannyEdgeDetection(copy.toBitmap(), null)
                ImageEdit.REMOVE_BACKGROUND -> generateRemoveBackground(copy.toBitmap())
            }
        }

        // Non-vector icons rasterise, then run through the shared modifier dispatch
        // (NONE is already handled by the early return at the top of this method).
        val modified = generateImage(icon.toBitmap(), null, imageEdit, colorizeMode)
        return preserveBitmapPresentation(icon, modified)
    }

    /**
     * Destructive bitmap modifiers may return a new bitmap or traced vector, but an adaptive
     * Material You source still needs its adaptive export flag and safe-zone preview zoom.
     * Ordinary bitmaps have the defaults and keep their original vector/bitmap result.
     */
    private fun preserveBitmapPresentation(
        source: IconPackDrawable,
        modified: IconPackDrawable
    ): IconPackDrawable {
        val bitmapSource = source as? BitmapIconDrawable ?: return modified
        if (!bitmapSource.isAdaptiveIcon() && bitmapSource.previewScale == 1f) return modified
        return BitmapIconDrawable(
            ctx.resources,
            modified.toBitmap(),
            exportAsAdaptiveIcon = bitmapSource.isAdaptiveIcon(),
            previewScale = bitmapSource.previewScale
        )
    }

    fun colorizeFromIconPack(iconPackName: String, icon: ResourceDrawable): IconPackDrawable? {
        val bitmapIcon = getIconBitmap(icon.drawable) ?: return null
        val parsedIcon = exportIconPackXML(iconPackName, icon)

        return if (options.primaryImageEdit == ImageEdit.COLORIZE)
            colorizeImage(bitmapIcon, parsedIcon, colorizeMode)
        else
            getDefaultIcon(
                bitmapIcon,
                parsedIcon,
                preserveAdaptiveAppearance = packChangesWithMaterialYouColors(iconPackName)
            )
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
        val resIcon = customIcon ?: iconPack.getApplicationIcon(application.toInstalledApplication()) ?: return null

        val bitmapIcon = getIconBitmap(resIcon.drawable) ?: return null
        val parsedIcon = exportIconPackXML(iconPack.iconPackName, resIcon)

        return generateImage(
            bitmapIcon,
            parsedIcon,
            imageEdit,
            colorizeMode,
            preserveAdaptiveAppearance = packChangesWithMaterialYouColors(iconPack.iconPackName)
        )
    }

    private fun generateImageFromApplication(
        application: PackageInfoStruct,
        imageEdit: ImageEdit): IconPackDrawable? {

        // Material You variant: recolor the app's own <monochrome> layer directly — tint it with
        // the chosen foreground over the chosen background. No path-tracing (that produces line
        // art, issue #81), so this only runs for the plain (NONE) modifier.
        if (options.applicationIconVariant == ApplicationIconVariant.MATERIAL_YOU &&
            imageEdit == ImageEdit.NONE && hasMonochromeLayer(application)) {
            return generateMaterialYou(application)
        }

        if (options.applicationIconVariant == ApplicationIconVariant.MATERIAL_YOU) {
            // Apps without an official layer still get a clearly-labelled Renkin-generated
            // approximation. Rasterising the complete launcher icon preserves its optical size;
            // getAppIconBitmap extracts the adaptive foreground and would enlarge it here.
            val bitmapIcon = application.icon.shrinkIfBiggerThan(500) ?: return null
            val generated = generateMaterialYouFromOriginal(bitmapIcon)
            return if (imageEdit == ImageEdit.NONE) generated
            else applyModifierInner(generated, imageEdit)
        }
        val bitmapIcon = getAppIconBitmap(application) ?: return null
        if (options.applicationIconVariant == ApplicationIconVariant.MONOCHROME) {
            // This is deliberately based on the regular launcher artwork, not the optional
            // Material You layer: every app is supported and its original design stays intact.
            return generateImage(toMonochrome(bitmapIcon), null, imageEdit, colorizeMode)
        }
        val parsedIcon = parseApplicationIcon(application)

        return generateImage(bitmapIcon, parsedIcon, imageEdit, colorizeMode)
    }

    /** True when [application]'s launcher icon ships a Material You `<monochrome>` layer (API 33+). */
    private fun hasMonochromeLayer(application: PackageInfoStruct): Boolean {
        val icon = application.icon
        return icon.isAdaptiveIconDrawable() && (icon as AdaptiveIconDrawable).haveMonochrome()
    }

    private fun generateMaterialYou(application: PackageInfoStruct): IconPackDrawable? {
        val icon = application.icon
        if (!icon.isAdaptiveIconDrawable()) return null
        // Read the monochrome layer directly — it may be any Drawable type (often an InsetDrawable
        // wrapping a vector), so we can't rely on getAppIconBitmap's Bitmap/Vector-only path.
        val mono = (icon as AdaptiveIconDrawable).monochrome?.mutate() ?: return null

        // Rasterise the raw silhouette at the layer's natural size: the monochrome is already a
        // full-bleed 108dp layer with its artwork in the inner safe zone, which is exactly what an
        // adaptive foreground expects — so (unlike the old flat export) we must NOT scale it up, or
        // the launcher would render it oversized.
        val size = 432
        mono.setTintList(null)
        mono.setBounds(0, 0, size, size)
        val mask = newArgbBitmap(size, size) { mono.draw(it) }

        // Opaque results stay adaptive so the launcher applies its mask. A translucent background
        // takes the flat path below because an adaptive export would insert the pack background.
        return materialYouDrawable(recolorMaterialYouLayer(mask))
    }

    /**
     * Paints [options.color] through the monochrome alpha mask (SRC_IN keeps the silhouette's
     * shape, replaces its colour) and composites it over [options.bgColor]. The background is
     * always applied — unlike [colorizeBitmap] — because the mask itself is transparent.
     */
    private fun recolorMaterialYouLayer(mask: Bitmap): Bitmap {
        return recolorMaterialYouMask(mask, options.color, options.bgColor)
    }

    /**
     * Creates an unofficial two-colour Material You approximation from regular icon artwork.
     * Transparent and light pixels become the selected background; dark artwork becomes the
     * foreground. This keeps the simple approximation used before foreground extraction trials.
     */
    private fun generateMaterialYouFromOriginal(icon: Bitmap): BitmapIconDrawable {
        val pixels = IntArray(icon.width * icon.height)
        icon.getPixels(pixels, 0, icon.width, 0, 0, icon.width, icon.height)

        val fgA = android.graphics.Color.alpha(options.color)
        val fgR = android.graphics.Color.red(options.color)
        val fgG = android.graphics.Color.green(options.color)
        val fgB = android.graphics.Color.blue(options.color)

        for (i in pixels.indices) {
            val source = pixels[i]
            val sourceAlpha = android.graphics.Color.alpha(source) / 255f
            val luminance = (
                0.2126f * android.graphics.Color.red(source) +
                    0.7152f * android.graphics.Color.green(source) +
                    0.0722f * android.graphics.Color.blue(source)
                ) / 255f
            val coverage = sourceAlpha * (1f - luminance)
            pixels[i] = android.graphics.Color.argb(
                (fgA * coverage).toInt().coerceIn(0, 255), fgR, fgG, fgB
            )
        }

        val foreground = Bitmap.createBitmap(icon.width, icon.height, Bitmap.Config.ARGB_8888)
        foreground.setPixels(pixels, 0, icon.width, 0, 0, icon.width, icon.height)

        // Adaptive launchers display only the inner 72dp of a 108dp foreground. Inset the complete
        // original icon by that ratio before export; previewScale reverses the inset in the flat
        // comparison header. Applying both once keeps Current and New at the same optical size.
        val generated = newArgbBitmap(icon.width, icon.height) { canvas ->
            canvas.drawColor(options.bgColor)
            val insetScale = 1f / adaptiveIconScale
            canvas.scale(insetScale, insetScale, icon.width / 2f, icon.height / 2f)
            canvas.drawBitmap(foreground, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        return materialYouDrawable(generated)
    }

    /**
     * An adaptive icon always receives the pack's global background during export. A translucent
     * custom background must therefore be exported as a flat bitmap or that global colour would
     * show through and replace the transparency selected by the user.
     */
    private fun materialYouDrawable(bitmap: Bitmap): BitmapIconDrawable {
        val adaptive = android.graphics.Color.alpha(options.bgColor) == 255
        return BitmapIconDrawable(
            ctx.resources,
            if (adaptive) bitmap else bitmap.scaleFromCenter(adaptiveIconScale),
            exportAsAdaptiveIcon = adaptive,
            previewScale = if (adaptive) adaptiveIconScale else 1f
        )
    }

    /** Removes hue and saturation while retaining the original icon's luminance and alpha. */
    private fun toMonochrome(icon: Bitmap): Bitmap = monochromeBitmap(icon, options.invertMonochrome)

    /**
     * Single dispatch point for applying an [ImageEdit] to a (bitmap, parsedIcon)
     * pair. Adding a new modifier means adding one [ImageEdit] entry and one branch
     * here — every "generate from a source" path routes through this method.
     */
    private fun generateImage(
        bitmapIcon: Bitmap,
        parsedIcon: Drawable?,
        imageEdit: ImageEdit,
        mode: PorterDuff.Mode,
        preserveAdaptiveAppearance: Boolean = false
    ): IconPackDrawable {
        if (imageEdit == ImageEdit.NONE && preserveAdaptiveAppearance && !options.themed) {
            val adaptiveIcon = parsedIcon as? AdaptiveIconDrawable
            renderAdaptivePackIcon(adaptiveIcon)?.let { return it }
        }

        if (parsedIcon != null && parsedIcon.isAdaptiveIconDrawable()) {
            fixAdaptiveIconSize(parsedIcon as AdaptiveIconDrawable)
        }

        return when (imageEdit) {
            ImageEdit.NONE -> getDefaultIcon(bitmapIcon, parsedIcon)
            ImageEdit.PATH -> generatePathTracing(bitmapIcon, parsedIcon)
            ImageEdit.EDGE -> generateCannyEdgeDetection(bitmapIcon, parsedIcon)
            ImageEdit.COLORIZE -> colorizeImage(bitmapIcon, parsedIcon, mode)
            ImageEdit.REMOVE_BACKGROUND -> generateRemoveBackground(bitmapIcon)
        }
    }

    /** Strips the flat background colour around the icon, keeping the cleaned glyph. */
    private fun generateRemoveBackground(bitmapIcon: Bitmap): IconPackDrawable {
        return getDefaultBitmapIcon(bitmapIcon.removeBackground(options.bgRemovalTolerance))
    }

    private fun generateText(applicationName: String, textType: TextType): IconPackDrawable {
        val size = 256
        val strokeWidth = size / 48F
        val textGenerator = LetterGenerator(ctx, options.textFontPath)

        // Custom text only applies to the CUSTOM type; the case transform applies everywhere.
        val text = when (options.textCase) {
            TextCase.UPPER -> applicationName.uppercase()
            TextCase.LOWER -> applicationName.lowercase()
            TextCase.AS_IS -> applicationName
        }

        val newIcon = when(textType) {
            TextType.FULL_NAME -> {
                val draw = textGenerator.generateAppName(text, options.color, size)
                createVectorForMultiLineText(draw as BaseTextDrawable, options.color, size)
            }
            TextType.ONE_LETTER -> {
                val draw = textGenerator.generateFirstLetter(text, options.color, strokeWidth, size)
                createVectorForText(draw as BaseTextDrawable, options.color, strokeWidth, size)
            }
            TextType.TWO_LETTERS -> {
                val draw = textGenerator.generateTwoLetters(text, options.color, strokeWidth, size)
                createVectorForText(draw as BaseTextDrawable, options.color, strokeWidth, size)
            }
            TextType.CUSTOM -> {
                val custom = when (options.textCase) {
                    TextCase.UPPER -> options.textCustom.uppercase()
                    TextCase.LOWER -> options.textCustom.lowercase()
                    TextCase.AS_IS -> options.textCustom
                }.trim().ifEmpty { text }
                if (custom.length <= 3) {
                    val draw = textGenerator.generateExact(custom, options.color, strokeWidth, size)
                    createVectorForText(draw as BaseTextDrawable, options.color, strokeWidth, size)
                } else {
                    val draw = textGenerator.generateAppName(custom, options.color, size)
                    createVectorForMultiLineText(draw as BaseTextDrawable, options.color, size)
                }
            }
        }

        if (options.themed)
            return vectorToInset(newIcon.toImageVectorDrawable())

        return newIcon.toImageVectorDrawable()
    }

    private fun parseApplicationIcon(application: PackageInfoStruct): Drawable? {
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

            if (parsedIcon.haveMonochrome() && options.materialYou) {
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
        // Deterministic tracing: the library's default 16-colour palette seeds indices 8..15
        // with kotlin.random.Random, so the same bitmap could trace to slightly different
        // geometry on every regeneration. Exactly 8 colours uses only the fixed RGB-corner
        // seeds (the random fill loop never runs) while the quantization cycles still adapt
        // them to the image.
        val imageVector = ImageTracer.imageToVector(
            bitmapIcon.asImageBitmap(),
            ImageTracer.TracingOptions().apply { numberOfColors = 8 }
        )

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

            if (PackageVersion.is33OrMore() && adaptiveIcon.monochrome != null && options.materialYou) {
                if (adaptiveIcon.monochrome is BitmapDrawable || adaptiveIcon.monochrome is VectorDrawable) {
                    newIcon = ForegroundIconDrawable(adaptiveIcon.monochrome!!)
                }
            }
        }

        return newIcon.shrinkIfBiggerThan(maxSize)
    }

    private fun getDefaultIcon(
        bitmapIcon: Bitmap,
        parsedIcon: Drawable?,
        preserveAdaptiveAppearance: Boolean = false
    ): IconPackDrawable {
        if (parsedIcon != null) {
            if (parsedIcon.isAdaptiveIconDrawable()) {
                parsedIcon as AdaptiveIconDrawable
                if (preserveAdaptiveAppearance && !options.themed) {
                    renderAdaptivePackIcon(parsedIcon)?.let { return it }
                }
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

            if (PackageVersion.is33OrMore() && options.materialYou) {
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
        val coloredIcon = icon.emptyLike()
        val paint = Paint()

        paint.colorFilter = PorterDuffColorFilter(options.color, mode)
        val canvas = Canvas(coloredIcon)
        if (options.themed) canvas.scale(0.5f, 0.5f, icon.width * 0.5f, icon.height * 0.5f)
        canvas.drawBitmap(icon, 0F, 0F, paint)

        val result = addBackground(coloredIcon)
        return if (options.colorizeInverse) invertBitmapColors(result) else result
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

    private fun packChangesWithMaterialYouColors(iconPackName: String): Boolean =
        materialYouPackSupport.getOrPut(iconPackName) {
            appMan.changesWithMaterialYouColors(iconPackName)
        }

    private fun renderAdaptivePackIcon(icon: AdaptiveIconDrawable?): BitmapIconDrawable? {
        icon ?: return null
        val rendered = icon.toSafeBitmapOrNull(
            ADAPTIVE_PACK_RENDER_SIZE,
            ADAPTIVE_PACK_RENDER_SIZE
        ) ?: return null
        // Draw the browser asset from the vector/adaptive source at its final size. Scaling
        // the 500 px raster down later blurred Lawnicons' one-pixel line details.
        val browserPreview = icon.toSafeBitmapOrNull(
            ADAPTIVE_PACK_BROWSER_PREVIEW_SIZE,
            ADAPTIVE_PACK_BROWSER_PREVIEW_SIZE
        )
        return BitmapIconDrawable(
            ctx.resources,
            rendered,
            browserPreviewBitmap = browserPreview
        )
    }

    private fun parseIconPackXML(iconPackName: String, iconDrawable: ResourceDrawable): Drawable? {
        if (!isVectorDrawable(iconDrawable.drawable)) return null

        val res = appMan.getResources(iconPackName) ?: return null
        val icon = IconParser.parseDrawable(res, iconDrawable.drawable, iconDrawable.resourceId)

        if (!icon.isAdaptiveIconDrawable()) return null

        val vectorIcon = icon.foregroundVectorOrNull() ?: return null

        val stroke = vectorIcon.viewportHeight / 48 //1F at 48
        vectorIcon.root.editStrokePaths(stroke)

        return icon
    }

    private fun colorizeImage(bitmapIcon: Bitmap, parsedIcon: Drawable?, mode: PorterDuff.Mode): IconPackDrawable {
        if (options.colorizeMonochrome) {
            // Reuse the Application icon → Monochrome result exactly. The Colorize colour is
            // intentionally irrelevant in this mode; Solid fill and normal tint remain separate.
            return getDefaultBitmapIcon(monochromeBitmap(bitmapIcon, options.colorizeInverse))
        }
        return when (parsedIcon) {
            is InsetIconDrawable -> {
                parsedIcon.newDrawable(colorizeImage(bitmapIcon, parsedIcon.drawable, mode))
            }
            is ImageVectorDrawable -> colorizeVector(parsedIcon)
            else -> BitmapIconDrawable(ctx.resources, colorizeBitmap(bitmapIcon, mode))
        }
    }

    /**
     * Applies the per-icon Modifier-tab adjustments — position offset, then scale — on top of an
     * already-built icon. No-op with the defaults, so it's safe to run on every generation path.
     * Vectors keep scale/position as vector groups; bitmap sources use the raster path. A vector
     * is rasterised only when a later outline or shape operation requires pixels. Bitmap results
     * carry over the source's adaptive-export flag and preview zoom (e.g. Material You).
     */
    private fun applyAdjustments(icon: IconPackDrawable): IconPackDrawable {
        val offset = options.iconOffsetX != 0f || options.iconOffsetY != 0f
        val shaped = options.iconShape != IconShape.NONE
        val outlined = options.outlineMode != OutlineMode.NONE
        if (!offset && options.iconScale == 1f && !shaped && !outlined) return icon

        // Keep vector-safe operations as groups. If no raster-only step follows, the result
        // remains XML all the way through DB persistence and pack export. When outline/shape
        // follows, rasterise only once after vector scale/position so enlarged SVG pixels are
        // never resampled from an earlier 256px bitmap.
        val vectorAdjusted = modifierVector(icon)?.withModifierTransform(
            options.iconScale,
            options.iconOffsetX,
            options.iconOffsetY
        )
        if (vectorAdjusted != null && !shaped && !outlined) return vectorAdjusted

        var bitmap = vectorAdjusted?.toModifierBitmap() ?: icon.toBitmap()
        if (bitmap.width <= 0 || bitmap.height <= 0) return icon

        val source = icon as? BitmapIconDrawable
        // A shape is exported as a legacy bitmap and therefore cannot keep previewScale. Bake
        // the Material You safe-zone zoom into the pixels before shaping so its optical size
        // stays identical to the unshaped preview. Normal bitmaps use 1f and are untouched.
        val previewScaleToBake = previewScaleToBakeForShape(icon, shaped)
        if (previewScaleToBake != 1f) {
            bitmap = bitmap.scaleFromCenter(previewScaleToBake)
        }

        // Vector adjustments above are already consumed; bitmap sources keep the old path.
        if (vectorAdjusted == null && offset) {
            bitmap = bitmap.translated(options.iconOffsetX * bitmap.width, options.iconOffsetY * bitmap.height)
        }
        if (vectorAdjusted == null && options.iconScale != 1f) {
            val src = bitmap
            bitmap = newArgbBitmap(src.width, src.height) { canvas ->
                canvas.scale(options.iconScale, options.iconScale, src.width / 2f, src.height / 2f)
                // Bilinear filtering: a null paint may fall back to nearest-neighbour sampling
                // on software canvases, giving jagged diagonals on scaled icons.
                canvas.drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
            }
        }
        if (outlined) {
            // Before the shape: the contour hugs the icon itself; a shape crop afterwards
            // still trims anything the outline pushed past the shape's edge.
            // The width option is calibrated to the 256px working size; scale it with the
            // actual bitmap so the outline looks the same at any source resolution.
            val preOutline = bitmap
            bitmap = IconOutline.apply(
                preOutline,
                options.outlineMode,
                options.outlineWidth * maxOf(preOutline.width, preOutline.height) / 256f,
                options.outlineColor
            )
            // The eraser: inside the painted areas the outline step is undone, the icon stays.
            options.outlineEraseMask?.let { mask ->
                bitmap = IconOutline.eraseOutline(bitmap, preOutline, mask)
            }
        }
        if (shaped) {
            bitmap = applyShape(bitmap)
        }

        return BitmapIconDrawable(
            ctx.resources,
            bitmap,
            // A shaped icon IS its shape — exporting it adaptive would let the launcher mask
            // it again (a squircle inside the launcher's circle). Ship it as a legacy bitmap.
            exportAsAdaptiveIcon = if (shaped) false else source?.isAdaptiveIcon() ?: false,
            previewScale = if (shaped) 1f else source?.previewScale ?: 1f
        )
    }

    /** A detached vector representation, including an InsetIconDrawable's canvas margins. */
    private fun modifierVector(icon: IconPackDrawable): ImageVectorDrawable? {
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

    /**
     * The Modifier tab's shape step. The icon itself stays untouched (its size is the
     * separate Icon scale adjustment) — what [GenerationOptions.iconShapeScale] scales is
     * THE SHAPE, around the centre: a smaller shape crops deeper into the icon, a larger one
     * clips just the corners. Crop mode masks the icon with the shape; plate mode fills the
     * shape with [GenerationOptions.bgColor] behind the icon first. Either way the composite
     * is masked by the shape at the end, so nothing pokes out of curvy shapes (pebble,
     * sunny). Anti-aliased SRC_IN masking — Canvas.clipPath would leave jagged edges.
     */
    private fun applyShape(src: Bitmap): Bitmap {
        val size = maxOf(src.width, src.height, 256)
        val sizeF = size.toFloat()
        val path = IconShapes.path(options.iconShape, sizeF * options.iconShapeScale) ?: return src
        // Keep the scaled shape centred on the canvas.
        path.offset(sizeF * (1f - options.iconShapeScale) / 2f, sizeF * (1f - options.iconShapeScale) / 2f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        val content = newArgbBitmap(size, size) { canvas ->
            if (!options.iconShapeCrop) {
                paint.color = options.bgColor
                canvas.drawPath(path, paint)
                paint.color = -0x1
            }
            canvas.drawBitmap(src, null, RectF(0f, 0f, sizeF, sizeF), paint)
        }

        return newArgbBitmap(size, size) { canvas ->
            val mask = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            canvas.drawPath(path, mask)
            mask.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            canvas.drawBitmap(content, 0f, 0f, mask)
        }
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
        vectorIcon.root.editPathColors(SolidColor(Color.Unspecified), SolidColor(Color(colorizeColor)))
        vectorIcon.tintColor = Color.Unspecified

        return vectorIcon
    }

    private fun applicationShouldBeSkipped(app: PackageInfoStruct): Boolean {
        // A refresh only fills empty slots and replaces its own unsaved output (isRefreshMade).
        // Hand-picked icons and anything already built/saved are locked — unless override is on.
        return !options.override && app.createdIcon != null && !app.isRefreshMade
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun fixAdaptiveIconSize(adaptiveIconDrawable: AdaptiveIconDrawable) {
        val vector = adaptiveIconDrawable.foregroundVectorOrNull()
        vector?.resizeAndCenter()?.applyAndRemoveGroup()?.scaleAtCenter(adaptiveIconScale)
    }

    private companion object {
        const val ADAPTIVE_PACK_RENDER_SIZE = 500
        const val ADAPTIVE_PACK_BROWSER_PREVIEW_SIZE = 96
    }
}

internal fun invertArgb(color: Int): Int = android.graphics.Color.argb(
    android.graphics.Color.alpha(color),
    255 - android.graphics.Color.red(color),
    255 - android.graphics.Color.green(color),
    255 - android.graphics.Color.blue(color)
)

internal fun invertBitmapColors(icon: Bitmap): Bitmap {
    val pixels = IntArray(icon.width * icon.height)
    icon.getPixels(pixels, 0, icon.width, 0, 0, icon.width, icon.height)
    for (index in pixels.indices) pixels[index] = invertArgb(pixels[index])
    return Bitmap.createBitmap(pixels, icon.width, icon.height, Bitmap.Config.ARGB_8888).apply {
        density = icon.density
    }
}

internal fun monochromeBitmap(icon: Bitmap, invert: Boolean): Bitmap {
    val pixels = IntArray(icon.width * icon.height)
    icon.getPixels(pixels, 0, icon.width, 0, 0, icon.width, icon.height)
    for (index in pixels.indices) {
        val source = pixels[index]
        val luminance = (
            0.213f * android.graphics.Color.red(source) +
                0.715f * android.graphics.Color.green(source) +
                0.072f * android.graphics.Color.blue(source)
            ).toInt().coerceIn(0, 255)
        val value = if (invert) 255 - luminance else luminance
        pixels[index] = android.graphics.Color.argb(android.graphics.Color.alpha(source), value, value, value)
    }
    return Bitmap.createBitmap(pixels, icon.width, icon.height, Bitmap.Config.ARGB_8888).apply {
        density = icon.density
    }
}

internal fun recolorMaterialYouMask(mask: Bitmap, foreground: Int, background: Int): Bitmap {
    val pixels = IntArray(mask.width * mask.height)
    mask.getPixels(pixels, 0, mask.width, 0, 0, mask.width, mask.height)
    for (index in pixels.indices) {
        val coverage = android.graphics.Color.alpha(pixels[index]) / 255f
        pixels[index] = compositeMaterialYouPixel(foreground, background, coverage)
    }
    return Bitmap.createBitmap(pixels, mask.width, mask.height, Bitmap.Config.ARGB_8888).apply {
        density = mask.density
    }
}

private fun compositeMaterialYouPixel(foreground: Int, background: Int, coverage: Float): Int {
    val fgAlpha = android.graphics.Color.alpha(foreground) / 255f * coverage
    val bgAlpha = android.graphics.Color.alpha(background) / 255f
    val outAlpha = fgAlpha + bgAlpha * (1f - fgAlpha)
    if (outAlpha <= 0f) return android.graphics.Color.TRANSPARENT

    fun channel(fg: Int, bg: Int): Int = (
        (fg * fgAlpha + bg * bgAlpha * (1f - fgAlpha)) / outAlpha
        ).toInt().coerceIn(0, 255)

    return android.graphics.Color.argb(
        (outAlpha * 255f).toInt().coerceIn(0, 255),
        channel(android.graphics.Color.red(foreground), android.graphics.Color.red(background)),
        channel(android.graphics.Color.green(foreground), android.graphics.Color.green(background)),
        channel(android.graphics.Color.blue(foreground), android.graphics.Color.blue(background))
    )
}
