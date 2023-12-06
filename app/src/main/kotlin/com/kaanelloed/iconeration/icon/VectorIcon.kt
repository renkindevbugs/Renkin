package com.kaanelloed.iconeration.icon

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter

class VectorIcon(val vector: ImageVector): ExportableIcon() {
    @Composable
    override fun getPainter(): Painter {
        return rememberVectorPainter(vector)
    }

    override fun toBitmap(): Bitmap {
        TODO("Not yet implemented")
    }
}