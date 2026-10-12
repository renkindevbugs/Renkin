package dev.renkinProject.renkin.icon.creator

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.VectorDrawable
import android.os.Build
import android.util.LruCache
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.renkinProject.renkin.constants.SuppressSameParameterValue
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.data.Source
import dev.renkinProject.renkin.data.TextType
import dev.renkinProject.renkin.drawable.ADAPTIVE_ICON_SCALE
import dev.renkinProject.renkin.drawable.AdaptiveIconPackDrawable
import dev.renkinProject.renkin.drawable.MaterialYouPackEditState
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
import dev.renkinProject.renkin.drawable.newAdaptiveIconDrawable
import dev.renkinProject.renkin.drawable.shrinkIfBiggerThan
import dev.renkinProject.renkin.extension.newArgbBitmap
import dev.renkinProject.renkin.extension.scaleFromCenter
import dev.renkinProject.renkin.icon.parser.IconParser
import dev.renkinProject.renkin.packages.IconPackCatalog
import dev.renkinProject.renkin.packages.PackageResourceResolver
import dev.renkinProject.renkin.packages.PackageInfoStruct
import dev.renkinProject.renkin.drawable.toImageVectorDrawable
import dev.renkinProject.renkin.util.Log
import dev.renkinProject.renkin.packages.PackageVersion
import dev.renkinProject.renkin.vector.PathConverter.Companion.toNodes
import dev.renkinProject.renkin.vector.VectorEditor.Companion.applyAndRemoveGroup
import dev.renkinProject.renkin.vector.VectorEditor.Companion.editStrokePaths
import kotlinx.coroutines.CancellationException
import dev.renkinProject.renkin.vector.VectorEditor.Companion.resizeAndCenter
import dev.renkinProject.renkin.vector.VectorEditor.Companion.scaleAtCenter
import dev.renkinProject.renkin.vector.VectorEditor.Companion.scaleStrokePaths
import dev.renkinProject.renkin.vector.VectorEditor.Companion.setReferenceColorPaths

internal fun previewScaleToBakeForShape(icon: IconPackDrawable, shaped: Boolean): Float =
    if (shaped) (icon as? BitmapIconDrawable)?.previewScale ?: 1f else 1f

internal fun effectiveMaterialYouPackStrokeScale(modifierScale: Float): Float =
    modifierScale.coerceIn(0.5f, 2f)

internal fun shouldNormalizeAdaptiveForeground(preserveAdaptiveAppearance: Boolean): Boolean =
    !preserveAdaptiveAppearance

