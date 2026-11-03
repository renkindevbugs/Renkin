package dev.renkinProject.renkin.icon.creator

import android.graphics.Matrix
import android.graphics.Path
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.rectangle

/**
 * The icon-shape choice in the Modifier tab. Geometry comes from Material 3 Expressive's
 * [MaterialShapes] presets, so the shapes match what Material You launchers draw natively.
 * The squircle is built here instead, because its corner is adjustable.
 */
enum class IconShape { NONE, CIRCLE, SQUIRCLE, PEBBLE, COOKIE, SUNNY }

val IconShape.hasAdjustableCorner: Boolean
    get() = this == IconShape.SQUIRCLE

/** Corner radius as a fraction of half the side: 0 is a sharp square, 1 a circle. */
val SHAPE_CORNER_RANGE = 0f..1f

// MaterialShapes.Square rounds a unit square by 0.3, so existing squircles keep their geometry.
const val DEFAULT_SHAPE_CORNER_PERCENT = 60
const val DEFAULT_SHAPE_CORNER = DEFAULT_SHAPE_CORNER_PERCENT / 100f

object IconShapes {

    /** [shape] as a closed path scaled to a [size]×[size] box; null for [IconShape.NONE]. */
    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    fun path(shape: IconShape, size: Float, corner: Float = DEFAULT_SHAPE_CORNER): Path? {
        val polygon = when (shape) {
            IconShape.NONE -> return null
            IconShape.CIRCLE -> MaterialShapes.Circle
            IconShape.SQUIRCLE -> roundedSquare(corner)
            IconShape.PEBBLE -> MaterialShapes.Bun
            IconShape.COOKIE -> MaterialShapes.Cookie6Sided
            IconShape.SUNNY -> MaterialShapes.Sunny
        }
        return polygon.toAndroidPath(size)
    }

    private fun roundedSquare(corner: Float): RoundedPolygon =
        RoundedPolygon.rectangle(
            width = 1f,
            height = 1f,
            rounding = CornerRounding(corner.coerceIn(SHAPE_CORNER_RANGE) / 2f),
            centerX = 0.5f,
            centerY = 0.5f
        )

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
