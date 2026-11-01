package dev.renkinProject.renkin.ui

import androidx.annotation.MainThread
import dev.renkinProject.renkin.data.editor.EditorAdjustmentControls
import dev.renkinProject.renkin.data.editor.EditorBrushPoint
import dev.renkinProject.renkin.data.editor.EditorBrushStroke
import dev.renkinProject.renkin.data.editor.EditorCalendarControls
import dev.renkinProject.renkin.data.editor.EditorControlsCodec
import dev.renkinProject.renkin.data.editor.EditorControlsSnapshot
import dev.renkinProject.renkin.data.editor.EditorCreateControls
import dev.renkinProject.renkin.data.editor.EditorPackControls
import dev.renkinProject.renkin.data.editor.EditorUploadControls
import dev.renkinProject.renkin.data.editor.EditorVectorControls
import dev.renkinProject.renkin.data.editor.EditorViewport
import dev.renkinProject.renkin.icon.creator.ColorizerStyle

/** Captures independent values; artwork, selected files and replay ownership are captured separately. */
@MainThread
internal fun EditorSession.captureControls(): EditorControlsSnapshot {
    requireCaptureBounds()
    return EditorControlsSnapshot(
        selectedTab = selectedTab,
        create = EditorCreateControls(
            source = source, browsingIconPacks = browsingIconPacks, imageEdit = imageEdit,
            textType = textType, customText = customText, textCase = textCase, fontReference = textFontPath,
            useVector = useVector, applicationIconVariant = applicationIconVariant,
            useFullApplicationIcon = useFullApplicationIcon, invertMonochrome = invertMonochrome,
            materialYouScheme = materialYouScheme, iconColorBits = iconColor.value.toLong(),
            backgroundColorBits = customBgColor.value.toLong(),
            materialYouForegroundStyle = materialYouForegroundStyle.copyLists(),
            materialYouBackgroundStyle = materialYouBackgroundStyle.copyLists(),
            iconPack = iconPack, replayMaterialYou = replayMaterialYou
        ),
        upload = EditorUploadControls(upload.asAdaptiveIcon, upload.zoomLevel),
        vector = with(vectorEditState) { EditorVectorControls(
            initialized = initialized, thickness = thickness, automaticallyCenter = automaticallyCenter,
            viewport = viewportOverride?.let { EditorViewport(it.first, it.second) },
            sourceUrl = sourceUrl, containsBakedModifierEdits = containsBakedModifierEdits
        ) },
        adjustments = with(adjustments) { EditorAdjustmentControls(
            colorizerStyle = colorizerStyle.copyLists(), shapeStyle = shapeStyle.copyLists(),
            outlineStyle = outlineStyle.copyLists(), shadowStyle = shadowStyle.copyLists(),
            edgeThreshold = edgeThreshold, edgeSmoothing = edgeSmoothing, edgeContrast = edgeContrast,
            colorizeLayers = colorizeLayers.map { it.copy(targets = it.targets.toList(), style = it.style.copyLists()) },
            iconScale = iconScale, backgroundTargets = bgRemovalTargets.toList(), backgroundTolerance = bgRemovalTolerance,
            autoCenter = autoCenter, offsetX = iconOffsetX, offsetY = iconOffsetY,
            shape = iconShape, shapeCrop = shapeCrop, shapeScale = shapeScale,
            outlineMode = outlineMode, outlineWidth = outlineWidth, shadowEnabled = shadowEnabled,
            shadowBlur = shadowBlur, shadowDistance = shadowDistance, shadowAngle = shadowAngle,
            shadowAllDirections = shadowAllDirections, shadowOpacity = shadowOpacity,
            eraseStrokes = eraseStrokes.copyGeometry(), backgroundBrushStrokes = backgroundBrushStrokes.copyGeometry()
        ) },
        materialYouPack = with(materialYouPackAdjustments) { EditorPackControls(
            selectedScheme, customForeground.copyLists(), customBackground.copyLists(), strokeScale
        ) },
        calendar = EditorCalendarControls(calendarEnabled, calendarPrefix, calendarPackName),
        onlineImageUrl = onlineImageUrl
    )
}

private fun EditorSession.requireCaptureBounds() {
    val layers = adjustments.colorizeLayers
    require(layers.size <= EditorControlsCodec.MAX_SEGMENTS) { "Too many editor segments" }
    require(adjustments.bgRemovalTargets.size <= EditorControlsCodec.MAX_STYLE_VALUES) { "Too many editor target colors" }
    for (layer in layers) {
        require(layer.targets.size <= EditorControlsCodec.MAX_STYLE_VALUES) { "Too many editor segment colors" }
        layer.style.requireCaptureBounds()
    }
    listOf(materialYouForegroundStyle, materialYouBackgroundStyle, adjustments.colorizerStyle, adjustments.shapeStyle,
        adjustments.outlineStyle, adjustments.shadowStyle, materialYouPackAdjustments.customForeground,
        materialYouPackAdjustments.customBackground).forEach { it.requireCaptureBounds() }
    var points = 0L
    for (strokes in listOf(adjustments.eraseStrokes, adjustments.backgroundBrushStrokes)) {
        require(strokes.size <= EditorControlsCodec.MAX_STROKES) { "Too many editor brush strokes" }
        points += strokes.sumOf { it.points.size.toLong() }
        require(points <= EditorControlsCodec.MAX_POINTS) { "Too many editor brush points" }
    }
    listOf(customText, textFontPath, iconPack, vectorEditState.sourceUrl, calendarPrefix, calendarPackName, onlineImageUrl)
        .forEach { require(it == null || it.length <= EditorControlsCodec.MAX_TEXT_BYTES) { "Editor control text is too large" } }
}

private fun ColorizerStyle.requireCaptureBounds() {
    require(gradientStops.size <= EditorControlsCodec.MAX_STYLE_VALUES &&
        gradientPositions.size <= EditorControlsCodec.MAX_STYLE_VALUES) { "Too many editor gradient values" }
}

private fun ColorizerStyle.copyLists(): ColorizerStyle = copy(
    gradientStops = gradientStops.toList(), gradientPositions = gradientPositions.toList()
)

private fun List<BrushStroke>.copyGeometry(): List<EditorBrushStroke> = map { stroke ->
    EditorBrushStroke(stroke.brush, stroke.points.map { EditorBrushPoint(it.x, it.y) }, stroke.action)
}
