package com.kaanelloed.iconeration.icon

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Base64
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import com.kaanelloed.iconeration.vector.MutableImageVector
import com.kaanelloed.iconeration.vector.MutableImageVector.Companion.toMutableImageVector
import com.kaanelloed.iconeration.vector.VectorEditor.Companion.center
import com.kaanelloed.iconeration.vector.VectorEditor.Companion.editFillPaths
import com.kaanelloed.iconeration.vector.VectorEditor.Companion.editStrokePaths
import com.kaanelloed.iconeration.vector.VectorEditor.Companion.resizeTo
import com.kaanelloed.iconeration.vector.VectorExporter.Companion.toXml
import com.kaanelloed.iconeration.vector.VectorRenderer.Companion.renderToCanvas

class VectorIcon(
    val vector: ImageVector
    , exportAsAdaptiveIcon: Boolean = false
    , val useFillColor: Boolean = false
): ExportableIcon(exportAsAdaptiveIcon) {
    constructor(mutableVector: MutableImageVector) : this(mutableVector.toImageVector())

    @Composable
    override fun getPainter(): Painter {
        return rememberVectorPainter(vector)
    }

    override fun toBitmap(): Bitmap {
        return render()
    }

    override fun toDbString(): String {
        return convertToBase64()
    }

    fun formatVector(brush: Brush): ImageVector {
        return vector.toMutableImageVector().also {
            if (useFillColor) {
                it.root.editFillPaths(brush)
            } else {
                it.root.editStrokePaths(brush)
            }
        }.toImageVector()
    }

    private fun render(): Bitmap {
        val mutableVector = vector.toMutableImageVector()
        val bmp = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        mutableVector.resizeTo(256F, 256F).center()
        mutableVector.renderToCanvas(canvas, fillVector = useFillColor)
        return bmp
    }

    private fun convertToBase64(): String {
        val bytes = vector.toXml()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}