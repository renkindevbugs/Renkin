package dev.renkinProject.renkin.icon.creator

import android.graphics.Matrix
import android.graphics.Path
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.graphics.shapes.RoundedPolygon

/**
 * The icon-shape choice in the Modifier tab. Geometry comes from Material 3 Expressive's
 * [MaterialShapes] presets, so the shapes match what Material You launchers draw natively.
 */
enum class IconShape { NONE, CIRCLE, SQUIRCLE, PEBBLE, COOKIE, SUNNY }

object IconShapes {

    /** [shape] as a closed path scaled to a [size]×[size] box; null for [IconShape.NONE]. */
    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    fun path(shape: IconShape, size: Float): Path? {
        val polygon = when (shape) {
            IconShape.NONE -> return null
            IconShape.CIRCLE -> MaterialShapes.Circle
            IconShape.SQUIRCLE -> MaterialShapes.Square
            IconShape.PEBBLE -> MaterialShapes.Bun
            IconShape.COOKIE -> MaterialShapes.Cookie6Sided
            IconShape.SUNNY -> MaterialShapes.Sunny
        }
        return polygon.toAndroidPath(size)
    }

    /**
     * Converts the polygon's cubic curves into an android [Path], scaled from the presets'
     * normalized 1×1 space to [size]. Manual conversion — no dependency on which artifact
     * happens to ship a toPath() extension.
     */
    private fun RoundedPolygon.toAndroidPath(size: Float): Path {
        val path = Path()
        val curves = cubics
        if (curves.isEmpty()) return path
        path.moveTo(curves.first().anchor0X, curves.first().anchor0Y)
        for (cubic in curves) {
            path.cubicTo(
                cubic.control0X, cubic.control0Y,
                cubic.control1X, cubic.control1Y,
                cubic.anchor1X, cubic.anchor1Y
            )
        }
        path.close()
        val matrix = Matrix()
        matrix.setScale(size, size)
        path.transform(matrix)
        return path
    }
}
