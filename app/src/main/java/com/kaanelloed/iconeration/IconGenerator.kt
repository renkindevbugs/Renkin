package com.kaanelloed.iconeration

import android.content.Context
import android.graphics.*
import androidx.core.graphics.drawable.toBitmap


class IconGenerator(private val ctx: Context, private val apps: Array<PackageInfoStruct>, private val color: Int) {
    fun generateIcons(type: GenerationType) {
        when (type) {
            GenerationType.EdgeDetection -> generateEdgeDetection()
            GenerationType.FirstLetter -> generateFirstLetter()
            GenerationType.TwoLetters -> generateTwoLetter()
            GenerationType.AppName -> generateAppName()
        }
    }

    private fun generateEdgeDetection() {
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

    private fun generateFirstLetter() {
        val gen = LetterGenerator(ctx)

        for (app in apps) {
            if (app.source == PackageInfoStruct.PackageSource.Device) {
                val draw = gen.generateFirstLetter(app.normalizeName())
                draw.colorFilter = LightingColorFilter(color, color)
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
        val coloredIcon: Bitmap = app.genIcon.copy(app.genIcon.config, true)
        val paint = Paint()

        paint.colorFilter = LightingColorFilter(color, color)
        Canvas(coloredIcon).drawBitmap(coloredIcon, 0F, 0F, paint)

        app.genIcon = coloredIcon
    }

    enum class GenerationType {
        EdgeDetection,
        FirstLetter,
        TwoLetters,
        AppName
    }
}