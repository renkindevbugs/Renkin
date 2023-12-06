package com.kaanelloed.iconeration

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
import android.os.Build
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.kaanelloed.iconeration.data.GenerationType
import com.kaanelloed.iconeration.data.IconPackApplication
import com.kaanelloed.iconeration.drawable.ForegroundIconDrawable
import com.kaanelloed.iconeration.icon.AdaptiveIcon
import com.kaanelloed.iconeration.icon.AdaptiveIconParser
import com.kaanelloed.iconeration.icon.BaseIcon
import com.kaanelloed.iconeration.icon.BitmapIcon
import com.kaanelloed.iconeration.icon.IconParser
import com.kaanelloed.iconeration.icon.InsetIcon
import com.kaanelloed.iconeration.icon.VectorIcon
import com.kaanelloed.iconeration.image.edge.CannyEdgeDetector
import com.kaanelloed.iconeration.image.tracer.ImageTracer
import com.kaanelloed.iconeration.vector.MutableImageVector.Companion.toMutableImageVector
import com.kaanelloed.iconeration.vector.MutableVectorGroup
import com.kaanelloed.iconeration.vector.MutableVectorPath
import com.kaanelloed.iconeration.vector.VectorEditor.Companion.scale
import com.kaanelloed.iconeration.xml.XmlParser.Companion.toXmlNode
import org.xmlpull.v1.XmlPullParser

