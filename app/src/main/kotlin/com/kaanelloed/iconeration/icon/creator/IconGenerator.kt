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
import com.kaanelloed.iconeration.apk.ApplicationProvider
import com.kaanelloed.iconeration.data.GenerationType
import com.kaanelloed.iconeration.data.InstalledApplication
import com.kaanelloed.iconeration.drawable.BaseTextDrawable
import com.kaanelloed.iconeration.drawable.DrawableExtension.Companion.isAdaptiveIconDrawable
import com.kaanelloed.iconeration.drawable.DrawableExtension.Companion.shrinkIfBiggerThan
import com.kaanelloed.iconeration.drawable.ForegroundIconDrawable
import com.kaanelloed.iconeration.drawable.ResourceDrawable
import com.kaanelloed.iconeration.icon.AdaptiveIcon
import com.kaanelloed.iconeration.icon.parser.AdaptiveIconParser
import com.kaanelloed.iconeration.icon.BaseIcon
import com.kaanelloed.iconeration.icon.BitmapIcon
import com.kaanelloed.iconeration.icon.EmptyIcon
import com.kaanelloed.iconeration.icon.ExportableIcon
import com.kaanelloed.iconeration.icon.parser.IconParser
import com.kaanelloed.iconeration.icon.InsetIcon
import com.kaanelloed.iconeration.icon.VectorIcon
import com.kaanelloed.iconeration.image.edge.CannyEdgeDetector
import com.kaanelloed.iconeration.packages.ApplicationManager
import com.kaanelloed.iconeration.packages.PackageInfoStruct
import com.kaanelloed.iconeration.packages.PackageVersion
import com.kaanelloed.iconeration.vector.MutableImageVector.Companion.toMutableImageVector
import com.kaanelloed.iconeration.vector.PathConverter.Companion.toNodes
import com.kaanelloed.iconeration.vector.VectorEditor.Companion.applyAndRemoveGroup
import com.kaanelloed.iconeration.vector.VectorEditor.Companion.editStrokePaths
import com.kaanelloed.iconeration.vector.VectorEditor.Companion.editPaths
import com.kaanelloed.iconeration.vector.VectorEditor.Companion.resizeAndCenter
import com.kaanelloed.iconeration.vector.VectorEditor.Companion.scaleAtCenter
import com.kaanelloed.iconeration.xml.XmlParser.Companion.toXmlNode
import dev.adevium.imagetracer.ImageTracer

