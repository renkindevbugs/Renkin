package com.kaanelloed.iconeration.icon

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter

class EmptyIcon: ExportableIcon(false) {
    @Composable
    override fun getPainter(): Painter {
        TODO("Not yet implemented")
    }

    override fun toBitmap(): Bitmap {
        TODO("Not yet implemented")
    }
}