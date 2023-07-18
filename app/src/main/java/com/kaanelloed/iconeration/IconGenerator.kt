package com.kaanelloed.iconeration

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.VectorDrawable
import androidx.core.graphics.drawable.toBitmap
import com.caverock.androidsvg.SVG
import jankovicsandras.imagetracerandroid.ImageTracerAndroid

class IconGenerator(private val ctx: Context, private val apps: Array<PackageInfoStruct>, private val color: Int) {
    private var applyColorOnAvailable = false

    fun generateIcons(type: GenerationType) {
        applyColorOnAvailable = PreferencesHelper(ctx).getApplyColorAvailableIcon()

        when (type) {
            GenerationType.PathDetection -> generateColorQuantizationDetection()
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

    private fun generateColorQuantizationDetection() {
        for (app in apps) {
            if (app.source == PackageInfoStruct.PackageSource.Device) {
                val options = HashMap<String, Float>()
                //options["numberofcolors"] = 64f
                options["colorsampling"] = 0f
                val svgString = ImageTracerAndroid.imageToSVG(getAppIconBitmap(app), options, null)!!

                val svg = SVG.getFromString(svgString)

                val bmp = Bitmap.createBitmap(app.icon.intrinsicWidth, app.icon.intrinsicHeight, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)

                svg.renderToCanvas(canvas)

                app.genIcon = bmp
            } else changeIconPackColor(app)
        }
    }

    private fun getAppIconBitmap(app: PackageInfoStruct): Bitmap {
        var newIcon = app.icon

        if (newIcon is AdaptiveIconDrawable) {
            val adapIcon = newIcon as AdaptiveIconDrawable

            if (adapIcon.foreground is BitmapDrawable || adapIcon.foreground is VectorDrawable)
                newIcon = ForegroundIconDrawable(adapIcon.foreground)

            //if (VERSION.SDK_INT >= VERSION_CODES.TIRAMISU && adapIcon.monochrome != null)
            //    newIcon = ForegroundIconDrawable(adapIcon.monochrome!!)
        }

        return newIcon.toBitmap()
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