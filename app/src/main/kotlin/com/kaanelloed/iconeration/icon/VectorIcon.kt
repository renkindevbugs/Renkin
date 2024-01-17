package com.kaanelloed.iconeration.icon

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import com.kaanelloed.iconeration.vector.MutableImageVector
import com.kaanelloed.iconeration.vector.MutableImageVector.Companion.toMutableImageVector
import com.kaanelloed.iconeration.vector.VectorEditor.Companion.center
import com.kaanelloed.iconeration.vector.VectorEditor.Companion.resizeTo
import com.kaanelloed.iconeration.vector.VectorRenderer.Companion.renderToCanvas

class VectorIcon(val vector: ImageVector, exportAsAdaptiveIcon: Boolean = false): ExportableIcon(exportAsAdaptiveIcon) {
    constructor(mutableVector: MutableImageVector) : this(mutableVector.toImageVector())

    @Composable
    override fun getPainter(): Painter {
        return rememberVectorPainter(vector)
    }

    override fun toBitmap(): Bitmap {
        return render()
    }

    private fun render(): Bitmap {
        val mutableVector = vector.toMutableImageVector()
        val bmp = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        mutableVector.resizeTo(256F, 256F).center()
        mutableVector.renderToCanvas(canvas)
        return bmp
    }
}