class IconGenerator(
    private val ctx: Context,
    private val options: GenerationOptions,
    private val primaryIconPackApplications: IconPackContainer,
    private val secondaryIconPackApplications: IconPackContainer,
    // The classic fallback styling applied to apps neither pack themes so they inherit a uniform
    // look instead of staying raw (issue #121). Empty = no fallback. [fallbackPackName] is the pack
    // those drawables (iconback/mask/upon) load from.
    private val primaryFallback: IconPackFallback = IconPackFallback(),
    private val fallbackPackName: String = "",
    private val iconPackCatalog: IconPackCatalog = IconPackCatalog(ctx),
    private val ownedTypeface: Typeface? = null
) {
    private val source = options.source
    private val modifiers = options.modifiers
    private val export = options.export
    private val adaptiveIconScale = ADAPTIVE_ICON_SCALE
    private val resourceResolver by lazy { PackageResourceResolver(ctx) }
    private val materialYouPackSupport = mutableMapOf<String, Boolean>()
    private val stageRenderer by lazy { IconModifierStageRenderer(ctx.resources, options) }
    private val adjustmentPipeline get() = stageRenderer.adjustments
    private val imageEditPipeline get() = stageRenderer.imageEdits
    private val fallbackBitmapCache by lazy {
        object : LruCache<String, Bitmap>(FALLBACK_BITMAP_CACHE_BYTES) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
        }
    }

    fun generateIcon(application: PackageInfoStruct,
                     onUpdate: (application: PackageInfoStruct, icon: IconPackDrawable?, sourcePackName: String) -> Unit) {
        generateIcons(listOf(application)) { app, icon, _, source -> onUpdate(app, icon, source) }
    }

    fun generateIcon(application: PackageInfoStruct,
                     customIcon: ResourceDrawable?,
                     onUpdate: (application: PackageInfoStruct, icon: IconPackDrawable?) -> Unit)  {
        if (source.primarySource == Source.NONE) {
            return
        }

        if (applicationShouldBeSkipped(application)) {
            return
        }

        onUpdate(application, generatePreview(application, customIcon, retainInput = false).icon)
    }

    fun generatePreview(
        application: PackageInfoStruct,
        customIcon: ResourceDrawable?,
        retainInput: Boolean = true
    ): IconGenerationResult {
        if (source.primarySource == Source.NONE || applicationShouldBeSkipped(application)) {
            return IconGenerationResult(null)
        }
        // A malformed pack drawable must not crash the preview — fall back to "no icon".
        return try {
            generateIcon(
                application,
                source.primarySource,
                modifiers.effect.imageEdit,
                source.primaryTextType,
                primaryIconPackApplications,
                customIcon
            )?.render(stageRenderer, retainInput) ?: IconGenerationResult(null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.error("IconGenerator", "Failed to generate icon for ${application.packageName}", e)
            IconGenerationResult(null)
        }
    }

    fun generateIcons(applications: List<PackageInfoStruct>
                      , onUpdate: (application: PackageInfoStruct, icon: IconPackDrawable?, isFallback: Boolean, sourcePackName: String) -> Unit) {
        if (source.primarySource == Source.NONE) {
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
                    source.primarySource,
                    modifiers.effect.imageEdit,
                    source.primaryTextType,
                    primaryIconPackApplications
                )

                // The bulk path applies the adjustments too: with preference-driven options only
                // the pack-wide outline can differ from the no-op defaults, but every icon the
                // refresh produces (fallback-styled ones included) must carry it.
                if (primary != null) {
                    onUpdate(app, primary.render(stageRenderer, retainInput = false).icon, false, sourcePackNameFor(source.primarySource, primaryIconPackApplications))
                } else {
                    val secondary = generateIcon(
                        app,
                        source.secondarySource,
                        source.secondaryImageEdit,
                        source.secondaryTextType,
                        secondaryIconPackApplications
                    )

                    if (secondary != null) {
                        onUpdate(app, secondary.render(stageRenderer, retainInput = false).icon, false, sourcePackNameFor(source.secondarySource, secondaryIconPackApplications))
                    } else {
                        // Neither pack themes this app — give it the primary pack's fallback styling.
                        // The result isn't a real pack icon, so it carries no source pack.
                        val fallback = generateFallback(app)?.let { applyAdjustments(it) }
                        onUpdate(app, fallback, fallback != null, "")
                    }
                }
            } catch (e: CancellationException) {
                throw e
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

        fun load(name: String?): Bitmap? {
            name ?: return null
            fallbackBitmapCache.get(name)?.let { return it }
            val bitmap = resourceResolver.getDrawableByName(packName, name)?.toSafeBitmapOrNull()
                ?: return null
            fallbackBitmapCache.put(name, bitmap)
            return bitmap
        }

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
        return imageEditPipeline.apply(icon, imageEdit)
    }

    fun colorizeFromIconPack(iconPackName: String, icon: ResourceDrawable): IconPackDrawable? {
        preservePackLayers(iconPackName, icon)?.let {
            return when (modifiers.effect.imageEdit) {
                ImageEdit.COLORIZE, ImageEdit.COLORIZE_SEGMENTS -> imageEditPipeline.applyEdit(it, modifiers.effect.imageEdit)
                else -> it
            }
        }
        val bitmapIcon = getIconBitmap(icon.drawable) ?: return null
        val changesWithMaterialYou = packChangesWithMaterialYouColors(iconPackName)
        val parsedIcon = exportIconPackXML(iconPackName, icon).let {
            if (changesWithMaterialYou) styleMaterialYouPackIcon(it) else it
        }

        return if (modifiers.effect.imageEdit == ImageEdit.COLORIZE ||
            modifiers.effect.imageEdit == ImageEdit.COLORIZE_SEGMENTS
        )
            colorizeImage(bitmapIcon, parsedIcon, modifiers.effect.colorizer.blendMode)
        else
            getDefaultIcon(
                bitmapIcon,
                parsedIcon,
                preserveAdaptiveAppearance = changesWithMaterialYou
            )
    }

    private fun generateIcon(
        application: PackageInfoStruct,
        source: Source,
        imageEdit: ImageEdit,
        textType: TextType,
        iconPack: IconPackContainer,
        customIcon: ResourceDrawable? = null
    ): IconSourceStage? {
        return when (source) {
            Source.NONE -> null
            Source.ICON_PACK -> generateImageFromIconPack(application, imageEdit, iconPack, customIcon)
            Source.APPLICATION_ICON -> generateImageFromApplication(application, imageEdit)
            Source.APPLICATION_NAME -> IconSourceStage(
                generateText(application.appName, textType), ModifierInputPipeline.ADJUSTMENTS_ONLY, imageEdit
            )
        }
    }

    private fun generateImageFromIconPack(
        application: PackageInfoStruct,
        imageEdit: ImageEdit,
        iconPack: IconPackContainer,
        customIcon: ResourceDrawable? = null
    ): IconSourceStage? {
        val resIcon = customIcon ?: iconPack.getApplicationIcon(application.toInstalledApplication()) ?: return null

        val retainable = modifiers.materialYouPack.isReplayableOn(adaptiveLayers = false)

        preservePackLayers(iconPack.iconPackName, resIcon)?.let {
            return IconSourceStage(it, ModifierInputPipeline.IMAGE_EDIT, imageEdit,
                modifiers.materialYouPack.isReplayableOn(adaptiveLayers = true))
        }

        val bitmapIcon = getIconBitmap(resIcon.drawable) ?: return null
        val changesWithMaterialYou = packChangesWithMaterialYouColors(iconPack.iconPackName)
        val parsedIcon = exportIconPackXML(iconPack.iconPackName, resIcon).let {
            if (changesWithMaterialYou) styleMaterialYouPackIcon(it) else it
        }

        return generateImage(
            bitmapIcon,
            parsedIcon,
            imageEdit,
            preserveAdaptiveAppearance = changesWithMaterialYou,
            retainable = retainable
        )
    }

    private fun generateImageFromApplication(
        application: PackageInfoStruct,
        imageEdit: ImageEdit): IconSourceStage? {
        val applicationIcon = application.icon.copyForRendering()

        // Material You variant: recolor the app's own <monochrome> layer directly — tint it with
        // the chosen foreground over the chosen background. No path-tracing (that produces line
        // art, issue #81), so this only runs for the plain (NONE) modifier.
        if (source.applicationIconVariant == ApplicationIconVariant.MATERIAL_YOU &&
            imageEdit == ImageEdit.NONE && hasMonochromeLayer(applicationIcon)) {
            return generateMaterialYou(applicationIcon)?.let {
                IconSourceStage(it, ModifierInputPipeline.IMAGE_EDIT, imageEdit)
            }
        }

        if (source.applicationIconVariant == ApplicationIconVariant.MATERIAL_YOU) {
            // Apps without an official layer still get a clearly-labelled Renkin-generated
            // approximation. Rasterising the complete launcher icon preserves its optical size;
            // getAppIconBitmap extracts the adaptive foreground and would enlarge it here.
            val bitmapIcon = applicationIcon.shrinkIfBiggerThan(500) ?: return null
            val generated = generateMaterialYouFromOriginal(bitmapIcon)
            return IconSourceStage(generated, ModifierInputPipeline.IMAGE_EDIT, imageEdit)
        }
        if (source.applicationIconVariant == ApplicationIconVariant.DEFAULT &&
            source.useFullApplicationIcon
        ) {
            val completeIcon = applicationIcon.shrinkIfBiggerThan(500) ?: return null
            return generateImage(
                bitmapIcon = completeIcon,
                parsedIcon = null,
                imageEdit = imageEdit
            )
        }
        // The standard variant deliberately exports only the foreground; the complete adaptive
        // appearance is handled above so its background reaches modifiers as part of one frame.
        val bitmapIcon = getAppIconBitmap(applicationIcon) ?: return null
        if (source.applicationIconVariant == ApplicationIconVariant.MONOCHROME) {
            // This is deliberately based on the regular launcher artwork, not the optional
            // Material You layer: every app is supported and its original design stays intact.
            return generateImage(toMonochrome(bitmapIcon), null, imageEdit)
        }
        val parsedIcon = parseApplicationIcon(application)

        return generateImage(bitmapIcon, parsedIcon, imageEdit)
    }

    private fun preservePackLayers(packName: String, resource: ResourceDrawable): AdaptiveIconPackDrawable? {
        if (export.themed || !resource.drawable.isAdaptiveIconDrawable()) return null
        val source = resource.drawable.copyForRendering() as AdaptiveIconDrawable
        val changesWithMaterialYou = packChangesWithMaterialYouColors(packName)
        // Parsed at most once: both the original and the restyled capture fall back to the same layer.
        val packMonochrome by lazy {
            resourceResolver.getResources(packName)?.let {
                IconParser.readMonochromeLayer(it, resource.resourceId)
            }
        }
        fun monochromeOf(icon: AdaptiveIconDrawable) =
            if (icon.haveMonochrome()) icon.monochrome else packMonochrome
        val restyle = modifiers.materialYouPack.hasRestyling
        // Only a restyle keeps the original layers; browser tiles never restyle, so skip the encodes.
        val original = if (changesWithMaterialYou && restyle) {
            AdaptiveIconPackDrawable.capture(source, monochromeOf(source))
        } else null
        val styled = if (restyle && changesWithMaterialYou) {
            val editable = if (modifiers.materialYouPack.strokeScale != 1f) {
                exportIconPackXML(packName, ResourceDrawable(resource.resourceId, source)) ?: source
            } else source
            styleMaterialYouPackIcon(editable) as? AdaptiveIconDrawable ?: source
        } else source
        val editState = if (changesWithMaterialYou) MaterialYouPackEditState(
            selectedScheme = modifiers.materialYouPack.selectedScheme,
            customForeground = modifiers.materialYouPack.customForeground
                ?: MaterialYouPackEditState.DEFAULT_FOREGROUND,
            customBackground = modifiers.materialYouPack.customBackground
                ?: MaterialYouPackEditState.DEFAULT_BACKGROUND,
            strokeScale = modifiers.materialYouPack.strokeScale
        ) else null
        return AdaptiveIconPackDrawable.capture(
            styled,
            monochromeOf(styled),
            editState,
            originalForegroundPng = original?.foregroundPng,
            originalBackgroundPng = original?.backgroundPng
        )
    }

    /** True when [icon] ships a Material You `<monochrome>` layer (API 33+). */
    private fun hasMonochromeLayer(icon: Drawable): Boolean {
        return icon.isAdaptiveIconDrawable() && (icon as AdaptiveIconDrawable).haveMonochrome()
    }

    private fun generateMaterialYou(icon: Drawable): IconPackDrawable? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !icon.isAdaptiveIconDrawable()) return null
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
     * Paints [modifiers.effect.colorizer.firstColor] through the monochrome alpha mask (SRC_IN keeps the silhouette's
     * shape, replaces its colour) and composites it over [export.background.color]. The background is
     * always applied — unlike [colorizeBitmap] — because the mask itself is transparent.
     */
    private fun recolorMaterialYouLayer(mask: Bitmap): Bitmap {
        return recolorMaterialYouMask(
            mask,
            modifiers.effect.colorizer.firstColor,
            export.background.color,
            export.foregroundStyle,
            export.background.style
        )
    }

    /**
     * Creates an unofficial two-colour Material You approximation from regular icon artwork.
     * Transparent and light pixels become the selected background; dark artwork becomes the
     * foreground. This keeps the simple approximation used before foreground extraction trials.
     */
    private fun generateMaterialYouFromOriginal(icon: Bitmap): BitmapIconDrawable {
        val pixels = IntArray(icon.width * icon.height)
        icon.getPixels(pixels, 0, icon.width, 0, 0, icon.width, icon.height)

        val fgA = android.graphics.Color.alpha(modifiers.effect.colorizer.firstColor)
        val fgR = android.graphics.Color.red(modifiers.effect.colorizer.firstColor)
        val fgG = android.graphics.Color.green(modifiers.effect.colorizer.firstColor)
        val fgB = android.graphics.Color.blue(modifiers.effect.colorizer.firstColor)

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
            val backgroundShader = options.backgroundShader(icon.width, icon.height)
            if (backgroundShader == null) {
                canvas.drawColor(export.background.color)
            } else {
                canvas.drawRect(
                    0f,
                    0f,
                    icon.width.toFloat(),
                    icon.height.toFloat(),
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = backgroundShader }
                )
            }
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
        val adaptive = android.graphics.Color.alpha(export.background.color) == 255
        return BitmapIconDrawable(
            ctx.resources,
            if (adaptive) bitmap else bitmap.scaleFromCenter(adaptiveIconScale),
            exportAsAdaptiveIcon = adaptive,
            previewScale = if (adaptive) adaptiveIconScale else 1f
        )
    }

    /** Removes hue and saturation while retaining the original icon's luminance and alpha. */
    private fun toMonochrome(icon: Bitmap): Bitmap = monochromeBitmap(icon, source.invertMonochrome)

    /**
     * Single dispatch point for applying an [ImageEdit] to a (bitmap, parsedIcon)
     * pair. Adding a new modifier means adding one [ImageEdit] entry and one branch
     * here — every "generate from a source" path routes through this method.
     */
    private fun generateImage(
        bitmapIcon: Bitmap,
        parsedIcon: Drawable?,
        imageEdit: ImageEdit,
        preserveAdaptiveAppearance: Boolean = false,
        retainable: Boolean = true
    ): IconSourceStage {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            imageEdit == ImageEdit.NONE && preserveAdaptiveAppearance && !export.themed) {
            val adaptiveIcon = parsedIcon as? AdaptiveIconDrawable
            renderAdaptivePackIcon(adaptiveIcon)?.let {
                return IconSourceStage(it, ModifierInputPipeline.IMAGE_EDIT, imageEdit, retainable)
            }
        }

        // Legacy adaptive sources are flattened to their foreground below, so compensate for
        // Android's 72/108 safe zone before doing that. Material You icon packs deliberately keep
        // the complete adaptive drawable: scaling its foreground here would make only the glyph
        // jump 1.5x as soon as any modifier is selected while the background stays unchanged.
        if (shouldNormalizeAdaptiveForeground(preserveAdaptiveAppearance) &&
            parsedIcon != null &&
            parsedIcon.isAdaptiveIconDrawable()
        ) {
            fixAdaptiveIconSize(parsedIcon as AdaptiveIconDrawable)
        }

        if (imageEdit == ImageEdit.NONE) return IconSourceStage(
            getDefaultIcon(bitmapIcon, parsedIcon), ModifierInputPipeline.IMAGE_EDIT, imageEdit, retainable
        )

        // Generic modifiers run on ONE canonical frame — the pixels ImageEdit.NONE would show —
        // and never see [parsedIcon] again. Handing them the parsed structure as well is what let
        // each of them compose the icon differently: colourize painted the enlarged foreground
        // bitmap, path tracing followed the parsed vector, and edge detection wrapped its result
        // back into the parsed inset (insetting an already-inset icon a second time).
        val canonical = canonicalArtwork(bitmapIcon, parsedIcon)
        return IconSourceStage(
            BitmapIconDrawable(ctx.resources, canonical), ModifierInputPipeline.BITMAP_IMAGE_EDIT,
            imageEdit, retainable
        )
    }

    /**
     * The single frame every generic modifier starts from: whatever ImageEdit.NONE renders,
     * rasterised through draw() at the working size. draw() and not toBitmap(), because a
     * vector's toBitmap() re-fits its artwork to the canvas (resizeTo + center) and so drops the
     * icon's own margins — the reason a modifier used to shrink or enlarge the icon.
     *
     * The Material You variants are the deliberate exception: they style the icon's own layers
     * (foreground/monochrome plus the pack's stroke scale) before this phase and keep the bitmap
     * they were handed. Themed export likewise inserts its own inset later.
     */
    private fun canonicalArtwork(bitmapIcon: Bitmap, parsedIcon: Drawable?): Bitmap {
        if (export.materialYou || export.themed) return bitmapIcon
        // Without a parsed drawable the bitmap already IS the canonical frame.
        val parsed = parsedIcon ?: return bitmapIcon
        return runCatching {
            getDefaultIcon(bitmapIcon, parsed, preserveAdaptiveAppearance = true)
                .toSafeBitmapOrNull(ADAPTIVE_PACK_RENDER_SIZE, ADAPTIVE_PACK_RENDER_SIZE)
        }.getOrNull() ?: bitmapIcon
    }

    private fun generateText(applicationName: String, textType: TextType): IconPackDrawable {
        val size = 256
        val strokeWidth = size / 48F
        val textGenerator = LetterGenerator(ctx, source.textFontPath, ownedTypeface)

        // Custom text only applies to the CUSTOM type; the case transform applies everywhere.
        val text = when (source.textCase) {
            TextCase.UPPER -> applicationName.uppercase()
            TextCase.LOWER -> applicationName.lowercase()
            TextCase.AS_IS -> applicationName
        }

        val newIcon = when(textType) {
            TextType.FULL_NAME -> {
                val draw = textGenerator.generateAppName(text, modifiers.effect.colorizer.firstColor, size)
                createVectorForMultiLineText(draw as BaseTextDrawable, modifiers.effect.colorizer.firstColor, size)
            }
            TextType.ONE_LETTER -> {
                val draw = textGenerator.generateFirstLetter(text, modifiers.effect.colorizer.firstColor, strokeWidth, size)
                createVectorForText(draw as BaseTextDrawable, modifiers.effect.colorizer.firstColor, strokeWidth, size)
            }
            TextType.TWO_LETTERS -> {
                val draw = textGenerator.generateTwoLetters(text, modifiers.effect.colorizer.firstColor, strokeWidth, size)
                createVectorForText(draw as BaseTextDrawable, modifiers.effect.colorizer.firstColor, strokeWidth, size)
            }
            TextType.CUSTOM -> {
                val custom = when (source.textCase) {
                    TextCase.UPPER -> source.textCustom.uppercase()
                    TextCase.LOWER -> source.textCustom.lowercase()
                    TextCase.AS_IS -> source.textCustom
                }.trim().ifEmpty { text }
                if (custom.length <= 3) {
                    val draw = textGenerator.generateExact(custom, modifiers.effect.colorizer.firstColor, strokeWidth, size)
                    createVectorForText(draw as BaseTextDrawable, modifiers.effect.colorizer.firstColor, strokeWidth, size)
                } else {
                    val draw = textGenerator.generateAppName(custom, modifiers.effect.colorizer.firstColor, size)
                    createVectorForMultiLineText(draw as BaseTextDrawable, modifiers.effect.colorizer.firstColor, size)
                }
            }
        }

        if (export.themed)
            return vectorToInset(newIcon.toImageVectorDrawable())

        return newIcon.toImageVectorDrawable()
    }

    private fun parseApplicationIcon(application: PackageInfoStruct): Drawable? {
        if (isVectorDrawable(application.icon) && export.vector) {
            val res = resourceResolver.getResources(application.packageName) ?: return null
            return IconParser.parseDrawable(res, application.icon, application.iconID)
        }

        return null
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

    /**
     * The usable artwork of one adaptive layer. The framework often hands the layer back wrapped
     * in an InsetDrawable; without unwrapping it the caller falls back to rasterising the WHOLE
     * adaptive icon, which drags the background layer in with it (Renkin's own icon did exactly
     * that in Create → Application icon → Default).
     */
    private fun adaptiveLayer(layer: Drawable?): Drawable? {
        val unwrapped = if (layer is InsetDrawable) layer.drawable else layer
        return unwrapped?.takeIf { it is BitmapDrawable || it is VectorDrawable }
    }

    private fun getAppIconBitmap(icon: Drawable, maxSize: Int = 500): Bitmap? {
        var newIcon = icon

        if (newIcon.isAdaptiveIconDrawable()) {
            val adaptiveIcon = newIcon as AdaptiveIconDrawable
            adaptiveLayer(adaptiveIcon.foreground)?.let { newIcon = ForegroundIconDrawable(it) }

            if (PackageVersion.is33OrMore() && adaptiveIcon.monochrome != null && export.materialYou) {
                adaptiveLayer(adaptiveIcon.monochrome)?.let { newIcon = ForegroundIconDrawable(it) }
            }
        }

        return newIcon.shrinkIfBiggerThan(maxSize)
    }

    private fun Drawable.copyForRendering(): Drawable =
        constantState?.newDrawable(ctx.resources)?.mutate() ?: this

    private fun getDefaultIcon(
        bitmapIcon: Bitmap,
        parsedIcon: Drawable?,
        preserveAdaptiveAppearance: Boolean = false
    ): IconPackDrawable {
        if (parsedIcon != null) {
            if (parsedIcon.isAdaptiveIconDrawable()) {
                parsedIcon as AdaptiveIconDrawable
                if (preserveAdaptiveAppearance && !export.themed) {
                    renderAdaptivePackIcon(parsedIcon)?.let { return it }
                }
                // The bitmap path already unwraps and normalises the adaptive foreground. In
                // themed mode, feeding the pack-authored foreground inset back into the standard
                // themed inset applied it twice and made the comparison preview microscopic.
                if (export.themed) return getDefaultBitmapIcon(bitmapIcon)
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
        return if (export.themed) {
            bitmapToInset(bitmap)
        } else {
            BitmapIconDrawable(ctx.resources, bitmap)
        }
    }

    private fun getDefaultVectorIcon(vectorIcon: ImageVectorDrawable): IconPackDrawable {
        return if (export.themed) {
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

            if (PackageVersion.is33OrMore() && export.materialYou) {
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
            iconPackCatalog.changesWithMaterialYouColors(iconPackName)
        }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun renderAdaptivePackIcon(icon: AdaptiveIconDrawable?): BitmapIconDrawable? {
        icon ?: return null
        val rendered = icon.toSafeBitmapOrNull(
            ADAPTIVE_PACK_RENDER_SIZE,
            ADAPTIVE_PACK_RENDER_SIZE
        ) ?: return null
        return BitmapIconDrawable(
            ctx.resources,
            rendered
        )
    }

    private fun styleMaterialYouPackIcon(parsedIcon: Drawable?): Drawable? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return parsedIcon
        val adaptive = parsedIcon as? AdaptiveIconDrawable ?: return parsedIcon
        val vector = adaptive.foregroundVectorOrNull()

        val modifierStrokeScale = modifiers.materialYouPack.strokeScale.coerceIn(0.5f, 2f)
        // Lawnicons ships real strokes (for example _10.svg is 12 units in a 192-unit
        // viewport) inside a 32% adaptive foreground inset. Preserve that source geometry:
        // centred 100% means the pack's real stroke width. The same 500 px render is used by
        // browser tiles, the comparison header, modifiers, persistence and APK export so changing
        // a modifier cannot silently switch to a thinner preview raster.
        vector?.root?.scaleStrokePaths(effectiveMaterialYouPackStrokeScale(modifierStrokeScale))
        modifiers.materialYouPack.foreground?.let { foreground ->
            if (vector != null) {
                vector.root.setReferenceColorPaths(SolidColor(Color(foreground)))
                vector.tintColor = Color.Unspecified
            } else {
                adaptive.foreground.setTint(foreground)
            }
        }

        val background = modifiers.materialYouPack.background?.let(::ColorDrawable)
            ?: adaptive.background
        val monochrome = if (adaptive.haveMonochrome()) adaptive.monochrome else null
        monochrome?.foregroundVectorOrNull()?.root
            ?.scaleStrokePaths(effectiveMaterialYouPackStrokeScale(modifierStrokeScale))
        return newAdaptiveIconDrawable(adaptive.foreground, background, monochrome)
    }

    private fun parseIconPackXML(iconPackName: String, iconDrawable: ResourceDrawable): Drawable? {
        if (!iconDrawable.drawable.isAdaptiveIconDrawable() && !isVectorDrawable(iconDrawable.drawable)) return null

        val res = resourceResolver.getResources(iconPackName) ?: return null
        val icon = IconParser.parseDrawable(res, iconDrawable.drawable, iconDrawable.resourceId, preserveFrameworkInsets = !export.themed)

        if (!icon.isAdaptiveIconDrawable()) return null

        val vectorIcon = icon.foregroundVectorOrNull()

        // Material You packs such as Lawnicons intentionally define their own stroke widths
        // and adaptive inset. Other packs keep the established normalization behaviour.
        if (export.themed && vectorIcon != null && !packChangesWithMaterialYouColors(iconPackName)) {
            val stroke = vectorIcon.viewportHeight / 48 //1F at 48
            vectorIcon.root.editStrokePaths(stroke)
        }

        return icon
    }

    private fun colorizeImage(bitmapIcon: Bitmap, parsedIcon: Drawable?, mode: PorterDuff.Mode): IconPackDrawable {
        // Themed export owns one standard adaptive inset. Reusing an adaptive pack's parsed inset
        // here stacks the source safe zone on top of it; use the already-normalised bitmap frame.
        if (export.themed) return imageEditPipeline.colorize(bitmapIcon, mode)
        // Segment layers are per-pixel, which the vector recolour path cannot express: bake.
        val effect = modifiers.effect
        if (effect.segments.isNotEmpty() ||
            effect.colorizer.lighten ||
            effect.colorizer.mode == ColorizerMode.GRADIENT ||
            effect.colorizer.monochrome ||
            parsedIcon == null
        ) {
            return imageEditPipeline.colorize(bitmapIcon, mode)
        }
        return when (parsedIcon) {
            is InsetIconDrawable -> {
                parsedIcon.newDrawable(colorizeImage(bitmapIcon, parsedIcon.drawable, mode))
            }
            is ImageVectorDrawable -> imageEditPipeline.colorizeVector(parsedIcon)
            else -> imageEditPipeline.colorize(bitmapIcon, mode)
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
        return adjustmentPipeline.apply(icon)
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
        const val FALLBACK_BITMAP_CACHE_BYTES = 8 * 1024 * 1024
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

internal fun recolorMaterialYouMask(
    mask: Bitmap,
    foreground: Int,
    background: Int,
    // Gradients arrive pre-rasterised, so the per-pixel composite stays a lookup.
    foregroundStyle: ColorizerStyle? = null,
    backgroundStyle: ColorizerStyle? = null
): Bitmap {
    val pixels = IntArray(mask.width * mask.height)
    mask.getPixels(pixels, 0, mask.width, 0, 0, mask.width, mask.height)
    val foregroundPixels = gradientPixels(foregroundStyle, mask.width, mask.height)
    val backgroundPixels = gradientPixels(backgroundStyle, mask.width, mask.height)
    for (index in pixels.indices) {
        val coverage = android.graphics.Color.alpha(pixels[index]) / 255f
        pixels[index] = compositeMaterialYouPixel(
            foregroundPixels?.get(index) ?: foreground,
            backgroundPixels?.get(index) ?: background,
            coverage
        )
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
