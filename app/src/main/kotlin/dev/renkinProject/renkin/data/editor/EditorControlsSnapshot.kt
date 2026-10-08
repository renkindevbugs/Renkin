package dev.renkinProject.renkin.data.editor

import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.data.Source
import dev.renkinProject.renkin.data.TextType
import dev.renkinProject.renkin.icon.creator.ApplicationIconVariant
import dev.renkinProject.renkin.icon.creator.BrushAction
import dev.renkinProject.renkin.icon.creator.ColorizerStyle
import dev.renkinProject.renkin.icon.creator.IconShape
import dev.renkinProject.renkin.icon.creator.OutlineMode
import dev.renkinProject.renkin.icon.creator.SegmentLayer
import dev.renkinProject.renkin.icon.creator.TextCase

/** Controls only: publishing a recoverable draft also requires source/artwork/replay state. */
internal data class EditorControlsSnapshot(
    val selectedTab: Int,
    val create: EditorCreateControls,
    val upload: EditorUploadControls,
    val vector: EditorVectorControls,
    val adjustments: EditorAdjustmentControls,
    val materialYouPack: EditorPackControls,
    val calendar: EditorCalendarControls,
    val onlineImageUrl: String?
)

internal data class EditorCreateControls(
    val source: Source,
    val browsingIconPacks: Boolean,
    val imageEdit: ImageEdit,
    val textType: TextType,
    val customText: String,
    val textCase: TextCase,
    // A selection identifier only; it is not authority to open a filesystem path on restore.
    val fontReference: String,
    val useVector: Boolean,
    val applicationIconVariant: ApplicationIconVariant,
    val useFullApplicationIcon: Boolean,
    val invertMonochrome: Boolean,
    val materialYouScheme: Int,
    // Preserve Compose Color's packed value, including its original color space.
    val iconColorBits: Long,
    val backgroundColorBits: Long,
    val materialYouForegroundStyle: ColorizerStyle,
    val materialYouBackgroundStyle: ColorizerStyle,
    val iconPack: String,
    val replayMaterialYou: Boolean
)

internal data class EditorUploadControls(val asAdaptiveIcon: Boolean, val zoomLevel: Float)

internal data class EditorViewport(val width: Float, val height: Float)

internal data class EditorVectorControls(
    val initialized: Boolean,
    val thickness: Float,
    val automaticallyCenter: Boolean,
    val viewport: EditorViewport?,
    val sourceUrl: String?,
    val containsBakedModifierEdits: Boolean
)

internal data class EditorAdjustmentControls(
    val colorizerStyle: ColorizerStyle,
    val shapeStyle: ColorizerStyle,
    val outlineStyle: ColorizerStyle,
    val shadowStyle: ColorizerStyle,
    val edgeThreshold: Float,
    val edgeSmoothing: Float,
    val edgeContrast: Boolean,
    val colorizeLayers: List<SegmentLayer>,
    val iconScale: Float,
    val backgroundTargets: List<Int>,
    val backgroundTolerance: Float,
    val autoCenter: Boolean,
    val offsetX: Float,
    val offsetY: Float,
    val shape: IconShape,
    val shapeCrop: Boolean,
    val shapeScale: Float,
    val outlineMode: OutlineMode,
    val outlineWidth: Float,
    val shadowEnabled: Boolean,
    val shadowBlur: Float,
    val shadowDistance: Float,
    val shadowAngle: Float,
    val shadowAllDirections: Boolean,
    val shadowOpacity: Float,
    val eraseStrokes: List<EditorBrushStroke>,
    val backgroundBrushStrokes: List<EditorBrushStroke>
)

internal data class EditorBrushPoint(val x: Float, val y: Float)

internal data class EditorBrushStroke(val brush: Float, val points: List<EditorBrushPoint>, val action: BrushAction)

internal data class EditorPackControls(
    val selectedScheme: Int,
    val customForeground: ColorizerStyle,
    val customBackground: ColorizerStyle,
    val strokeScale: Float
)

internal data class EditorCalendarControls(val enabled: Boolean, val prefix: String?, val packName: String?)
