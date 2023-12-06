package com.kaanelloed.iconeration.icon

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.core.graphics.drawable.toBitmap
import com.caverock.androidsvg.SVG
import com.kaanelloed.iconeration.drawable.SVGDrawable
import com.kaanelloed.iconeration.vector.VectorExporter.Companion.toSvg
import com.kaanelloed.iconeration.vector.VectorRenderer.Companion.renderToCanvas

class VectorIcon(val vector: ImageVector, private val option: RendererOption = RendererOption.Svg): ExportableIcon() {
    @Composable
    override fun getPainter(): Painter {
        return rememberVectorPainter(vector)
    }

    override fun toBitmap(): Bitmap {
        when (option) {
            RendererOption.Directly -> renderDirectly()
            RendererOption.Svg -> renderWithSvgDynamic()
            RendererOption.SvgDynamic -> renderWithSvg()
        }

        //return renderDirectly()
        return renderWithSvgDynamic()
    }

    private fun renderDirectly(): Bitmap {
        val bmp = Bitmap.createBitmap(vector.viewportWidth.toInt(), vector.viewportHeight.toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        vector.renderToCanvas(canvas)
        return bmp
    }

    private fun renderWithSvgDynamic(): Bitmap {
        val svg = SVG.getFromString(vector.toSvg())
        val offset = vector.viewportHeight / 6
        svg.setDocumentViewBox(offset, offset, offset * 4, offset * 4)
        return SVGDrawable(svg).toBitmap(198, 198)
    }

    private fun renderWithSvg(): Bitmap {
        val bmp = Bitmap.createBitmap(vector.defaultWidth.value.toInt(), vector.defaultHeight.value.toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val svg = SVG.getFromString(vector.toSvg())
        svg.renderToCanvas(canvas)
        return bmp
    }

    enum class RendererOption {
        Directly, Svg, SvgDynamic
    }
}