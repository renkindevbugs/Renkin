package dev.renkinProject.renkin.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.data.Source
import dev.renkinProject.renkin.icon.creator.ColorFill
import dev.renkinProject.renkin.icon.creator.ColorizerStyle
import dev.renkinProject.renkin.icon.creator.GenerationOptions
import dev.renkinProject.renkin.icon.creator.IconShape
import dev.renkinProject.renkin.icon.creator.ModifierInputPipeline
import dev.renkinProject.renkin.icon.creator.ModifierStack
import dev.renkinProject.renkin.icon.creator.OutlineMode
import dev.renkinProject.renkin.icon.creator.SHADOW_ANGLE_DEFAULT
import dev.renkinProject.renkin.icon.creator.SHADOW_BLUR_DEFAULT
import dev.renkinProject.renkin.icon.creator.SHADOW_DISTANCE_DEFAULT
import dev.renkinProject.renkin.icon.creator.SHADOW_OPACITY_DEFAULT
import dev.renkinProject.renkin.icon.creator.VerifiedModifierInput
import dev.renkinProject.renkin.drawable.AdaptiveIconPackDrawable
import dev.renkinProject.renkin.drawable.MaterialYouPackEditState

internal class EditorModifierReplay(val verified: VerifiedModifierInput) {
    companion object {
        internal fun fromDraft(verified: VerifiedModifierInput, baseline: GenerationOptions, plate: ColorizerStyle?) =
            EditorModifierReplay(verified).apply {
                this.baseline = baseline.modifiers
                baselinePlateStyle = plate
            }
    }
    private val saved = verified.retained.options
    private lateinit var baseline: ModifierStack
    private var baselinePlateStyle: ColorizerStyle? = null
    val allowsImageEdits: Boolean get() = verified.retained.pipeline != ModifierInputPipeline.ADJUSTMENTS_ONLY
    // Pack colours stay editable because the retained layers can be recoloured exactly.
    val packEditState: MaterialYouPackEditState? =
        (verified.input as? AdaptiveIconPackDrawable)?.materialYouEditState
            ?.takeIf { verified.retained.pipeline == ModifierInputPipeline.IMAGE_EDIT }
    internal data class Bookkeeping(val baseline: ModifierStack, val plate: ColorizerStyle?)
    internal fun captureBookkeeping() = Bookkeeping(baseline, baselinePlateStyle)

    fun restoreControls(session: EditorSession) {
        val stack = saved.modifiers
        session.source = Source.NONE
        session.imageEdit = stack.effect.imageEdit
        session.iconColor = Color(stack.effect.colorizer.firstColor)
        session.useVector = saved.export.vector
        session.replayMaterialYou = saved.export.materialYou
        session.customIconList = emptyList()
        with(session.materialYouPackAdjustments) { packEditState?.let(::restore) ?: reset() }
        with(session.adjustments) {
            colorizerStyle = stack.effect.colorizer
            edgeThreshold = stack.effect.edges.lowThreshold
            edgeSmoothing = stack.effect.edges.smoothing
            edgeContrast = stack.effect.edges.normalizeContrast
            bgRemovalTargets = stack.effect.backgroundRemoval.targets
            bgRemovalTolerance = stack.effect.backgroundRemoval.tolerance
            colorizeLayers = stack.effect.segments
            iconScale = stack.transform.scale
            iconOffsetX = stack.transform.offsetX
            iconOffsetY = stack.transform.offsetY
            autoCenter = false
            iconShape = stack.shape?.shape ?: IconShape.NONE
            shapeCrop = stack.shape?.crop ?: true
            shapeScale = stack.shape?.scale ?: 1f
            val plate = stack.shape?.fill ?: saved.export.background
            shapeStyle = plate.style ?: ColorizerStyle(firstColor = plate.color)
            outlineMode = stack.outline?.mode ?: OutlineMode.NONE
            outlineWidth = stack.outline?.width ?: 6f
            val outlineFill = stack.outline?.fill
            outlineStyle = outlineFill?.style ?: ColorizerStyle(firstColor = outlineFill?.color ?: android.graphics.Color.BLACK)
            shadowEnabled = stack.shadow?.enabled ?: false
            shadowBlur = stack.shadow?.blur ?: SHADOW_BLUR_DEFAULT
            shadowDistance = stack.shadow?.distance ?: SHADOW_DISTANCE_DEFAULT
            shadowAngle = stack.shadow?.angle ?: SHADOW_ANGLE_DEFAULT
            shadowAllDirections = stack.shadow?.allDirections ?: false
            shadowOpacity = stack.shadow?.opacity ?: SHADOW_OPACITY_DEFAULT
            shadowStyle = stack.shadow?.style ?: ColorizerStyle(firstColor = android.graphics.Color.BLACK)
            eraseStrokes = emptyList()
            backgroundBrushStrokes = emptyList()
        }
        baseline = session.adjustments.applyToModifierStack(
            saved.modifiers, session.imageEdit, session.iconColor.toArgb(), null, emptyList()
        )
        baselinePlateStyle = session.adjustments.shapeStyle.takeIf {
            session.adjustments.iconShape != IconShape.NONE && !session.adjustments.shapeCrop
        }
        session.selectedTab = 2
    }

    fun optionsFor(ui: GenerationOptions): GenerationOptions {
        val before = baseline
        val after = ui.modifiers
        val original = saved.modifiers
        // Controls project nullable groups and a derived high edge threshold. Keep every
        // original value until that control changes, rather than replacing it with UI defaults.
        val effect = original.effect.copy(
            imageEdit = if (allowsImageEdits) after.effect.imageEdit else ImageEdit.NONE,
            colorizer = if (after.effect.colorizer == before.effect.colorizer) original.effect.colorizer else after.effect.colorizer,
            edges = original.effect.edges.copy(
                lowThreshold = after.effect.edges.lowThreshold,
                highThreshold = if (after.effect.edges.lowThreshold == before.effect.edges.lowThreshold) {
                    original.effect.edges.highThreshold
                } else after.effect.edges.highThreshold,
                smoothing = after.effect.edges.smoothing,
                normalizeContrast = after.effect.edges.normalizeContrast
            ),
            backgroundRemoval = if (after.effect.backgroundRemoval == before.effect.backgroundRemoval) {
                original.effect.backgroundRemoval
            } else after.effect.backgroundRemoval,
            segments = if (after.effect.segments == before.effect.segments) original.effect.segments else after.effect.segments
        )
        val plateChanged = ui.export.background.style != baselinePlateStyle
        val shape = if (after.shape == before.shape && !plateChanged) original.shape else after.shape?.let {
            val style = ui.export.background.style
            it.copy(fill = if (plateChanged && style != null) ColorFill(style.firstColor, style)
                else original.shape?.fill)
        }
        val outline = if (after.outline == before.outline) original.outline else after.outline?.let {
            it.copy(fill = if (it.fill == before.outline?.fill) original.outline?.fill ?: it.fill else it.fill)
        }
        return saved.copy(
            modifiers = original.copy(
                effect = effect,
                transform = if (after.transform == before.transform) original.transform else after.transform,
                shape = shape,
                outline = outline,
                shadow = if (after.shadow == before.shadow) original.shadow else after.shadow,
                materialYouPack = if (packEditState == null || after.materialYouPack == before.materialYouPack) {
                    original.materialYouPack
                } else after.materialYouPack
            ),
            export = saved.export.copy(vector = ui.export.vector, materialYou = ui.export.materialYou)
        )
    }
}
