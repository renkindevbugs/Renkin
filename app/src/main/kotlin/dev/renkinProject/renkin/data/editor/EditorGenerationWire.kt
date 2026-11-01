package dev.renkinProject.renkin.data.editor

import dev.renkinProject.renkin.data.FallbackSource
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.data.Source
import dev.renkinProject.renkin.data.TextType
import dev.renkinProject.renkin.icon.creator.*

internal data class EditorMaskReference(val action: BrushAction, val artwork: String)

/** Options contain no native masks; each mask points to exact draft-owned bitmap artwork. */
internal data class EditorGenerationSnapshot(
    val options: GenerationOptions,
    val eraseMask: String?,
    val backgroundMasks: List<EditorMaskReference>
)

internal fun EditorArtworkWriter.generation(value: EditorGenerationSnapshot) {
    val options = value.options
    require(options.modifiers.outline?.eraseMask == null &&
        options.modifiers.effect.backgroundRemoval.brushOperations.isEmpty()) { "Live masks in detached editor options" }
    with(options.source) {
        text(primarySource.name); text(primaryTextType.name); text(primaryIconPack)
        text(secondarySource.name); text(secondaryImageEdit.name); text(secondaryTextType.name); text(secondaryIconPack)
        text(textCustom); text(textCase.name); text(textFontPath); text(fallbackSource.name)
        text(applicationIconVariant.name); boolean(invertMonochrome); boolean(useFullApplicationIcon)
    }
    with(options.export) {
        boolean(vector); boolean(materialYou); boolean(themed); fill(background); optionalStyle(foregroundStyle)
    }
    boolean(options.override)
    val stack = options.modifiers
    with(stack.effect) {
        text(imageEdit.name); style(colorizer)
        number(edges.lowThreshold); number(edges.highThreshold); number(edges.smoothing); boolean(edges.normalizeContrast)
        require(backgroundRemoval.targets.size <= EditorControlsCodec.MAX_STYLE_VALUES)
        output.writeInt(backgroundRemoval.targets.size); backgroundRemoval.targets.forEach(output::writeInt)
        number(backgroundRemoval.tolerance)
        require(segments.size <= EditorControlsCodec.MAX_SEGMENTS)
        output.writeInt(segments.size)
        segments.forEach { segment ->
            ensureActive()
            require(segment.targets.size <= EditorControlsCodec.MAX_STYLE_VALUES)
            output.writeInt(segment.targets.size); segment.targets.forEach(output::writeInt)
            number(segment.tolerance); style(segment.style)
        }
    }
    with(stack.transform) { number(scale); number(offsetX); number(offsetY) }
    boolean(stack.shape != null)
    stack.shape?.let { text(it.shape.name); boolean(it.crop); number(it.scale); boolean(it.fill != null); it.fill?.let(::fill) }
    boolean(stack.outline != null)
    stack.outline?.let { text(it.mode.name); number(it.width); fill(it.fill) }
    boolean(stack.shadow != null)
    stack.shadow?.let {
        number(it.blur); number(it.distance); number(it.angle); boolean(it.allDirections)
        style(it.style); number(it.opacity); boolean(it.enabled)
    }
    with(stack.materialYouPack) {
        optionalInt(foreground); optionalInt(background); output.writeInt(selectedScheme)
        optionalStyle(customForeground); optionalStyle(customBackground); number(strokeScale)
    }
    optionalText(value.eraseMask)
    require(value.backgroundMasks.size <= EditorControlsCodec.MAX_STROKES)
    output.writeInt(value.backgroundMasks.size)
    value.backgroundMasks.forEach { ensureActive(); text(it.action.name); text(it.artwork) }
}

internal fun EditorArtworkReader.generation(): EditorGenerationSnapshot {
    val source = IconSourceOptions(
        primarySource = enumValueOf<Source>(text()), primaryTextType = enumValueOf<TextType>(text()), primaryIconPack = text(),
        secondarySource = enumValueOf<Source>(text()), secondaryImageEdit = enumValueOf<ImageEdit>(text()),
        secondaryTextType = enumValueOf<TextType>(text()), secondaryIconPack = text(), textCustom = text(),
        textCase = enumValueOf<TextCase>(text()), textFontPath = text(), fallbackSource = enumValueOf<FallbackSource>(text()),
        applicationIconVariant = enumValueOf<ApplicationIconVariant>(text()), invertMonochrome = boolean(),
        useFullApplicationIcon = boolean()
    )
    val export = IconExportOptions(boolean(), boolean(), boolean(), fill(), optionalStyle())
    val override = boolean()
    val imageEdit = enumValueOf<ImageEdit>(text())
    val colorizer = style()
    val edges = EdgeModifier(number(), number(), number(), boolean())
    val removal = BackgroundRemovalModifier(List(count(EditorControlsCodec.MAX_STYLE_VALUES, 4)) { input.readInt() }, number())
    val segments = List(count(EditorControlsCodec.MAX_SEGMENTS, 12)) {
        ensureActive()
        SegmentLayer(List(count(EditorControlsCodec.MAX_STYLE_VALUES, 4)) { input.readInt() }, number(), style())
    }
    val effect = EffectModifier(imageEdit, colorizer, edges, removal, segments)
    val transform = TransformModifier(number(), number(), number())
    val shape = if (boolean()) ShapeModifier(enumValueOf<IconShape>(text()), boolean(), number(), if (boolean()) fill() else null) else null
    val outline = if (boolean()) OutlineModifier(enumValueOf<OutlineMode>(text()), number(), fill()) else null
    val shadow = if (boolean()) ShadowModifier(number(), number(), number(), boolean(), style(), number(), boolean()) else null
    val pack = MaterialYouPackModifier(optionalInt(), optionalInt(), input.readInt(), optionalStyle(), optionalStyle(), number())
    val erase = optionalText()
    val masks = List(count(EditorControlsCodec.MAX_STROKES, 8)) { ensureActive(); EditorMaskReference(enumValueOf<BrushAction>(text()), text()) }
    return EditorGenerationSnapshot(GenerationOptions(source, ModifierStack(effect, transform, shape, outline, shadow, pack), export, override), erase, masks)
}

internal fun EditorArtworkWriter.style(value: ColorizerStyle) = EditorControlsCodec.Writer(output).style(value)
internal fun EditorArtworkReader.style(): ColorizerStyle = EditorControlsCodec.Reader(input).style()
internal fun EditorArtworkWriter.optionalStyle(value: ColorizerStyle?) { boolean(value != null); value?.let(::style) }
internal fun EditorArtworkReader.optionalStyle(): ColorizerStyle? = if (boolean()) style() else null
private fun EditorArtworkWriter.fill(value: ColorFill) { output.writeInt(value.color); optionalStyle(value.style) }
private fun EditorArtworkReader.fill() = ColorFill(input.readInt(), optionalStyle())
private fun EditorArtworkWriter.optionalInt(value: Int?) { boolean(value != null); value?.let(output::writeInt) }
private fun EditorArtworkReader.optionalInt(): Int? = if (boolean()) input.readInt() else null