class IconGenerator(
    private val ctx: Context,
    private val appProvider: ApplicationProvider,
    private val options: GenerationOptions,
    private val iconPackName: String,
    private val iconPackApplications: Map<InstalledApplication, ResourceDrawable>,
    private val override: Boolean
) {
    private lateinit var apps: List<PackageInfoStruct>

    fun generateIcons(application: PackageInfoStruct, type: GenerationType) {
        generateIcons(listOf(application), type)
    }

    fun generateIcons(applications: List<PackageInfoStruct>, type: GenerationType) {
        apps = applications

        when (type) {
            GenerationType.PATH -> generatePathDetection()
            GenerationType.EDGE -> generateCannyEdgeDetection()
            GenerationType.ONE_LETTER -> generateFirstLetter()
            GenerationType.TWO_LETTERS -> generateTwoLetter()
            GenerationType.APP_NAME -> generateAppName()
            GenerationType.ICON_PACK_ONLY -> generateOnlyIconPack()
        }
    }

    fun updateFromIconPack(application: PackageInfoStruct, icon: Drawable) {
        val exportIcon = colorizeFromIconPack(application, icon)
        updateApplication(application, exportIcon)
    }

    private fun colorizeFromIconPack(application: PackageInfoStruct, icon: Drawable): ExportableIcon {
        return if (isVectorDrawable(icon)) {
            exportIconPackXML(application) ?: changeIconPackColor(icon)
        } else {
            changeIconPackColor(icon)
        }
    }

    fun colorizeFromIconPack(icon: ResourceDrawable): ExportableIcon {
        return if (isVectorDrawable(icon.drawable)) {
            exportIconPackXML(icon.resourceId) ?: changeIconPackColor(icon.drawable)
        } else {
            changeIconPackColor(icon.drawable)
        }
    }

    private fun generateOnlyIconPack() {
        for (app in apps) {
            if (applicationShouldBeSkipped(app)) {
                continue
            }

            val iconPack = iconPackApplicationIcon(app.packageName)

            val icon = if (iconPack != null) {
                colorizeFromIconPack(app, iconPack)
            } else {
                EmptyIcon()
            }

            updateApplication(app, icon)
        }
    }

    private fun generateCannyEdgeDetection() {
        var edgeDetector: CannyEdgeDetector
        for (app in apps) {
            if (applicationShouldBeSkipped(app)) {
                continue
            }

            val iconPack = iconPackApplicationIcon(app.packageName)

            val icon = if (iconPack == null) {
                edgeDetector = CannyEdgeDetector()
                edgeDetector.process(
                    getAppIconBitmap(app).asImageBitmap(),
                    options.color
                )
                BitmapIcon(edgeDetector.edgesImage)
            } else {
                changeIconPackColor(iconPack)
            }

            updateApplication(app, icon)
        }
    }

    private fun generatePathDetection() {
        val appMan = ApplicationManager(ctx)

        for (app in apps) {
            if (applicationShouldBeSkipped(app)) {
                continue
            }

            val iconPack = iconPackApplicationIcon(app.packageName)

            val icon = if (iconPack == null) {
                if (isVectorDrawable(app.icon) && options.vector) {
                    generatePathFromXML(appMan, app)
                } else {
                    generateColorQuantizationDetection(app)
                }
            } else colorizeFromIconPack(app, iconPack)

            updateApplication(app, icon)
        }
    }

    private fun generatePathFromXML(appMan: ApplicationManager, app: PackageInfoStruct): ExportableIcon {
        val res = appMan.getResources(app.packageName) ?: return generateColorQuantizationDetection(app)

        val appIcon = IconParser.parseDrawable(res, app.icon, app.iconID)
        var vectorIcon: BaseIcon = appIcon

        if (appIcon is AdaptiveIcon) {
            if (appIcon.foreground is VectorIcon) {
                vectorIcon = appIcon.foreground
            }

            if (appIcon.monochrome is VectorIcon && options.monochrome) {
                vectorIcon = appIcon.monochrome
            }
        }

        if (vectorIcon !is VectorIcon) {
            return generateColorQuantizationDetection(app)
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

    private fun generateColorQuantizationDetection(app: PackageInfoStruct): ExportableIcon {
        val imageVector = ImageTracer.imageToVector(getAppIconBitmap(app).asImageBitmap()
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

    private fun generateFirstLetter() {
        val size = 256
        val strokeWidth = size / 48F
        val gen = LetterGenerator(ctx)

        for (app in apps) {
            if (applicationShouldBeSkipped(app)) {
                continue
            }

            val iconPack = iconPackApplicationIcon(app.packageName)

            val icon = if (iconPack == null) {
                val draw = gen.generateFirstLetter(app.appName, options.color, strokeWidth, size)
                val newIcon = createVectorForText(draw as BaseTextDrawable, options.color, strokeWidth, size)
                VectorIcon(newIcon)
            } else {
                changeIconPackColor(iconPack)
            }

            updateApplication(app, icon)
        }
    }

    private fun generateTwoLetter() {
        val size = 256
        val strokeWidth = size / 48F
        val gen = LetterGenerator(ctx)

        for (app in apps) {
            if (applicationShouldBeSkipped(app)) {
                continue
            }

            val iconPack = iconPackApplicationIcon(app.packageName)

            val icon = if (iconPack == null) {
                val draw = gen.generateTwoLetters(app.appName, options.color, strokeWidth, size)
                val newIcon = createVectorForText(draw as BaseTextDrawable, options.color, strokeWidth, size)
                VectorIcon(newIcon)
            } else {
                changeIconPackColor(iconPack)
            }

            updateApplication(app, icon)
        }
    }

    private fun generateAppName() {
        val size = 256
        val gen = LetterGenerator(ctx)

        for (app in apps) {
            if (applicationShouldBeSkipped(app)) {
                continue
            }

            val iconPack = iconPackApplicationIcon(app.packageName)

            val icon = if (iconPack == null) {
                val draw = gen.generateAppName(app.appName, options.color, size)
                val newIcon = createVectorForMultiLineText(draw as BaseTextDrawable, options.color, size)
                VectorIcon(newIcon, useFillColor = true)
            } else {
                changeIconPackColor(iconPack)
            }

            updateApplication(app, icon)
        }
    }

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

    private fun changeIconPackColor(icon: Drawable): ExportableIcon {
        val isAdaptiveIcon = icon.isAdaptiveIconDrawable()

        return if (options.colorizeIconPack) {
            val coloredIcon = colorIcon(icon)
            BitmapIcon(coloredIcon, isAdaptiveIcon)
        }
        else {
            val iconToShow = getIconBitmap(icon)
            BitmapIcon(iconToShow, isAdaptiveIcon)
        }
    }

    private fun getIconBitmap(icon: Drawable, maxSize: Int = 500): Bitmap {
        return if (icon.isAdaptiveIconDrawable()) {
            icon as AdaptiveIconDrawable
            icon.foreground.shrinkIfBiggerThan(maxSize)
        } else {
            icon.shrinkIfBiggerThan(maxSize)
        }
    }

    private fun colorIcon(icon: Drawable): Bitmap {
        val oldIcon = getIconBitmap(icon)

        val coloredIcon = oldIcon.copy(oldIcon.config, true)
        val paint = Paint()

        paint.colorFilter = PorterDuffColorFilter(options.color, PorterDuff.Mode.SRC_IN)
        Canvas(coloredIcon).drawBitmap(coloredIcon, 0F, 0F, paint)

        return addBackground(coloredIcon)
    }

    private fun addBackground(image: Bitmap): Bitmap {
        return if (options.themed) image.changeBackgroundColor(options.bgColor) else image
    }

    private fun exportIconPackXML(app: PackageInfoStruct): ExportableIcon? {
        iconPackApplication(app.packageName) ?: return null
        val iconID = iconPackApplicationIconID(app.packageName)

        return exportIconPackXML(iconID)
    }

    private fun exportIconPackXML(iconID: Int): ExportableIcon? {
        val res = ApplicationManager(ctx).getResources(iconPackName) ?: return null
        val parser = ApplicationManager(ctx).getPackageResourceXml(iconPackName, iconID) ?: return null

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
        if (options.colorizeIconPack) {
            mutableVector.root.editPaths(stroke, SolidColor(Color.Unspecified), SolidColor(Color(options.color)))
            mutableVector.tintColor = Color.Unspecified
        }
        else {
            mutableVector.root.editStrokePaths(stroke)
        }

        return VectorIcon(mutableVector)
    }

    private fun iconPackApplication(packageName: String): InstalledApplication? {
        return iconPackApplications.keys.find { it.packageName == packageName }
    }

    private fun iconPackApplicationIcon(packageName: String): Drawable? {
        val app = iconPackApplication(packageName)

        if (app != null) {
            return iconPackApplications[app]!!.drawable
        }

        return null
    }

    private fun iconPackApplicationIconID(packageName: String): Int {
        val app = iconPackApplication(packageName)

        if (app != null) {
            return iconPackApplications[app]!!.resourceId
        }

        return -1
    }

    private fun applicationShouldBeSkipped(app: PackageInfoStruct): Boolean {
        return !override && app.createdIcon !is EmptyIcon
    }

    private fun updateApplication(application: PackageInfoStruct, icon: ExportableIcon) {
        appProvider.editApplication(application, application.changeExport(icon))
    }

    class GenerationOptions(
        val color: Int,
        val monochrome: Boolean,
        val vector: Boolean,
        val themed: Boolean = false,
        val bgColor: Int = 0,
        val colorizeIconPack: Boolean = false
    )

    private fun Bitmap.changeBackgroundColor(color: Int): Bitmap {
        val newBitmap = Bitmap.createBitmap(width, height, config)
        val canvas = Canvas(newBitmap)
        canvas.drawColor(color)
        canvas.drawBitmap(this, 0F, 0F, null)
        recycle()
        return newBitmap
    }
}