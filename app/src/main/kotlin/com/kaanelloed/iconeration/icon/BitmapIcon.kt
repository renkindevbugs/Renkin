package com.kaanelloed.iconeration.icon

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import com.kaanelloed.iconeration.extension.toBase64

class BitmapIcon(val bitmap: Bitmap, exportAsAdaptiveIcon: Boolean = false): ExportableIcon(exportAsAdaptiveIcon) {
    @Composable
    override fun getPainter(): Painter {
        return BitmapPainter(bitmap.asImageBitmap())
    }

    override fun toBitmap(): Bitmap {
        return bitmap
    }

    override fun toDbString(): String {
        return convertToBase64()
    }

    private fun convertToBase64(): String {
        return bitmap.toBase64(Bitmap.CompressFormat.PNG, 100)
    }
}