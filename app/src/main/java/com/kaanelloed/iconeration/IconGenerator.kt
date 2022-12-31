package com.kaanelloed.iconeration

import android.content.Context
import android.graphics.Color
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
        var firstLetter: LetterGenerator
        for (app in apps) {
            if (app.source == PackageInfoStruct.PackageSource.Device) {
                firstLetter = LetterGenerator(ctx)
                app.genIcon = firstLetter.generateFirstLetter(app.appName).toBitmap()
            }
        }
    }

    enum class GenerationType {
        EdgeDetection,
        FirstLetter
    }
}