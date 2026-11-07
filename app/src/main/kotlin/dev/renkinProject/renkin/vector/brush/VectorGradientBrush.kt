package dev.renkinProject.renkin.vector.brush

import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.RadialGradient
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.toArgb
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** SVG `matrix(a b c d e f)`: x' = a·x + c·y + e, y' = b·x + d·y + f. */
data class GradientMatrix(val a: Float, val b: Float, val c: Float, val d: Float, val e: Float, val f: Float) {
    /** `this ∘ other`: [other] applies first. */
    operator fun times(other: GradientMatrix) = GradientMatrix(
        a = a * other.a + c * other.b,
        b = b * other.a + d * other.b,
        c = a * other.c + c * other.d,
        d = b * other.c + d * other.d,
        e = a * other.e + c * other.f + e,
        f = b * other.e + d * other.f + f
    )

    fun mapX(x: Float, y: Float) = a * x + c * y + e
    fun mapY(x: Float, y: Float) = b * x + d * y + f

    val isUsable: Boolean
        get() = listOf(a, b, c, d, e, f).all(Float::isFinite) && abs(a * d - b * c) > 1e-12f

    fun toAndroidMatrix(): Matrix = Matrix().apply { setValues(floatArrayOf(a, c, e, b, d, f, 0f, 0f, 1f)) }

    fun values(): List<Float> = listOf(a, b, c, d, e, f)

    companion object {
        val IDENTITY = GradientMatrix(1f, 0f, 0f, 1f, 0f, 0f)

        fun translate(tx: Float, ty: Float) = GradientMatrix(1f, 0f, 0f, 1f, tx, ty)

        fun scale(sx: Float, sy: Float, pivotX: Float = 0f, pivotY: Float = 0f) =
            GradientMatrix(sx, 0f, 0f, sy, pivotX * (1 - sx), pivotY * (1 - sy))

        /** Same convention as `NodeEditor.rotate`: degrees, `[cos −sin; sin cos]` about the pivot. */
        fun rotate(degrees: Float, pivotX: Float = 0f, pivotY: Float = 0f): GradientMatrix {
            val rad = degrees * PI / 180
            val cos = cos(rad).toFloat()
            val sin = sin(rad).toFloat()
            return GradientMatrix(cos, sin, -sin, cos, pivotX - pivotX * cos + pivotY * sin, pivotY - pivotX * sin - pivotY * cos)
        }

        fun of(values: List<Float>): GradientMatrix? =
            values.takeIf { it.size == 6 }?.let { GradientMatrix(it[0], it[1], it[2], it[3], it[4], it[5]) }
    }
}

enum class GradientType { LINEAR, RADIAL }

/**
 * A readable linear or radial gradient paint for editor paths. Coordinates are kept as authored and
 * [matrix] maps them into path (viewport) space, so ellipses and skew stay exact and every geometry
 * edit is `m ∘ matrix`. Value equality matters: editor state compares paths by `equals`.
 * Build through [of], the one validator shared by import, XML parsing and draft decoding.
 */
data class VectorGradientBrush internal constructor(
    val type: GradientType,
    val colors: List<Color>,
    val offsets: List<Float>,
    val x0: Float,
    val y0: Float,
    val x1: Float,
    val y1: Float,
    val radius: Float,
    val tileMode: TileMode,
    val matrix: GradientMatrix
) : ShaderBrush() {

    // Viewport units, not pixels: Compose and VectorRenderer apply the viewport scale to the canvas.
    override fun createShader(size: Size): Shader = shader(null)

    /** A fresh shader; [outer] maps path space to the canvas when the path is pre-transformed. */
    fun shader(outer: Matrix?): Shader {
        val argb = colors.map { it.toArgb() }.toIntArray()
        val positions = offsets.toFloatArray()
        val mode = when (tileMode) {
            TileMode.Mirror -> android.graphics.Shader.TileMode.MIRROR
            TileMode.Repeated -> android.graphics.Shader.TileMode.REPEAT
            else -> android.graphics.Shader.TileMode.CLAMP
        }
        val shader = when (type) {
            GradientType.LINEAR -> LinearGradient(x0, y0, x1, y1, argb, positions, mode)
            GradientType.RADIAL -> RadialGradient(x0, y0, radius, argb, positions, mode)
        }
        val local = matrix.toAndroidMatrix()
        if (outer != null) local.postConcat(outer)
        shader.setLocalMatrix(local)
        return shader
    }

    fun transformed(transform: GradientMatrix): VectorGradientBrush = copy(matrix = transform * matrix)

    /**
     * One solid colour for "Make solid" and for builds that cannot read gradients: each stop weighted by
     * the share of the gradient it covers. Derived from the stops only, so XML re-export stays byte-identical.
     */
    fun fallbackColor(): Color {
        val channels = FloatArray(4)
        fun add(color: Color, weight: Float) {
            channels[0] += color.red * weight
            channels[1] += color.green * weight
            channels[2] += color.blue * weight
            channels[3] += color.alpha * weight
        }
        add(colors.first(), offsets.first())
        for (i in 0 until colors.lastIndex) {
            val span = offsets[i + 1] - offsets[i]
            add(colors[i], span / 2)
            add(colors[i + 1], span / 2)
        }
        add(colors.last(), 1f - offsets.last())
        return Color(
            channels[0].coerceIn(0f, 1f),
            channels[1].coerceIn(0f, 1f),
            channels[2].coerceIn(0f, 1f),
            channels[3].coerceIn(0f, 1f)
        )
    }

    companion object {
        const val MAX_STOPS = 256

        /**
         * A validated gradient, a [SolidColor] where SVG paints one colour (single stop, zero-length
         * linear vector, zero radius), or null when the values cannot describe a gradient.
         */
        fun of(
            type: GradientType,
            colors: List<Color>,
            offsets: List<Float>,
            x0: Float,
            y0: Float,
            x1: Float,
            y1: Float,
            radius: Float,
            tileMode: TileMode,
            matrix: GradientMatrix
        ): Brush? {
            if (colors.isEmpty() || colors.size != offsets.size || colors.size > MAX_STOPS) return null
            if (offsets.any { !it.isFinite() || it < 0f || it > 1f }) return null
            if (offsets.zipWithNext().any { (previous, next) -> next < previous }) return null
            if (listOf(x0, y0, x1, y1, radius).any { !it.isFinite() } || radius < 0f || !matrix.isUsable) return null
            val degenerate = colors.size == 1 ||
                (type == GradientType.LINEAR && x0 == x1 && y0 == y1) ||
                (type == GradientType.RADIAL && radius == 0f)
            if (degenerate) return SolidColor(colors.last())
            return VectorGradientBrush(
                type, colors, offsets, x0, y0,
                if (type == GradientType.LINEAR) x1 else 0f,
                if (type == GradientType.LINEAR) y1 else 0f,
                if (type == GradientType.RADIAL) radius else 0f,
                if (tileMode == TileMode.Mirror || tileMode == TileMode.Repeated) tileMode else TileMode.Clamp,
                matrix
            )
        }
    }
}

/** Applies [transform] to a gradient brush; every other brush is position-independent. */
fun Brush?.transformedBy(transform: GradientMatrix): Brush? =
    if (this is VectorGradientBrush) transformed(transform) else this
