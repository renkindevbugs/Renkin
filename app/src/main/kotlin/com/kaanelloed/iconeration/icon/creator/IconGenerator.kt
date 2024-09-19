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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.kaanelloed.iconeration.MainActivity
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
import com.kaanelloed.iconeration.image.tracer.ImageTracer
import com.kaanelloed.iconeration.packages.ApplicationManager
import com.kaanelloed.iconeration.packages.PackageInfoStruct
import com.kaanelloed.iconeration.packages.PackageVersion
import com.kaanelloed.iconeration.vector.MutableImageVector.Companion.toMutableImageVector
import com.kaanelloed.iconeration.vector.PathConverter.Companion.toNodes
import com.kaanelloed.iconeration.vector.VectorEditor.Companion.applyAndRemoveGroup
import com.kaanelloed.iconeration.vector.VectorEditor.Companion.editPaths
import com.kaanelloed.iconeration.vector.VectorEditor.Companion.resizeAndCenter
import com.kaanelloed.iconeration.vector.VectorEditor.Companion.scaleAtCenter
import com.kaanelloed.iconeration.xml.XmlParser.Companion.toXmlNode

class IconGenerator(
    private val ctx: Context,
    private val activity: MainActivity,
    private val options: GenerationOptions,
    private val iconPackName: String,
    private val iconPackApplications: Map<InstalledApplication, ResourceDrawable>
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

    fun colorizeFromIconPack(application: PackageInfoStruct, icon: Drawable) {
        if (isVectorDrawable(icon)) {
            if (!exportIconPackXML(application)) {
                changeIconPackColor(application, icon)
            }
        } else {
            changeIconPackColor(application, icon)
        }
    }

    private fun generateOnlyIconPack() {
        for (app in apps) {
            val iconPack = iconPackApplicationIcon(app.packageName)

            if (iconPack != null) {
                colorizeFromIconPack(app, iconPack)
            } else {
                emptyApplication(app)
            }
        }
    }

    private fun generateCannyEdgeDetection() {
        var edgeDetector: CannyEdgeDetector
        for (app in apps) {
            val iconPack = iconPackApplicationIcon(app.packageName)

            if (iconPack == null) {
                edgeDetector = CannyEdgeDetector()
                edgeDetector.process(
                    getAppIconBitmap(app),
                    options.color
                )
                updateApplication(app, BitmapIcon(edgeDetector.edgesImage))
            } else changeIconPackColor(app, iconPack)
        }
    }

    private fun generatePathDetection() {
        val appMan = ApplicationManager(ctx)

        for (app in apps) {
            val iconPack = iconPackApplicationIcon(app.packageName)

            if (iconPack == null) {
                if (isVectorDrawable(app.icon) && options.vector) {
                    generatePathFromXML(appMan, app)
                } else {
                    generateColorQuantizationDetection(app)
                }
            } else colorizeFromIconPack(app, iconPack)
        }
    }

    private fun generatePathFromXML(appMan: ApplicationManager, app: PackageInfoStruct) {
        val res = appMan.getResources(app.packageName)

        if (res == null) {
            generateColorQuantizationDetection(app)
            return
        }

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
            generateColorQuantizationDetection(app)
            return
        }

        val mutableVector = vectorIcon.vector.toMutableImageVector()

        val stroke = mutableVector.viewportHeight / 48 //1F at 48
        mutableVector.root.editPaths(stroke, SolidColor(Color.Unspecified), SolidColor(Color(options.color)))
        mutableVector.resizeAndCenter().applyAndRemoveGroup().scaleAtCenter(6F / 4F)
        mutableVector.tintColor = Color.Unspecified

        if (options.themed) {
            mutableVector.scaleAtCenter(0.5F)
        }

        updateApplication(app, VectorIcon(mutableVector))
    }

    private fun generateColorQuantizationDetection(app: PackageInfoStruct) {
        val imageVector = ImageTracer.imageToVector(getAppIconBitmap(app), ImageTracer.TracingOptions())

        val vector = imageVector.toMutableImageVector()
        val stroke = imageVector.viewportHeight / 48 //1F at 48
        vector.root.editPaths(stroke, SolidColor(Color.Unspecified), SolidColor(Color(options.color)))
        vector.resizeAndCenter()

        if (options.themed) {
            vector.scaleAtCenter(0.5F)
        }

        updateApplication(app, VectorIcon(vector))
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
            val iconPack = iconPackApplicationIcon(app.packageName)

            if (iconPack == null) {
                val draw = gen.generateFirstLetter(app.appName, options.color, strokeWidth, size)
                val newIcon = draw.toBitmap(size, size)
                updateApplication(app, BitmapIcon(addBackground(newIcon)))
            } else changeIconPackColor(app, iconPack)
        }
    }

    private fun generateTwoLetter() {
        val size = 256
        val strokeWidth = size / 48F
        val gen = LetterGenerator(ctx)

        for (app in apps) {
            val iconPack = iconPackApplicationIcon(app.packageName)

            if (iconPack == null) {
                val draw = gen.generateTwoLetters(app.appName, options.color, strokeWidth, size)
                val newIcon = draw.toBitmap(size, size)
                updateApplication(app, BitmapIcon(addBackground(newIcon)))
            } else changeIconPackColor(app, iconPack)
        }
    }

    private fun generateAppName() {
        val size = 256
        val gen = LetterGenerator(ctx)

        for (app in apps) {
            val iconPack = iconPackApplicationIcon(app.packageName)

            if (iconPack == null) {
                val draw = gen.generateAppName(app.appName, options.color, size)
                val newIcon = draw.toBitmap(size, size)
                updateApplication(app, BitmapIcon(addBackground(newIcon)))
            } else changeIconPackColor(app, iconPack)
        }
    }

    private fun createVectorForText(drawable: BaseTextDrawable): ImageVector {
        val builder = ImageVector.Builder(defaultWidth = 256.dp, defaultHeight = 256.dp, viewportWidth = 256F, viewportHeight = 256F)

        val paths = drawable.getPaths()
        for (path in paths) {
            val cPath = path.asComposePath()
            builder.addPath(cPath.toNodes())
        }

        return builder.build()
    }

    private fun changeIconPackColor(app: PackageInfoStruct, icon: Drawable) {
        val isAdaptiveIcon = icon.isAdaptiveIconDrawable()

        if (options.colorizeIconPack) {
            val coloredIcon = colorIcon(icon)
            updateApplication(app, BitmapIcon(coloredIcon, isAdaptiveIcon))
        }
        else {
            val iconToShow = getIconBitmap(icon)
            updateApplication(app, BitmapIcon(iconToShow, isAdaptiveIcon))
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

    private fun exportIconPackXML(app: PackageInfoStruct): Boolean {
        iconPackApplication(app.packageName) ?: return false

        val res = ApplicationManager(ctx).getResources(iconPackName) ?: return false

        val iconID = iconPackApplicationIconID(app.packageName)
        val parser = ApplicationManager(ctx).getPackageResourceXml(iconPackName, iconID) ?: return false

        val adaptiveIcon = AdaptiveIconParser.parse(res, parser.toXmlNode()) ?: return false
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
            return false
        }

        val mutableVector = vectorIcon.vector.toMutableImageVector().resizeAndCenter().scaleAtCenter(0.5f)

        val stroke = mutableVector.viewportHeight / 48 //1F at 48
        if (options.colorizeIconPack) {
            mutableVector.root.editPaths(stroke, SolidColor(Color.Unspecified), SolidColor(Color(options.color)))
            mutableVector.tintColor = Color.Unspecified
        }
        else {
            mutableVector.root.editPaths(stroke)
        }

        updateApplication(app, VectorIcon(mutableVector))
        return true
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

    private fun updateApplication(application: PackageInfoStruct, icon: ExportableIcon) {
        activity.editApplication(application, application.changeExport(icon))
    }

    private fun emptyApplication(application: PackageInfoStruct) {
        updateApplication(application, EmptyIcon())
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