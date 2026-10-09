package dev.renkinProject.renkin.data.editor

import androidx.compose.ui.graphics.Color
import dev.renkinProject.renkin.data.OUTLINE_WIDTH_MAX
import dev.renkinProject.renkin.data.OUTLINE_WIDTH_MIN
import dev.renkinProject.renkin.icon.creator.ColorizerStyle
import dev.renkinProject.renkin.icon.creator.ColorizerMode
import dev.renkinProject.renkin.icon.creator.GenerationOptions
import dev.renkinProject.renkin.icon.creator.MAX_GRADIENT_STOPS
import dev.renkinProject.renkin.icon.creator.SHADOW_BLUR_MAX
import dev.renkinProject.renkin.icon.creator.SHADOW_BLUR_MIN
import dev.renkinProject.renkin.icon.creator.SHADOW_DISTANCE_MAX
import dev.renkinProject.renkin.icon.creator.SHADOW_DISTANCE_MIN
import dev.renkinProject.renkin.icon.creator.SHADOW_OPACITY_MAX
import dev.renkinProject.renkin.icon.creator.SHADOW_OPACITY_MIN

/** Wire validity does not establish that controls can be bound without changing their values. */
internal object EditorRecoveryValidation {
    fun validate(capture: EditorCompleteCapture) {
        EditorCompleteCodec.validate(capture)
        val value = capture.snapshot
        require(value.origin != EditorDraftOrigin.CREATE || value.initialized) { "Create frame is not initialized" }
        val controls = value.controls
        require(controls.selectedTab in 0..3 && controls.create.materialYouScheme >= 0) { "Invalid editor selection" }
        range(controls.upload.zoomLevel, 0.25f..2f)
        color(controls.create.iconColorBits); color(controls.create.backgroundColorBits)
        style(controls.create.materialYouForegroundStyle); style(controls.create.materialYouBackgroundStyle)
        pack(controls.materialYouPack)
        with(controls.adjustments) {
            listOf(colorizerStyle, shapeStyle, outlineStyle, shadowStyle).forEach(::style)
            range(edgeThreshold, 0.5f..5f); range(edgeSmoothing, 0.5f..4f)
            range(iconScale, 0.5f..1.5f); range(offsetX, -0.5f..0.5f); range(offsetY, -0.5f..0.5f)
            range(backgroundTolerance, 0f..0.5f); range(shapeScale, 0.5f..1.5f)
            range(outlineWidth, OUTLINE_WIDTH_MIN.toFloat()..OUTLINE_WIDTH_MAX.toFloat())
            validateShadow(shadowBlur, shadowDistance, shadowAngle, shadowOpacity)
            colorizeLayers.forEach { range(it.tolerance, 0f..1f); style(it.style) }
            (eraseStrokes + backgroundBrushStrokes).forEach { stroke ->
                require(stroke.brush.isFinite() && stroke.brush > 0f && stroke.brush <= 0.25f) { "Invalid editor brush width" }
                stroke.points.forEach { range(it.x, 0f..1f); range(it.y, 0f..1f) }
            }
        }
        vector(value.vector.current); value.vector.seed?.let(::vector)
        capture.artwork.values.forEach(::artwork)
        val generations = listOfNotNull(value.requested, value.renderedOptions, value.original.input?.generation,
            value.input?.generation, value.restoredExisting?.generation, value.replay?.input?.generation, value.replay?.baseline)
        generations.forEach {
            require(it.eraseMask == null || it.options.modifiers.outline != null) { "Editor erase mask has no outline owner" }
            (listOfNotNull(it.eraseMask) + it.backgroundMasks.map { mask -> mask.artwork }).forEach { slot ->
                val bitmap = capture.artwork[slot] as EditorBitmapArtwork
                require(!bitmap.adaptive && bitmap.previewScale == 1f) { "Invalid editor mask presentation" }
            }
            generation(it.options)
        }
        value.replay?.let {
            require(it.input == value.restoredExisting && it.input.artwork == value.restoredSource) { "Replay input ownership mismatch" }
            it.plate?.let(::style)
        }
        value.restoredExisting?.let {
            require(it.artwork == value.restoredSource && value.original.input == it && it.baked == value.restoredSourceBaked) {
                "Restored original input mismatch"
            }
        }
    }

