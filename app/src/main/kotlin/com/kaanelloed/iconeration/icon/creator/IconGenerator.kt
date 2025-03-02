package com.kaanelloed.iconeration.icon.creator

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.VectorDrawable
import androidx.compose.ui.geometry.Rect
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
import com.kaanelloed.iconeration.drawable.BitmapIconDrawable
import com.kaanelloed.iconeration.drawable.ForegroundIconDrawable
import com.kaanelloed.iconeration.drawable.IconPackDrawable
import com.kaanelloed.iconeration.drawable.ImageVectorDrawable
import com.kaanelloed.iconeration.drawable.InsetIconDrawable
import com.kaanelloed.iconeration.drawable.ResourceDrawable
import com.kaanelloed.iconeration.drawable.haveMonochrome
import com.kaanelloed.iconeration.drawable.isAdaptiveIconDrawable
import com.kaanelloed.iconeration.drawable.shrinkIfBiggerThan
import com.kaanelloed.iconeration.extension.changeBackgroundColor
import com.kaanelloed.iconeration.extension.clone
import com.kaanelloed.iconeration.icon.parser.IconParser
import com.kaanelloed.iconeration.packages.ApplicationManager
import com.kaanelloed.iconeration.packages.PackageInfoStruct
import com.kaanelloed.iconeration.packages.PackageVersion
import com.kaanelloed.iconeration.drawable.toImageVectorDrawable
import com.kaanelloed.iconeration.vector.PathConverter.Companion.toNodes
import com.kaanelloed.iconeration.vector.VectorEditor.Companion.applyAndRemoveGroup
import com.kaanelloed.iconeration.vector.VectorEditor.Companion.editPathColors
import com.kaanelloed.iconeration.vector.VectorEditor.Companion.editStrokePaths
import com.kaanelloed.iconeration.vector.VectorEditor.Companion.editPaths
import com.kaanelloed.iconeration.vector.VectorEditor.Companion.resizeAndCenter
import com.kaanelloed.iconeration.vector.VectorEditor.Companion.scaleAtCenter
import dev.adevium.imagetracer.ImageTracer
import dev.adevium.tgCannyEdgeCompose.CannyEdgeDetector

