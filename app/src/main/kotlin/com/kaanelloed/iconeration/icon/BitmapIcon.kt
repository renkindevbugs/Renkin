package com.kaanelloed.iconeration.icon

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter

class BitmapIcon(val bitmap: Bitmap): ExportableIcon() {
    @Composable
    override fun getPainter(): Painter {
        return BitmapPainter(bitmap.asImageBitmap())
    }

    override fun toBitmap(): Bitmap {
        return bitmap
    }
}