package com.kaanelloed.iconeration

import android.content.Context
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LightingColorFilter
import android.graphics.PorterDuff
import androidx.core.graphics.drawable.toBitmap

class IconGenerator(private val ctx: Context, private val apps: Array<PackageInfoStruct>, private val color: Int) {
    fun generateIcons(type: GenerationType) {
        when (type) {
            GenerationType.EdgeDetection -> generateEdgeDetection()
            GenerationType.FirstLetter -> generateFirstLetter()
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
            }
        }
    }

    private fun generateFirstLetter() {
        for (app in apps) {
            if (app.source == PackageInfoStruct.PackageSource.Device) {
                val draw = LetterGenerator(ctx).generateFirstLetter(app.appName)
                draw.colorFilter = LightingColorFilter(color, color)
                app.genIcon = draw.toBitmap(256, 256)
            }
        }
    }

    enum class GenerationType {
        EdgeDetection,
        FirstLetter
    }
}