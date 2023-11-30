package com.kaanelloed.iconeration

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.VectorDrawable
import android.os.Build
import androidx.core.graphics.drawable.toBitmap
import com.caverock.androidsvg.SVG
import com.kaanelloed.iconeration.data.GenerationType
import com.kaanelloed.iconeration.data.IconPackApplication
import com.kaanelloed.iconeration.image.edge.CannyEdgeDetector
import com.kaanelloed.iconeration.image.tracer.ImageTracer
import org.xmlpull.v1.XmlPullParser

class IconGenerator(
    private val ctx: Context,
    private val activity: MainActivity,
    private val options: GenerationOptions,
    private val iconPackApplications: Map<IconPackApplication, Drawable>
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
        changeIconPackColor(application, icon)
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
                activity.editApplication(app, app.changeExport(PackageInfoStruct.Export(edgeDetector.edgesImage)))
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
            } else changeIconPackColor(app, iconPack)
        }
    }

    private fun generatePathFromXML(appMan: ApplicationManager, app: PackageInfoStruct) {
        var parser = appMan.getPackageResourceXml(app.packageName, app.iconID)

        if (app.icon is AdaptiveIconDrawable && parser != null) {
            val adaptiveIcon = app.icon as AdaptiveIconDrawable

            val monoParser = if (monochromeExits(adaptiveIcon) && options.monochrome) {
                appMan.getPackageResourceXml(app.packageName, getMonochromeXMLID(parser))
            }
            else { null }

            parser = monoParser ?: appMan.getPackageResourceXml(app.packageName, getForegroundXMLID(parser))
        }

        if (parser == null) {
            generateColorQuantizationDetection(app)
            return
        }

        val vec = VectorHandler()
        vec.parse(parser)

        val stroke = vec.vector.viewportHeight / 108 //1F at 108

        val fillColor = ColorResource()
        fillColor.fromInt(Color.TRANSPARENT)

        val strokeColor = ColorResource()
        strokeColor.fromInt(options.color)

        for (grp in vec.vector.groups) {
            for (path in grp.paths) {
                path.strokeWidth = stroke
                path.fillColor = fillColor
                path.strokeColor = strokeColor
            }
        }

        for (path in vec.vector.paths) {
            path.strokeWidth = stroke
            path.fillColor = fillColor
            path.strokeColor = strokeColor
        }

        val svg = SVG.getFromString(vec.toSVG())
        //svg.setDocumentViewBox(18F, 18F, 72F, 72F) //AdaptiveIconDrawable size

        val offset = vec.vector.viewportHeight / 6
        svg.setDocumentViewBox(offset, offset, offset * 4, offset * 4)

        val newIcon = SVGDrawable(svg).toBitmap(198, 198)
        activity.editApplication(app, app.changeExport(PackageInfoStruct.Export(newIcon, vec)))
    }

    private fun generateColorQuantizationDetection(app: PackageInfoStruct) {
        val svgString = ImageTracer.imageToSVG(getAppIconBitmap(app), ImageTracer.TracingOptions())

        val vector = VectorHandler()
        vector.parseSvg(svgString)

        vector.vector.viewportWidth = app.icon.intrinsicWidth.toFloat()
        vector.vector.viewportHeight = app.icon.intrinsicHeight.toFloat()

        vector.vector.width += "dp"
        vector.vector.height += "dp"

        val fillColor = ColorResource()
        fillColor.fromInt(Color.TRANSPARENT)

        val strokeColor = ColorResource()
        strokeColor.fromInt(options.color)

        for (path in vector.vector.paths) {
            path.strokeWidth = 2F
            path.fillColor = fillColor
            path.strokeColor = strokeColor
        }

        val newSvg = vector.toSVG()
        val svg = SVG.getFromString(newSvg)

        val bmp = Bitmap.createBitmap(app.icon.intrinsicWidth, app.icon.intrinsicHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        svg.renderToCanvas(canvas)

        activity.editApplication(app, app.changeExport(PackageInfoStruct.Export(bmp, vector)))
    }

    private fun getAppIconBitmap(app: PackageInfoStruct): Bitmap {
        var newIcon = app.icon

        if (newIcon is AdaptiveIconDrawable) {
            if (newIcon.foreground is BitmapDrawable || newIcon.foreground is VectorDrawable)
                newIcon = ForegroundIconDrawable(newIcon.foreground)

            //if (VERSION.SDK_INT >= VERSION_CODES.TIRAMISU && adapIcon.monochrome != null)
            //    newIcon = ForegroundIconDrawable(adapIcon.monochrome!!)
        }

        return newIcon.toBitmap()
    }

    private fun isVectorDrawable(image: Drawable): Boolean {
        if (image is VectorDrawable)
            return true

        if (image is AdaptiveIconDrawable) {
            if (image.foreground is VectorDrawable)
                return true

            return monochromeExits(image) && options.monochrome
        }

        return false
    }

    private fun getForegroundXMLID(adaptiveParser: XmlPullParser): Int {
        return getAdaptiveDrawableID(adaptiveParser, "foreground")
    }

    private fun getMonochromeXMLID(adaptiveParser: XmlPullParser): Int {
        return getAdaptiveDrawableID(adaptiveParser, "monochrome")
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
            val namespace = parser.getAttributeNamespace(i)
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
                activity.editApplication(app, app.changeExport(PackageInfoStruct.Export(newIcon)))
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
                activity.editApplication(app, app.changeExport(PackageInfoStruct.Export(newIcon)))
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
                activity.editApplication(app, app.changeExport(PackageInfoStruct.Export(newIcon)))
            } else changeIconPackColor(app, iconPack)
        }
    }

    private fun changeIconPackColor(app: PackageInfoStruct, icon: Drawable) {
        val oldIcon = if (icon is AdaptiveIconDrawable) {
            ForegroundIconDrawable(icon.foreground).toBitmap()
        } else {
            icon.toBitmap()
        }

        val coloredIcon: Bitmap = oldIcon.copy(oldIcon.config, true)
        val paint = Paint()

        paint.colorFilter = PorterDuffColorFilter(options.color, PorterDuff.Mode.SRC_IN)
        Canvas(coloredIcon).drawBitmap(coloredIcon, 0F, 0F, paint)

        activity.editApplication(app, app.changeExport(PackageInfoStruct.Export(coloredIcon)))
    }

    private fun iconPackApplicationIcon(packageName: String): Drawable? {
        val app = iconPackApplications.keys.find { it.packageName == packageName }

        if (app != null) {
            return iconPackApplications[app]
        }

        return null
    }

    class GenerationOptions(val color: Int, val monochrome: Boolean, val vector: Boolean) { }
}