class IconGenerator(
    private val ctx: Context,
    private val activity: MainActivity,
    private val options: GenerationOptions,
    private val iconPackApplications: Map<IconPackApplication, Pair<Int, Drawable>>
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
        }
    }

    fun colorizeFromIconPack(application: PackageInfoStruct, icon: Drawable) {
        if (isVectorDrawable(icon)) {
            getIconPackXML(application)
        } else {
            changeIconPackColor(application, icon)
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
                activity.editApplication(app, app.changeExport(BitmapIcon(edgeDetector.edgesImage)))
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
        val appIcon = IconParser.parseDrawable(appMan.getResources(app.packageName), app.icon, app.iconID)
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

        val stroke = mutableVector.viewportHeight / 108 //1F at 108
        editVectorGroup(mutableVector.root, stroke, SolidColor(Color.Unspecified), SolidColor(Color(options.color)))

        activity.editApplication(app, app.changeExport(VectorIcon(mutableVector.toImageVector(), VectorIcon.RendererOption.SvgDynamic)))
    }

    private fun editVectorGroup(vectorGroup: MutableVectorGroup, stroke: Float, fillColor: Brush, strokeColor: Brush) {
        for (child in vectorGroup.children) {
            if (child is MutableVectorGroup) {
                editVectorGroup(child, stroke, fillColor, strokeColor)
            }

            if (child is MutableVectorPath) {
                child.strokeLineWidth = stroke
                child.fill = fillColor
                child.stroke = strokeColor
            }
        }
    }

    private fun generateColorQuantizationDetection(app: PackageInfoStruct) {
        val imageVector = ImageTracer.imageToVector(getAppIconBitmap(app), ImageTracer.TracingOptions())

        val vector = imageVector.toMutableImageVector()
        vector.viewportWidth = app.icon.intrinsicWidth.toFloat()
        vector.viewportHeight = app.icon.intrinsicHeight.toFloat()

        vector.defaultWidth = app.icon.intrinsicWidth.toFloat().dp
        vector.defaultHeight = app.icon.intrinsicHeight.toFloat().dp

        editVectorGroup(vector.root, 2F, SolidColor(Color.Unspecified), SolidColor(Color(options.color)))
        activity.editApplication(app, app.changeExport(VectorIcon(vector.toImageVector(), VectorIcon.RendererOption.Svg)))
    }

    private fun getAppIconBitmap(app: PackageInfoStruct, maxSize: Int = 1000): Bitmap {
        var newIcon = app.icon

        if (newIcon is AdaptiveIconDrawable) {
            if (newIcon.foreground is BitmapDrawable || newIcon.foreground is VectorDrawable)
                newIcon = ForegroundIconDrawable(newIcon.foreground)

            //if (VERSION.SDK_INT >= VERSION_CODES.TIRAMISU && adapIcon.monochrome != null)
            //    newIcon = ForegroundIconDrawable(adapIcon.monochrome!!)
        }

        val maxWidthOrHeight = kotlin.math.max(newIcon.intrinsicWidth, newIcon.intrinsicHeight)
        if (maxWidthOrHeight > maxSize) {
            val multi = maxSize / maxWidthOrHeight.toFloat()
            val newWidth = (newIcon.intrinsicWidth * multi).toInt()
            val newHeight = (newIcon.intrinsicHeight * multi).toInt()

            return newIcon.toBitmap(newWidth, newHeight)
        }

        return newIcon.toBitmap()
    }

    private fun isVectorDrawable(image: Drawable): Boolean {
        if (image is VectorDrawable)
            return true

        if (image is AdaptiveIconDrawable) {
            if (image.foreground is VectorDrawable)
                return true

            if (image.foreground is InsetDrawable) {
                val inset = image.foreground as InsetDrawable
                if (inset.drawable!! is VectorDrawable)
                    return true
            }

            return monochromeExits(image) && options.monochrome
        }

        return false
    }

    private fun monochromeExits(icon: AdaptiveIconDrawable): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            return icon.monochrome != null

        return false
    }

    private fun getAdaptiveDrawableID(adaptiveParser: XmlPullParser, type: String): Int {
        var id = 0

        while (adaptiveParser.eventType != XmlPullParser.END_DOCUMENT) {
            if (adaptiveParser.eventType == XmlPullParser.START_TAG) {
                if (adaptiveParser.name == type) {
                    val drawable = getAttributeValueByName(adaptiveParser, "drawable")!!
                    id = drawable.substring(1).toInt()
                }
            }

            adaptiveParser.next()
        }

        return id
    }

    private fun getAttributeValueByName(parser: XmlPullParser, attributeName: String): String? {
        for (i in 0 until parser.attributeCount) {
            //val namespace = parser.getAttributeNamespace(i)
            val name = parser.getAttributeName(i)
            val value = parser.getAttributeValue(i)

            if (name == attributeName)
                return value
        }

        return null
    }

    private fun generateFirstLetter() {
        val gen = LetterGenerator(ctx)

        for (app in apps) {
            val iconPack = iconPackApplicationIcon(app.packageName)

            if (iconPack == null) {
                val draw = gen.generateFirstLetter(app.normalizeName())
                draw.colorFilter = PorterDuffColorFilter(options.color, PorterDuff.Mode.SRC_IN)
                val newIcon = draw.toBitmap(256, 256)
                activity.editApplication(app, app.changeExport(BitmapIcon(addBackground(newIcon))))
            } else changeIconPackColor(app, iconPack)
        }
    }

    private fun generateTwoLetter() {
        val gen = LetterGenerator(ctx)

        for (app in apps) {
            val iconPack = iconPackApplicationIcon(app.packageName)

            if (iconPack == null) {
                val draw = gen.generateTwoLetters(app.appName, options.color)
                val newIcon = draw.toBitmap(256, 256)
                activity.editApplication(app, app.changeExport(BitmapIcon(addBackground(newIcon))))
            } else changeIconPackColor(app, iconPack)
        }
    }

    private fun generateAppName() {
        val gen = LetterGenerator(ctx)

        for (app in apps) {
            val iconPack = iconPackApplicationIcon(app.packageName)

            if (iconPack == null) {
                val draw = gen.generateAppName(app.appName, options.color)
                val newIcon = draw.toBitmap(256, 256)
                activity.editApplication(app, app.changeExport(BitmapIcon(addBackground(newIcon))))
            } else changeIconPackColor(app, iconPack)
        }
    }

    private fun changeIconPackColor(app: PackageInfoStruct, icon: Drawable) {
        val coloredIcon = colorIcon(icon)
        activity.editApplication(app, app.changeExport(BitmapIcon(coloredIcon)))
    }

    private fun colorIcon(icon: Drawable): Bitmap {
        val oldIcon = if (icon is AdaptiveIconDrawable) {
            ForegroundIconDrawable(icon.foreground).toBitmap()
        } else {
            icon.toBitmap()
        }

        val coloredIcon = oldIcon.copy(oldIcon.config, true)
        val paint = Paint()

        paint.colorFilter = PorterDuffColorFilter(options.color, PorterDuff.Mode.SRC_IN)
        Canvas(coloredIcon).drawBitmap(coloredIcon, 0F, 0F, paint)

        return addBackground(coloredIcon)
    }

    private fun addBackground(image: Bitmap): Bitmap {
        return if (options.themed) image.changeBackgroundColor(options.bgColor) else image
    }

    private fun getIconPackXML(app: PackageInfoStruct) {
        val iconPackApp = iconPackApplication(app.packageName)!!
        val iconID = iconPackApplicationIconID(app.packageName)
        val icon = iconPackApplicationIcon(app.packageName)
        val parser = ApplicationManager(ctx).getPackageResourceXml(iconPackApp.iconPackName, iconID)!!

        val adaptiveIcon = AdaptiveIconParser.parse(ApplicationManager(ctx).getResources(iconPackApp.iconPackName), parser.toXmlNode())!!
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
            TODO()
        }

        val mutableVector = vectorIcon.vector.scale(0.5f).toMutableImageVector()

        val stroke = mutableVector.viewportHeight / 108 //1F at 108
        editVectorGroup(mutableVector.root, stroke, SolidColor(Color.Unspecified), SolidColor(Color(options.color)))

        activity.editApplication(app, app.changeExport(VectorIcon(mutableVector.toImageVector())))
    }

    private fun iconPackApplication(packageName: String): IconPackApplication? {
        return iconPackApplications.keys.find { it.packageName == packageName }
    }

    private fun iconPackApplicationIcon(packageName: String): Drawable? {
        val app = iconPackApplication(packageName)

        if (app != null) {
            return iconPackApplications[app]!!.second
        }

        return null
    }

    private fun iconPackApplicationIconID(packageName: String): Int {
        val app = iconPackApplication(packageName)

        if (app != null) {
            return iconPackApplications[app]!!.first
        }

        return -1
    }

    class GenerationOptions(
        val color: Int,
        val monochrome: Boolean,
        val vector: Boolean,
        val themed: Boolean = false,
        val bgColor: Int = 0
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