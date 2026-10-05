package dev.renkinProject.renkin.icon.creator

import android.graphics.Bitmap
import android.graphics.PorterDuff
import android.graphics.Shader
import dev.renkinProject.renkin.data.ImageEdit

data class ModifierStack(
    val effect: EffectModifier,
    val transform: TransformModifier = TransformModifier(),
    val shape: ShapeModifier? = null,
    val outline: OutlineModifier? = null,
    val shadow: ShadowModifier? = null,
    val materialYouPack: MaterialYouPackModifier = MaterialYouPackModifier()
) {
    val hasVisibleEffect: Boolean
        get() = effect.imageEdit != ImageEdit.NONE || hasAdjustments || materialYouPack.hasRestyling

    val hasAdjustments: Boolean
        get() = !transform.isIdentity || requiresRasterization

    val requiresRasterization: Boolean
        get() = shape?.isEnabled == true || outline?.isEnabled == true || shadow?.isVisible == true

    // An outline alone retains the source's adaptive export flag and preview scale.
    val flattensOutput: Boolean
        get() = shape?.isEnabled == true || shadow?.isVisible == true
}

data class EffectModifier(
    val imageEdit: ImageEdit,
    val colorizer: ColorizerStyle,
    val edges: EdgeModifier = EdgeModifier(),
    val backgroundRemoval: BackgroundRemovalModifier = BackgroundRemovalModifier(),
    val segments: List<SegmentLayer> = emptyList()
)

data class EdgeModifier(
    val lowThreshold: Float = 2.5f,
    val highThreshold: Float = 7.5f,
    val smoothing: Float = 2f,
    val normalizeContrast: Boolean = false
)

data class BackgroundRemovalModifier(
    val targets: List<Int> = emptyList(),
    val tolerance: Float = 0.1f,
    val brushOperations: List<BackgroundBrushOperation> = emptyList()
)

data class TransformModifier(
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f
) {
    val isIdentity: Boolean get() = scale == 1f && offsetX == 0f && offsetY == 0f
}

data class ShapeModifier(
    val shape: IconShape,
    val crop: Boolean,
    val scale: Float,
    // Null inherits the presentation background; an explicit fill belongs only to this shape.
    val fill: ColorFill? = null
) {
    val isEnabled: Boolean get() = shape != IconShape.NONE
}

data class OutlineModifier(
    val mode: OutlineMode,
    val width: Float,
    val fill: ColorFill,
    val eraseMask: Bitmap? = null
) {
    val isEnabled: Boolean get() = mode != OutlineMode.NONE
}

data class ShadowModifier(
    val blur: Float,
    val distance: Float,
    val angle: Float,
    val allDirections: Boolean,
    val style: ColorizerStyle,
    val opacity: Float,
    val enabled: Boolean = true
) {
    val isVisible: Boolean
        get() {
            if (!(enabled && opacity > 0f && (blur > 0f || distance > 0f))) return false
            val colors = if (style.mode == ColorizerMode.GRADIENT) style.allGradientColors else listOf(style.firstColor)
            return colors.any { (it ushr 24) > 0 }
        }
}

// Legacy options distinguish a flat fallback colour from an optional gradient's first stop.
// Retain both values: a source-specific background must not inherit an editor plate colour.
data class ColorFill(val color: Int, val style: ColorizerStyle? = null) {
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

inline fun ModifierStack.withEffect(update: EffectModifier.() -> EffectModifier): ModifierStack =
    copy(effect = effect.update())