    private fun generation(value: GenerationOptions) {
        with(value.modifiers) {
            style(effect.colorizer)
            range(effect.edges.lowThreshold, 0.5f..5f)
            require(effect.edges.highThreshold.isFinite() && effect.edges.highThreshold >= effect.edges.lowThreshold) { "Invalid editor edge thresholds" }
            range(effect.edges.smoothing, 0.5f..4f); range(effect.backgroundRemoval.tolerance, 0f..0.5f)
            effect.segments.forEach { range(it.tolerance, 0f..1f); style(it.style) }
            range(transform.scale, 0.5f..1.5f); range(transform.offsetX, -0.5f..0.5f); range(transform.offsetY, -0.5f..0.5f)
            shape?.let { range(it.scale, 0.5f..1.5f); it.fill?.style?.let(::style) }
            outline?.let { range(it.width, OUTLINE_WIDTH_MIN.toFloat()..OUTLINE_WIDTH_MAX.toFloat()); it.fill.style?.let(::style) }
            shadow?.let { validateShadow(it.blur, it.distance, it.angle, it.opacity); style(it.style) }
            with(materialYouPack) {
                require(selectedScheme >= -1) { "Invalid editor pack scheme" }
                range(strokeScale, 0.5f..2f); customForeground?.let(::style); customBackground?.let(::style)
            }
        }
        value.export.background.style?.let(::style); value.export.foregroundStyle?.let(::style)
    }

    private fun pack(value: EditorPackControls) {
        // -1 inherits; indices beyond the palette select Custom and must remain intact.
        require(value.selectedScheme >= -1) { "Invalid editor pack scheme" }
        range(value.strokeScale, 0.5f..2f); style(value.customForeground); style(value.customBackground)
    }

    private fun style(value: ColorizerStyle) {
        val minimum = if (value.mode == ColorizerMode.GRADIENT) 1 else 0
        require(value.gradientStops.size in minimum until MAX_GRADIENT_STOPS) { "Unsupported editor gradient stop count" }
        range(value.gradientAngle, 0f..360f)
        val positions = value.gradientPositions
        require(positions.isEmpty() || positions.size == value.gradientStops.size + 1) { "Invalid editor gradient positions" }
        positions.forEach { range(it, 0f..1f) }
        require(positions.zipWithNext().all { (left, right) -> left <= right }) { "Unordered editor gradient positions" }
    }

    private fun validateShadow(blur: Float, distance: Float, angle: Float, opacity: Float) {
        range(blur, SHADOW_BLUR_MIN..SHADOW_BLUR_MAX); range(distance, SHADOW_DISTANCE_MIN..SHADOW_DISTANCE_MAX)
        range(angle, 0f..360f); range(opacity, SHADOW_OPACITY_MIN..SHADOW_OPACITY_MAX)
    }

    private fun vector(value: EditorVectorInputs) {
        range(value.thickness, 0.25f..4f)
        value.viewport?.let { positive(it.width); positive(it.height) }
        value.entries.forEach { require(it.baseStroke.isFinite() && it.baseStroke >= 0f); path(it.path) }
    }

    private fun artwork(value: EditorArtworkSnapshot) {
        when (value) {
            is EditorBitmapArtwork -> EditorArtworkCodec.validateBitmap(value)
            is EditorVectorArtwork -> {
                positive(value.width); positive(value.height); positive(value.viewportWidth); positive(value.viewportHeight)
                color(value.tintBits, allowUnspecified = true); node(value.root)
            }
            is EditorAdaptiveArtwork -> value.controls?.let(::pack)
            is EditorInsetArtwork -> artwork(value.child)
        }
    }

    private fun node(value: EditorVectorNode) {
        when (value) {
            is EditorVectorGroup -> value.children.forEach(::node)
            is EditorVectorPath -> path(value)
        }
    }

    private fun path(value: EditorVectorPath) {
        range(value.fillAlpha, 0f..1f); range(value.strokeAlpha, 0f..1f)
        require(value.strokeWidth.isFinite() && value.strokeWidth >= 0f && value.miter.isFinite() && value.miter >= 0f) { "Invalid editor vector stroke" }
        require(value.cap in setOf("Butt", "Round", "Square") && value.join in setOf("Miter", "Round", "Bevel")) { "Invalid editor vector stroke mode" }
        range(value.trimStart, 0f..1f); range(value.trimEnd, 0f..1f)
        value.fill?.let { color(it.colorBits) }; value.stroke?.let { color(it.colorBits) }
    }

    private fun color(bits: Long, allowUnspecified: Boolean = false) {
        try {
            val color = Color(bits.toULong())
            require(allowUnspecified || color != Color.Unspecified) { "Unspecified editor color" }
            if (color != Color.Unspecified) {
                color.colorSpace
                require(listOf(color.red, color.green, color.blue, color.alpha).all(Float::isFinite) && color.alpha in 0f..1f) { "Invalid editor color" }
            }
        } catch (error: IndexOutOfBoundsException) { throw IllegalArgumentException("Unsupported editor color space", error) }
    }

    private fun range(value: Float, range: ClosedFloatingPointRange<Float>) {
        require(value.isFinite() && value in range) { "Editor value is outside its supported domain" }
    }
    private fun positive(value: Float) { require(value.isFinite() && value > 0f) { "Invalid editor vector dimensions" } }
}
