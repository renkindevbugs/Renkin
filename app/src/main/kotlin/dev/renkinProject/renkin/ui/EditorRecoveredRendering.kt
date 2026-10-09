package dev.renkinProject.renkin.ui

import android.graphics.Bitmap
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.icon.creator.BackgroundBrushOperation
import dev.renkinProject.renkin.icon.creator.ColorFill
import dev.renkinProject.renkin.icon.creator.GenerationOptions
import dev.renkinProject.renkin.icon.creator.OutlineMode

/** UI controls describe edits; they must not normalize untouched captured generation values. */
internal class EditorRecoveredRendering(
    private val saved: GenerationOptions,
    private val witness: EditorRenderWitness,
    private val outlineStrokes: List<BrushStroke>,
    private val backgroundStrokes: List<BrushStroke>
) {
    private val controls = witness.controls

    fun outlineMask(strokes: List<BrushStroke>, mode: OutlineMode, build: () -> Bitmap?): Bitmap? =
        if (strokes == outlineStrokes &&
            (mode == controls.adjustments.outlineMode || saved.modifiers.outline?.eraseMask != null)) {
            saved.modifiers.outline?.eraseMask
        } else build()

    fun backgroundMasks(
        strokes: List<BrushStroke>, imageEdit: ImageEdit,
        build: () -> List<BackgroundBrushOperation>
    ): List<BackgroundBrushOperation> =
        if (strokes == backgroundStrokes && imageEdit == controls.create.imageEdit) {
            saved.modifiers.effect.backgroundRemoval.brushOperations
        } else build()

    fun optionsFor(ui: GenerationOptions, currentWitness: EditorRenderWitness): GenerationOptions {
        val current = currentWitness.controls
        val sourceReplaced = currentWitness.origin != witness.origin || when (witness.origin) {
            IconOrigin.CREATE -> currentWitness.selections != witness.selections ||
                currentWitness.replay !== witness.replay || current.create.source != controls.create.source
            IconOrigin.UPLOAD -> currentWitness.upload.path != witness.upload.path
            IconOrigin.VECTOR -> false
        }
        val before = controls.create
        val after = current.create
        val old = controls.adjustments
        val now = current.adjustments
        val stack = saved.modifiers
        val proposed = ui.modifiers
        val paletteChanged = sourceReplaced || before.applicationIconVariant != after.applicationIconVariant ||
            before.materialYouScheme != after.materialYouScheme || before.iconColorBits != after.iconColorBits ||
            before.backgroundColorBits != after.backgroundColorBits
        val sourceStyleChanged = paletteChanged || before.materialYouForegroundStyle != after.materialYouForegroundStyle ||
            before.materialYouBackgroundStyle != after.materialYouBackgroundStyle
        val plateChanged = old.shapeStyle != now.shapeStyle
        val outlineGeometryChanged = old.eraseStrokes != now.eraseStrokes
        val backgroundGeometryChanged = old.backgroundBrushStrokes != now.backgroundBrushStrokes
        val imageEditChanged = before.imageEdit != after.imageEdit

        val source = if (sourceReplaced) ui.source else saved.source.copy(
            primarySource = edited(before.source != after.source, saved.source.primarySource, ui.source.primarySource),
            primaryTextType = edited(before.textType != after.textType, saved.source.primaryTextType, ui.source.primaryTextType),
            primaryIconPack = edited(before.iconPack != after.iconPack, saved.source.primaryIconPack, ui.source.primaryIconPack),
            textCustom = edited(before.customText != after.customText, saved.source.textCustom, ui.source.textCustom),
            textCase = edited(before.textCase != after.textCase, saved.source.textCase, ui.source.textCase),
            textFontPath = edited(before.fontReference != after.fontReference, saved.source.textFontPath, ui.source.textFontPath),
            applicationIconVariant = edited(before.applicationIconVariant != after.applicationIconVariant,
                saved.source.applicationIconVariant, ui.source.applicationIconVariant),
            invertMonochrome = edited(before.invertMonochrome != after.invertMonochrome,
                saved.source.invertMonochrome, ui.source.invertMonochrome),
            useFullApplicationIcon = edited(before.useFullApplicationIcon != after.useFullApplicationIcon,
                saved.source.useFullApplicationIcon, ui.source.useFullApplicationIcon)
        )
        val shapeChanged = old.shape != now.shape || old.shapeCrop != now.shapeCrop ||
            old.shapeScale != now.shapeScale || plateChanged
        val shape = if (!shapeChanged) stack.shape else proposed.shape?.let { candidate ->
            stack.shape?.let { original ->
                val fill = if (original.fill != null && plateChanged) {
                    ColorFill(ui.export.background.style?.firstColor ?: ui.export.background.color, ui.export.background.style)
                } else original.fill
                original.copy(
                    shape = edited(old.shape != now.shape, original.shape, candidate.shape),
                    crop = edited(old.shapeCrop != now.shapeCrop, original.crop, candidate.crop),
                    scale = edited(old.shapeScale != now.shapeScale, original.scale, candidate.scale),
                    fill = fill
                )
            } ?: candidate
        }
        val outlineChanged = old.outlineMode != now.outlineMode || old.outlineWidth != now.outlineWidth ||
            old.outlineStyle != now.outlineStyle || outlineGeometryChanged
        val outline = if (!outlineChanged) stack.outline else proposed.outline?.let { candidate ->
            stack.outline?.let { original -> original.copy(
                mode = edited(old.outlineMode != now.outlineMode, original.mode, candidate.mode),
                width = edited(old.outlineWidth != now.outlineWidth, original.width, candidate.width),
                fill = edited(old.outlineStyle != now.outlineStyle, original.fill, candidate.fill),
                eraseMask = edited(outlineGeometryChanged || old.outlineMode != now.outlineMode,
                    original.eraseMask, candidate.eraseMask)
            ) } ?: candidate
        }
        val shadowChanged = old.shadowEnabled != now.shadowEnabled || old.shadowBlur != now.shadowBlur ||
            old.shadowDistance != now.shadowDistance || old.shadowAngle != now.shadowAngle ||
            old.shadowAllDirections != now.shadowAllDirections || old.shadowOpacity != now.shadowOpacity ||
            old.shadowStyle != now.shadowStyle
        val shadow = if (!shadowChanged) stack.shadow else proposed.shadow?.let { candidate ->
            stack.shadow?.let { original -> original.copy(
                enabled = edited(old.shadowEnabled != now.shadowEnabled, original.enabled, candidate.enabled),
                blur = edited(old.shadowBlur != now.shadowBlur, original.blur, candidate.blur),
                distance = edited(old.shadowDistance != now.shadowDistance, original.distance, candidate.distance),
                angle = edited(old.shadowAngle != now.shadowAngle, original.angle, candidate.angle),
                allDirections = edited(old.shadowAllDirections != now.shadowAllDirections, original.allDirections, candidate.allDirections),
                opacity = edited(old.shadowOpacity != now.shadowOpacity, original.opacity, candidate.opacity),
                style = edited(old.shadowStyle != now.shadowStyle, original.style, candidate.style)
            ) } ?: candidate
        }
        val packBefore = controls.materialYouPack
        val packAfter = current.materialYouPack
        val pack = if (sourceReplaced) proposed.materialYouPack else stack.materialYouPack.copy(
            selectedScheme = edited(packBefore.selectedScheme != packAfter.selectedScheme,
                stack.materialYouPack.selectedScheme, proposed.materialYouPack.selectedScheme),
            foreground = edited(packBefore.selectedScheme != packAfter.selectedScheme || packBefore.customForeground != packAfter.customForeground,
                stack.materialYouPack.foreground, proposed.materialYouPack.foreground),
            background = edited(packBefore.selectedScheme != packAfter.selectedScheme || packBefore.customBackground != packAfter.customBackground,
                stack.materialYouPack.background, proposed.materialYouPack.background),
            customForeground = edited(packBefore.customForeground != packAfter.customForeground,
                stack.materialYouPack.customForeground, proposed.materialYouPack.customForeground),
            customBackground = edited(packBefore.customBackground != packAfter.customBackground,
                stack.materialYouPack.customBackground, proposed.materialYouPack.customBackground),
            strokeScale = edited(packBefore.strokeScale != packAfter.strokeScale,
                stack.materialYouPack.strokeScale, proposed.materialYouPack.strokeScale)
        )
        return saved.copy(
            source = source,
            export = saved.export.copy(
                vector = edited(sourceReplaced || before.useVector != after.useVector, saved.export.vector, ui.export.vector),
                materialYou = edited(sourceReplaced || before.applicationIconVariant != after.applicationIconVariant ||
                    before.replayMaterialYou != after.replayMaterialYou, saved.export.materialYou, ui.export.materialYou),
                themed = edited(sourceReplaced, saved.export.themed, ui.export.themed),
                foregroundStyle = edited(sourceStyleChanged, saved.export.foregroundStyle, ui.export.foregroundStyle),
                background = edited(sourceStyleChanged || plateChanged || old.shape != now.shape || old.shapeCrop != now.shapeCrop,
                    saved.export.background, ui.export.background)
            ),
            override = edited(sourceReplaced, saved.override, ui.override),
            modifiers = stack.copy(
                effect = stack.effect.copy(
                    imageEdit = edited(imageEditChanged, stack.effect.imageEdit, proposed.effect.imageEdit),
                    colorizer = edited(paletteChanged || old.colorizerStyle != now.colorizerStyle,
                        stack.effect.colorizer, proposed.effect.colorizer),
                    edges = stack.effect.edges.copy(
                        lowThreshold = edited(old.edgeThreshold != now.edgeThreshold, stack.effect.edges.lowThreshold, proposed.effect.edges.lowThreshold),
                        highThreshold = edited(old.edgeThreshold != now.edgeThreshold, stack.effect.edges.highThreshold, proposed.effect.edges.highThreshold),
                        smoothing = edited(old.edgeSmoothing != now.edgeSmoothing, stack.effect.edges.smoothing, proposed.effect.edges.smoothing),
                        normalizeContrast = edited(old.edgeContrast != now.edgeContrast, stack.effect.edges.normalizeContrast, proposed.effect.edges.normalizeContrast)
                    ),
                    backgroundRemoval = stack.effect.backgroundRemoval.copy(
                        targets = edited(old.backgroundTargets != now.backgroundTargets,
                            stack.effect.backgroundRemoval.targets, proposed.effect.backgroundRemoval.targets),
                        tolerance = edited(old.backgroundTolerance != now.backgroundTolerance,
                            stack.effect.backgroundRemoval.tolerance, proposed.effect.backgroundRemoval.tolerance),
                        brushOperations = edited(backgroundGeometryChanged || imageEditChanged,
                            stack.effect.backgroundRemoval.brushOperations, proposed.effect.backgroundRemoval.brushOperations)
                    ),
                    segments = edited(old.colorizeLayers != now.colorizeLayers || imageEditChanged,
                        stack.effect.segments, proposed.effect.segments)
                ),
                transform = stack.transform.copy(
                    scale = edited(old.iconScale != now.iconScale, stack.transform.scale, proposed.transform.scale),
                    offsetX = edited(old.offsetX != now.offsetX, stack.transform.offsetX, proposed.transform.offsetX),
                    offsetY = edited(old.offsetY != now.offsetY, stack.transform.offsetY, proposed.transform.offsetY)
                ),
                shape = shape, outline = outline, shadow = shadow, materialYouPack = pack
            )
        )
    }

    private fun <T> edited(changed: Boolean, original: T, proposed: T): T = if (changed) proposed else original
}
