package com.kaanelloed.iconeration

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import androidx.core.graphics.drawable.toBitmap
import com.caverock.androidsvg.SVG
import jankovicsandras.imagetracerandroid.ImageTracerAndroid

class IconGenerator(private val ctx: Context, private val apps: Array<PackageInfoStruct>, private val color: Int) {
    private var applyColorOnAvailable = false

    fun generateIcons(type: GenerationType) {
        applyColorOnAvailable = PreferencesHelper(ctx).getApplyColorAvailableIcon()

        when (type) {
            GenerationType.EdgeDetection -> generateColorQuantizationDetection()
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
                    app.icon.toBitmap(),
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
                val svgString = ImageTracerAndroid.imageToSVG(app.icon.toBitmap(), options, null)!!

                val svg = SVG.getFromString(svgString)

                val bmp = Bitmap.createBitmap(app.icon.intrinsicWidth, app.icon.intrinsicHeight, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)

                svg.renderToCanvas(canvas)

                app.genIcon = bmp
            } else changeIconPackColor(app)
        }
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
        EdgeDetection,
        FirstLetter,
        TwoLetters,
        AppName
    }
}