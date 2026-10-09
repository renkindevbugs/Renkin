package dev.renkinProject.renkin.ui

import androidx.annotation.MainThread
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import dev.renkinProject.renkin.data.editor.EditorBrushStroke
import dev.renkinProject.renkin.data.editor.EditorControlsSnapshot

/** Only for an unpublished session whose detached controls passed recovery validation. */
@MainThread
internal fun EditorSession.restoreDraftControls(value: EditorControlsSnapshot) {
    selectedTab = value.selectedTab
    with(value.create) {
        this@restoreDraftControls.source = source
        this@restoreDraftControls.browsingIconPacks = browsingIconPacks
        this@restoreDraftControls.imageEdit = imageEdit
        this@restoreDraftControls.textType = textType
        this@restoreDraftControls.customText = customText
        this@restoreDraftControls.textCase = textCase
        textFontPath = fontReference
        this@restoreDraftControls.useVector = useVector
        this@restoreDraftControls.applicationIconVariant = applicationIconVariant
        this@restoreDraftControls.useFullApplicationIcon = useFullApplicationIcon
        this@restoreDraftControls.invertMonochrome = invertMonochrome
        this@restoreDraftControls.materialYouScheme = materialYouScheme
        iconColor = Color(iconColorBits.toULong())
        customBgColor = Color(backgroundColorBits.toULong())
        this@restoreDraftControls.materialYouForegroundStyle = materialYouForegroundStyle
        this@restoreDraftControls.materialYouBackgroundStyle = materialYouBackgroundStyle
        this@restoreDraftControls.iconPack = iconPack
        this@restoreDraftControls.replayMaterialYou = replayMaterialYou
    }
    upload.asAdaptiveIcon = value.upload.asAdaptiveIcon
    upload.zoomLevel = value.upload.zoomLevel
    with(adjustments) {
        val saved = value.adjustments
        colorizerStyle = saved.colorizerStyle
        shapeStyle = saved.shapeStyle
        outlineStyle = saved.outlineStyle
        shadowStyle = saved.shadowStyle
        edgeThreshold = saved.edgeThreshold
        edgeSmoothing = saved.edgeSmoothing
        edgeContrast = saved.edgeContrast
        colorizeLayers = saved.colorizeLayers
        iconScale = saved.iconScale
        bgRemovalTargets = saved.backgroundTargets
        bgRemovalTolerance = saved.backgroundTolerance
        autoCenter = saved.autoCenter
        iconOffsetX = saved.offsetX
        iconOffsetY = saved.offsetY
        iconShape = saved.shape
        shapeCrop = saved.shapeCrop
        shapeScale = saved.shapeScale
        outlineMode = saved.outlineMode
        outlineWidth = saved.outlineWidth
        shadowEnabled = saved.shadowEnabled
        shadowBlur = saved.shadowBlur
        shadowDistance = saved.shadowDistance
        shadowAngle = saved.shadowAngle
        shadowAllDirections = saved.shadowAllDirections
        shadowOpacity = saved.shadowOpacity
        eraseStrokes = saved.eraseStrokes.restoreGeometry()
        backgroundBrushStrokes = saved.backgroundBrushStrokes.restoreGeometry()
    }
    with(materialYouPackAdjustments) {
        selectedScheme = value.materialYouPack.selectedScheme
        customForeground = value.materialYouPack.customForeground
        customBackground = value.materialYouPack.customBackground
        strokeScale = value.materialYouPack.strokeScale
    }
    calendarEnabled = value.calendar.enabled
    calendarPrefix = value.calendar.prefix
    calendarPackName = value.calendar.packName
    onlineImageUrl = value.onlineImageUrl
}

private fun List<EditorBrushStroke>.restoreGeometry() = map { stroke ->
    BrushStroke(stroke.brush, stroke.points.map { Offset(it.x, it.y) }, stroke.action)
}
