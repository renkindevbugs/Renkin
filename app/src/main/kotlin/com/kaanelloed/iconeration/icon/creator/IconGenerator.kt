package com.kaanelloed.iconeration.icon.creator

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.VectorDrawable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.kaanelloed.iconeration.constants.SuppressSameParameterValue
import com.kaanelloed.iconeration.data.ImageEdit
import com.kaanelloed.iconeration.data.Source
import com.kaanelloed.iconeration.data.TextType
import com.kaanelloed.iconeration.drawable.BaseTextDrawable
import com.kaanelloed.iconeration.drawable.DrawableExtension.Companion.isAdaptiveIconDrawable
import com.kaanelloed.iconeration.drawable.DrawableExtension.Companion.shrinkIfBiggerThan
import com.kaanelloed.iconeration.drawable.ForegroundIconDrawable
import com.kaanelloed.iconeration.drawable.ResourceDrawable
import com.kaanelloed.iconeration.extension.changeBackgroundColor
import com.kaanelloed.iconeration.icon.AdaptiveIcon
import com.kaanelloed.iconeration.icon.parser.AdaptiveIconParser
import com.kaanelloed.iconeration.icon.BaseIcon
import com.kaanelloed.iconeration.icon.BitmapIcon
import com.kaanelloed.iconeration.icon.EmptyIcon
import com.kaanelloed.iconeration.icon.ExportableIcon
import com.kaanelloed.iconeration.icon.parser.IconParser
import com.kaanelloed.iconeration.icon.InsetIcon
import com.kaanelloed.iconeration.icon.VectorIcon
import com.kaanelloed.iconeration.packages.ApplicationManager
import com.kaanelloed.iconeration.packages.PackageInfoStruct
import com.kaanelloed.iconeration.packages.PackageVersion
import com.kaanelloed.iconeration.vector.MutableImageVector.Companion.toMutableImageVector
import com.kaanelloed.iconeration.vector.PathConverter.Companion.toNodes
import com.kaanelloed.iconeration.vector.VectorEditor.Companion.applyAndRemoveGroup
import com.kaanelloed.iconeration.vector.VectorEditor.Companion.editPathColors
import com.kaanelloed.iconeration.vector.VectorEditor.Companion.editStrokePaths
import com.kaanelloed.iconeration.vector.VectorEditor.Companion.editPaths
import com.kaanelloed.iconeration.vector.VectorEditor.Companion.resizeAndCenter
import com.kaanelloed.iconeration.vector.VectorEditor.Companion.scaleAtCenter
import com.kaanelloed.iconeration.xml.XmlParser.Companion.toXmlNode
import dev.adevium.imagetracer.ImageTracer
import dev.adevium.tgCannyEdgeCompose.CannyEdgeDetector

