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
import jankovicsandras.imagetracerandroid.ImageTracerAndroid
import org.xmlpull.v1.XmlPullParser

class IconGenerator(private val ctx: Context, private val apps: Array<PackageInfoStruct>, private val color: Int) {
    private var applyColorOnAvailable = false
    private val useMonochrome = PreferencesHelper(ctx).getUseMonochrome()
    private val includeVector = PreferencesHelper(ctx).getIncludeVector()

    fun generateIcons(type: GenerationType) {
        applyColorOnAvailable = PreferencesHelper(ctx).getApplyColorAvailableIcon()

        when (type) {
            GenerationType.PathDetection -> generatePathDetection()
            GenerationType.EdgeDetection -> generateCannyEdgeDetection()
            GenerationType.FirstLetter -> generateFirstLetter()
            GenerationType.TwoLetters -> generateTwoLetter()
            GenerationType.AppName -> generateAppName()
        }
    }

    private fun generateCannyEdgeDetection() {
        var edgeDetector: CannyEdgeDetector
        for (app in apps) {
            if (app.source == PackageInfoStruct.PackageSource.Device) {
                edgeDetector = CannyEdgeDetector()
                edgeDetector.process(
                    getAppIconBitmap(app),
                    color
                )
                app.genIcon = edgeDetector.edgesImage
            } else changeIconPackColor(app)
        }
    }

    private fun generatePathDetection() {
        val appMan = ApplicationManager(ctx)

        for (app in apps) {
            if (app.source == PackageInfoStruct.PackageSource.Device) {
                app.exportType = PackageInfoStruct.ExportType.XML

                if (isVectorDrawable(app.icon) && includeVector) {
                    generatePathFromXML(appMan, app)
                } else {
                    generateColorQuantizationDetection(app)
                }
            } else changeIconPackColor(app)
        }
    }

    private fun generatePathFromXML(appMan: ApplicationManager, app: PackageInfoStruct) {
        var parser = appMan.getPackageResourceXml(app.packageName, app.iconID)!!

        if (app.icon is AdaptiveIconDrawable) {
            val adaptiveIcon = app.icon as AdaptiveIconDrawable

            parser = if (monochromeExits(adaptiveIcon) && useMonochrome)
                appMan.getPackageResourceXml(app.packageName, getMonochromeXMLID(parser))!!
            else
                appMan.getPackageResourceXml(app.packageName, getForegroundXMLID(parser))!!
        }

        val vec = VectorHandler()
        vec.parse(parser)

        val stroke = vec.vector.viewportHeight / 108 //1F at 108

        val fillColor = ColorResource()
        fillColor.fromInt(Color.TRANSPARENT)

        val strokeColor = ColorResource()
        strokeColor.fromInt(color)

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

        app.genIcon = SVGDrawable(svg).toBitmap(198, 198)
        app.vector = vec
    }

    private fun generateColorQuantizationDetection(app: PackageInfoStruct) {
        val options = HashMap<String, Float>()
        //options["numberofcolors"] = 64f
        options["colorsampling"] = 0f
        val svgString = ImageTracerAndroid.imageToSVG(getAppIconBitmap(app), options, null)!!

        val vector = VectorHandler()
        vector.parseSvg(svgString)

        vector.vector.viewportWidth = app.icon.intrinsicWidth.toFloat()
        vector.vector.viewportHeight = app.icon.intrinsicHeight.toFloat()

        vector.vector.width += "dp"
        vector.vector.height += "dp"

        val fillColor = ColorResource()
        fillColor.fromInt(Color.TRANSPARENT)

        val strokeColor = ColorResource()
        strokeColor.fromInt(color)

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

        app.genIcon = bmp
        app.vector = vector
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

            return monochromeExits(image) && useMonochrome
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
            if (app.source == PackageInfoStruct.PackageSource.Device) {
                val draw = gen.generateFirstLetter(app.normalizeName())
                draw.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
                app.genIcon = draw.toBitmap(256, 256)
            } else changeIconPackColor(app)
        }
    }

    private fun generateTwoLetter() {
        val gen = LetterGenerator(ctx)

        for (app in apps) {
            if (app.source == PackageInfoStruct.PackageSource.Device) {
                val draw = gen.generateTwoLetters(app.appName, color)
                app.genIcon = draw.toBitmap(256, 256)
            } else changeIconPackColor(app)
        }
    }

    private fun generateAppName() {
        val gen = LetterGenerator(ctx)

        for (app in apps) {
            if (app.source == PackageInfoStruct.PackageSource.Device) {
                val draw = gen.generateAppName(app.appName, color)
                app.genIcon = draw.toBitmap(256, 256)
            } else changeIconPackColor(app)
        }
    }

    private fun changeIconPackColor(app: PackageInfoStruct) {
        if (applyColorOnAvailable) {
            val coloredIcon: Bitmap = app.genIcon.copy(app.genIcon.config, true)
            val paint = Paint()

            paint.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
            Canvas(coloredIcon).drawBitmap(coloredIcon, 0F, 0F, paint)

            app.genIcon = coloredIcon
        }
    }

    enum class GenerationType {
        PathDetection,
        EdgeDetection,
        FirstLetter,
        TwoLetters,
        AppName
    }
}