class IconGenerator(
    private val ctx: Context,
    private val options: GenerationOptions,
    private val primaryIconPackApplications: IconPackContainer,
    private val secondaryIconPackApplications: IconPackContainer
) {
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

        onUpdate(application, icon)
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

    private fun generateImage(
        bitmapIcon: Bitmap,
        parsedIcon: Drawable?,
        imageEdit: ImageEdit,
        mode: PorterDuff.Mode): IconPackDrawable? {
        val defaultIcon = getDefaultIcon(bitmapIcon, parsedIcon)

        return when (imageEdit) {
            ImageEdit.NONE -> defaultIcon
            ImageEdit.PATH -> generatePathTracing(bitmapIcon, parsedIcon)
            ImageEdit.EDGE -> generateCannyEdgeDetection(bitmapIcon)
            ImageEdit.COLORIZE -> colorizeImage(bitmapIcon, parsedIcon, mode)
        }
    }

    private fun generateText(applicationName: String, textType: TextType): IconPackDrawable? {
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

    private fun generateCannyEdgeDetection(bitmapIcon: Bitmap): IconPackDrawable? {
        val edgeDetector = CannyEdgeDetector()

        edgeDetector.process(
            bitmapIcon.asImageBitmap(),
            options.color
        )

        val bitmap = if (options.themed) {
            convertBitmapToAdaptiveForeground(edgeDetector.edgesImage)
        } else {
            edgeDetector.edgesImage
        }

        return BitmapIconDrawable(bitmap)
    }

    private fun generatePathTracing(bitmapIcon: Bitmap, parsedIcon: Drawable?): IconPackDrawable? {
        return if (parsedIcon != null) {
            generatePathFromXML(bitmapIcon, parsedIcon)
        } else {
            generateColorQuantizationDetection(bitmapIcon)
        }
    }

    private fun generatePathFromXML(bitmapIcon: Bitmap, parsedIcon: Drawable): IconPackDrawable? {
        var vectorIcon = parsedIcon

        if (parsedIcon.isAdaptiveIconDrawable()) {
            parsedIcon as AdaptiveIconDrawable

            if (parsedIcon.foreground is ImageVectorDrawable) {
                vectorIcon = parsedIcon.foreground
            }

            if (parsedIcon.haveMonochrome() && options.monochrome) {
                vectorIcon = parsedIcon.monochrome!!
            }
        }

        if (vectorIcon !is ImageVectorDrawable) {
            return generateColorQuantizationDetection(bitmapIcon)
        }

        val stroke = vectorIcon.viewportHeight / 48 //1F at 48
        vectorIcon.root.editPaths(stroke, SolidColor(Color.Unspecified), SolidColor(Color(options.color)))
        vectorIcon.resizeAndCenter().applyAndRemoveGroup().scaleAtCenter(6F / 4F)
        vectorIcon.tintColor = Color.Unspecified

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

        return InsetIconDrawable(BitmapDrawable(null, bitmap), dims, fractions)
    }

    private fun generateColorQuantizationDetection(bitmapIcon: Bitmap): IconPackDrawable? {
        val imageVector = ImageTracer.imageToVector(bitmapIcon.asImageBitmap()
            , ImageTracer.TracingOptions())

        val vector = imageVector.toImageVectorDrawable()
        val stroke = imageVector.viewportHeight / 48 //1F at 48
        vector.root.editPaths(stroke, SolidColor(Color.Unspecified), SolidColor(Color(options.color)))
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

    private fun getDefaultIcon(bitmapIcon: Bitmap, parsedIcon: Drawable?): IconPackDrawable? {
        return if (parsedIcon is ImageVectorDrawable) {
            getDefaultVectorIcon(parsedIcon)
        } else {
            getDefaultBitmapIcon(bitmapIcon)
        }
    }

    private fun getDefaultBitmapIcon(bitmap: Bitmap): IconPackDrawable {
        return if (options.themed) {
            bitmapToInset(bitmap)
        } else {
            BitmapIconDrawable(bitmap)
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
            icon.foreground.shrinkIfBiggerThan(maxSize)
        } else {
            icon.shrinkIfBiggerThan(maxSize)
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

    private fun convertBitmapToAdaptiveForeground(bitmap: Bitmap): Bitmap {
        val newBitmap = bitmap.clone()
        val canvas = Canvas(newBitmap)

        canvas.scale(0.5f, 0.5f, bitmap.width * 0.5f, bitmap.height * 0.5f)
        canvas.drawBitmap(bitmap, 0F, 0F, Paint())

        return newBitmap
    }

    private fun addBackground(image: Bitmap): Bitmap {
        return if (options.themed) image.changeBackgroundColor(options.bgColor) else image
    }

    private fun exportIconPackXML(iconPackName: String, iconDrawable: ResourceDrawable): Drawable? {
        if (!isVectorDrawable(iconDrawable.drawable)) return null

        val res = ApplicationManager(ctx).getResources(iconPackName) ?: return null

        //val adaptiveIcon = AdaptiveIconParser.parse(res, parser.toXmlNode()) ?: return null
        val icon = IconParser.parseDrawable(res, iconDrawable.drawable, iconDrawable.resourceId)

        if (!icon.isAdaptiveIconDrawable()) return null

        val adaptiveIcon = icon as AdaptiveIconDrawable
        var vectorIcon: ImageVectorDrawable? = null
        var inset: InsetIconDrawable? = null

        if (adaptiveIcon.foreground is InsetIconDrawable) {
            inset = adaptiveIcon.foreground as InsetIconDrawable
            if (inset.drawable is ImageVectorDrawable) {
                vectorIcon = inset.drawable as ImageVectorDrawable
            }
        }

        if (adaptiveIcon.foreground is ImageVectorDrawable) {
            vectorIcon = adaptiveIcon.foreground as ImageVectorDrawable
        }

        if (vectorIcon == null) {
            return null
        }

        val mutableVector = vectorIcon.resizeAndCenter()

        val stroke = mutableVector.viewportHeight / 48 //1F at 48
        mutableVector.root.editStrokePaths(stroke)

        if (inset != null)
            return inset

        return vectorIcon
    }

    private fun colorizeImage(bitmapIcon: Bitmap, parsedIcon: Drawable?, mode: PorterDuff.Mode): IconPackDrawable? {
        return if (parsedIcon is ImageVectorDrawable) {
            colorizeVector(parsedIcon)
        } else {
            BitmapIconDrawable(colorizeBitmap(bitmapIcon, mode))
        }
    }

    private fun colorizeVector(vectorIcon: ImageVectorDrawable): ImageVectorDrawable {
        vectorIcon.root.editPathColors(SolidColor(Color.Unspecified), SolidColor(Color(options.color)))
        vectorIcon.tintColor = Color.Unspecified

        return vectorIcon
    }

    private fun applicationShouldBeSkipped(app: PackageInfoStruct): Boolean {
        return !options.override && app.createdIcon != null
    }
}