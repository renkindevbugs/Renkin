package dev.renkinProject.renkin.icon.creator

import android.graphics.Bitmap
import android.graphics.PorterDuff
import android.graphics.Shader
import dev.renkinProject.renkin.data.ImageEdit

internal data class ModifierStack(
    val effect: EffectModifier,
    val transform: TransformModifier = TransformModifier(),
    val shape: ShapeModifier? = null,
    val outline: OutlineModifier? = null,
    val shadow: ShadowModifier? = null
) {
    val hasAdjustments: Boolean
        get() = !transform.isIdentity || requiresRasterization

    val requiresRasterization: Boolean
        get() = shape != null || outline != null || shadow?.isVisible == true

    // An outline alone retains the source's adaptive export flag and preview scale.
    val flattensOutput: Boolean
        get() = shape != null || shadow?.isVisible == true
}

internal data class EffectModifier(
    val imageEdit: ImageEdit,
    val colorizer: ColorizerStyle,
    val edges: EdgeModifier = EdgeModifier(),
    val backgroundRemoval: BackgroundRemovalModifier = BackgroundRemovalModifier(),
    val segments: List<SegmentLayer> = emptyList()
)

internal data class EdgeModifier(
    val lowThreshold: Float = 2.5f,
    val highThreshold: Float = 7.5f,
    val smoothing: Float = 2f,
    val normalizeContrast: Boolean = false
)

internal data class BackgroundRemovalModifier(
    val targets: List<Int> = emptyList(),
    val tolerance: Float = 0.1f,
    val brushOperations: List<BackgroundBrushOperation> = emptyList()
)

internal data class TransformModifier(
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f
) {
    val isIdentity: Boolean get() = scale == 1f && offsetX == 0f && offsetY == 0f
}

internal data class ShapeModifier(
    val shape: IconShape,
    val crop: Boolean,
    val scale: Float,
    val fill: ColorFill
)

internal data class OutlineModifier(
    val mode: OutlineMode,
    val width: Float,
    val fill: ColorFill,
    val eraseMask: Bitmap? = null
)

internal data class ShadowModifier(
    val blur: Float,
    val distance: Float,
    val angle: Float,
    val allDirections: Boolean,
    val style: ColorizerStyle,
    val opacity: Float
) {
    val isVisible: Boolean
        get() {
            if (!(opacity > 0f && (blur > 0f || distance > 0f))) return false
            val colors = if (style.mode == ColorizerMode.GRADIENT) style.allGradientColors else listOf(style.firstColor)
            return colors.any { (it ushr 24) > 0 }
        }
}

// Legacy options distinguish a flat fallback colour from an optional gradient's first stop.
// Retain both values: a source-specific background must not inherit an editor plate colour.
internal data class ColorFill(val color: Int, val style: ColorizerStyle? = null) {
    fun shader(width: Int, height: Int): Shader? {
        val gradient = style?.takeIf { it.mode == ColorizerMode.GRADIENT } ?: return null
        return buildColorizerShader(
            gradient.allGradientColors, gradient.gradientType, gradient.gradientAngle,
            width, height, gradient.gradientPositions
        )
    }
}

internal val ColorizerStyle.blendMode: PorterDuff.Mode
    get() = when {
        monochrome -> PorterDuff.Mode.MULTIPLY
        flat -> PorterDuff.Mode.SRC_IN
        lighten -> PorterDuff.Mode.SCREEN
        else -> PorterDuff.Mode.MULTIPLY
    }