class IconGenerator(
    private val ctx: Context,
    private val options: GenerationOptions,
    private val primaryIconPackApplications: IconPackContainer,
    private val secondaryIconPackApplications: IconPackContainer
) {
    fun generateIcon(application: PackageInfoStruct,
                     onUpdate: (application: PackageInfoStruct, icon: ExportableIcon) -> Unit) {
        generateIcons(listOf(application), onUpdate)
    }

    fun generateIcon(application: PackageInfoStruct,
                     customIcon: ResourceDrawable?,
                     onUpdate: (application: PackageInfoStruct, icon: ExportableIcon) -> Unit)  {
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

        onUpdate(application, icon)
    }

    fun generateIcons(applications: List<PackageInfoStruct>
                      , onUpdate: (application: PackageInfoStruct, icon: ExportableIcon) -> Unit) {
        if (options.primarySource == Source.NONE) {
            return
        }

        for (app in applications) {
            if (applicationShouldBeSkipped(app)) {
                continue
            }

            val primaryIcon = generateIcon(
                app,
                options.primarySource,
                options.primaryImageEdit,
                options.primaryTextType,
                primaryIconPackApplications
            )

            val icon = if (primaryIcon is EmptyIcon) {
                generateIcon(
                    app,
                    options.secondarySource,
                    options.secondaryImageEdit,
                    options.secondaryTextType,
                    secondaryIconPackApplications
                )
            } else {
                primaryIcon
            }

            onUpdate(app, icon)
        }
    }

    fun colorizeFromIconPack(iconPackName: String, icon: ResourceDrawable): ExportableIcon {
        val bitmapIcon = getIconBitmap(icon.drawable)
        val parsedIcon = exportIconPackXML(iconPackName, icon) ?: EmptyIcon()

        return if (options.primaryImageEdit == ImageEdit.COLORIZE)
            colorizeImage(bitmapIcon, parsedIcon, PorterDuff.Mode.SRC_IN)
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
    ): ExportableIcon {
        return when (source) {
            Source.NONE -> EmptyIcon()
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
    ): ExportableIcon {
        val resIcon = customIcon ?: iconPack.getApplicationIcon(application.packageName) ?: return EmptyIcon()

        val bitmapIcon = getIconBitmap(resIcon.drawable)
        val parsedIcon = exportIconPackXML(iconPack.iconPackName, resIcon) ?: EmptyIcon()

        return generateImage(bitmapIcon, parsedIcon, imageEdit, PorterDuff.Mode.MULTIPLY)
    }

    private fun generateImageFromApplication(
        application: PackageInfoStruct,
        imageEdit: ImageEdit): ExportableIcon {

        val bitmapIcon = getAppIconBitmap(application)
        val parsedIcon = parseApplicationIcon(application)

        return generateImage(bitmapIcon, parsedIcon, imageEdit, PorterDuff.Mode.MULTIPLY)
    }

    private fun generateImage(
        bitmapIcon: Bitmap,
        parsedIcon: BaseIcon,
        imageEdit: ImageEdit,
        mode: PorterDuff.Mode): ExportableIcon {
        val defaultIcon = getDefaultIcon(bitmapIcon, parsedIcon)

        return when (imageEdit) {
            ImageEdit.NONE -> defaultIcon
            ImageEdit.PATH -> generatePathTracing(bitmapIcon, parsedIcon)
            ImageEdit.EDGE -> generateCannyEdgeDetection(bitmapIcon)
            ImageEdit.COLORIZE -> colorizeImage(bitmapIcon, parsedIcon, mode)
        }
    }

    private fun generateText(applicationName: String, textType: TextType): ExportableIcon {
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

        return VectorIcon(newIcon)
    }

    private fun parseApplicationIcon(application: PackageInfoStruct): BaseIcon {
        val appMan = ApplicationManager(ctx)

        if (isVectorDrawable(application.icon) && options.vector) {
            val res = appMan.getResources(application.packageName) ?: return EmptyIcon()
            return IconParser.parseDrawable(res, application.icon, application.iconID)
        }

        return EmptyIcon()
    }

    private fun generateCannyEdgeDetection(bitmapIcon: Bitmap): ExportableIcon {
        val edgeDetector = CannyEdgeDetector()

        edgeDetector.process(
            bitmapIcon.asImageBitmap(),
            options.color
        )

        return BitmapIcon(edgeDetector.edgesImage)
    }

    private fun generatePathTracing(bitmapIcon: Bitmap, parsedIcon: BaseIcon): ExportableIcon {
        return if (parsedIcon !is EmptyIcon) {
            generatePathFromXML(bitmapIcon, parsedIcon)
        } else {
            generateColorQuantizationDetection(bitmapIcon)
        }
    }

    private fun generatePathFromXML(bitmapIcon: Bitmap, parsedIcon: BaseIcon): ExportableIcon {
        var vectorIcon: BaseIcon = parsedIcon

        if (parsedIcon is AdaptiveIcon) {
            if (parsedIcon.foreground is VectorIcon) {
                vectorIcon = parsedIcon.foreground
            }

            if (parsedIcon.monochrome is VectorIcon && options.monochrome) {
                vectorIcon = parsedIcon.monochrome
            }
        }

        if (vectorIcon !is VectorIcon) {
            return generateColorQuantizationDetection(bitmapIcon)
        }

        val mutableVector = vectorIcon.vector.toMutableImageVector()

        val stroke = mutableVector.viewportHeight / 48 //1F at 48
        mutableVector.root.editPaths(stroke, SolidColor(Color.Unspecified), SolidColor(Color(options.color)))
        mutableVector.resizeAndCenter().applyAndRemoveGroup().scaleAtCenter(6F / 4F)
        mutableVector.tintColor = Color.Unspecified

        if (options.themed) {
            mutableVector.scaleAtCenter(0.5F)
        }

        return VectorIcon(mutableVector)
    }

    private fun generateColorQuantizationDetection(bitmapIcon: Bitmap): ExportableIcon {
        val imageVector = ImageTracer.imageToVector(bitmapIcon.asImageBitmap()
            , ImageTracer.TracingOptions())

        val vector = imageVector.toMutableImageVector()
        val stroke = imageVector.viewportHeight / 48 //1F at 48
        vector.root.editPaths(stroke, SolidColor(Color.Unspecified), SolidColor(Color(options.color)))
        vector.resizeAndCenter()

        if (options.themed) {
            vector.scaleAtCenter(0.5F)
        }

        return VectorIcon(vector)
    }

    private fun getAppIconBitmap(app: PackageInfoStruct, maxSize: Int = 500): Bitmap {
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

    private fun getDefaultIcon(bitmapIcon: Bitmap, parsedIcon: BaseIcon): ExportableIcon {
        return if (parsedIcon is VectorIcon) {
            parsedIcon
        } else {
            BitmapIcon(bitmapIcon)
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

    private fun getIconBitmap(icon: Drawable, maxSize: Int = 500): Bitmap {
        return if (icon.isAdaptiveIconDrawable()) {
            icon as AdaptiveIconDrawable
            icon.foreground.shrinkIfBiggerThan(maxSize)
        } else {
            icon.shrinkIfBiggerThan(maxSize)
        }
    }

    private fun colorizeBitmap(icon: Bitmap, mode: PorterDuff.Mode): Bitmap {
        val coloredIcon = icon.copy(icon.config!!, true)
        val paint = Paint()

        paint.colorFilter = PorterDuffColorFilter(options.color, mode)
        Canvas(coloredIcon).drawBitmap(coloredIcon, 0F, 0F, paint)

        return addBackground(coloredIcon)
    }

    private fun addBackground(image: Bitmap): Bitmap {
        return if (options.themed) image.changeBackgroundColor(options.bgColor) else image
    }

    private fun exportIconPackXML(iconPackName: String, iconDrawable: ResourceDrawable): ExportableIcon? {
        if (!isVectorDrawable(iconDrawable.drawable)) return null

        val res = ApplicationManager(ctx).getResources(iconPackName) ?: return null
        val parser = ApplicationManager(ctx).getPackageResourceXml(iconPackName, iconDrawable.resourceId) ?: return null

        val adaptiveIcon = AdaptiveIconParser.parse(res, parser.toXmlNode()) ?: return null
        var vectorIcon: VectorIcon? = null

        if (adaptiveIcon.foreground is InsetIcon) {
            val inset = adaptiveIcon.foreground
            if (inset.innerIcon is VectorIcon) {
                vectorIcon = inset.innerIcon
            }
        }

        if (adaptiveIcon.foreground is VectorIcon) {
            vectorIcon = adaptiveIcon.foreground
        }

        if (vectorIcon == null) {
            return null
        }

        val mutableVector = vectorIcon.vector.toMutableImageVector().resizeAndCenter().scaleAtCenter(0.5f)

        val stroke = mutableVector.viewportHeight / 48 //1F at 48
        mutableVector.root.editStrokePaths(stroke)

        return VectorIcon(mutableVector)
    }

    private fun colorizeImage(bitmapIcon: Bitmap, parsedIcon: BaseIcon, mode: PorterDuff.Mode): ExportableIcon {
        return if (parsedIcon is VectorIcon) {
            colorizeVector(parsedIcon)
        } else {
            BitmapIcon(colorizeBitmap(bitmapIcon, mode))
        }
    }

    private fun colorizeVector(vectorIcon: VectorIcon): VectorIcon {
        val vector = vectorIcon.vector.toMutableImageVector()

        vector.root.editPathColors(SolidColor(Color.Unspecified), SolidColor(Color(options.color)))
        vector.tintColor = Color.Unspecified

        return VectorIcon(vector)
    }

    private fun applicationShouldBeSkipped(app: PackageInfoStruct): Boolean {
        return !options.override && app.createdIcon !is EmptyIcon
    }
}