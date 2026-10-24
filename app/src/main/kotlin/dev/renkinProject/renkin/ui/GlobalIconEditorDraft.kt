package dev.renkinProject.renkin.ui

import android.graphics.Bitmap
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import dev.renkinProject.renkin.apk.ProfileSessionToken
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.data.Source
import dev.renkinProject.renkin.drawable.BitmapIconDrawable
import dev.renkinProject.renkin.drawable.IconPackDrawable
import dev.renkinProject.renkin.icon.creator.ColorFill
import dev.renkinProject.renkin.icon.creator.ColorizerStyle
import dev.renkinProject.renkin.icon.creator.EffectModifier
import dev.renkinProject.renkin.icon.creator.GenerationOptions
import dev.renkinProject.renkin.icon.creator.IconExportOptions
import dev.renkinProject.renkin.icon.creator.IconShape
import dev.renkinProject.renkin.icon.creator.IconSourceOptions
import dev.renkinProject.renkin.icon.creator.ModifierStack
import dev.renkinProject.renkin.packages.PackageInfoStruct

// Live artwork and controls survive activity recreation in the ViewModel, never SavedStateHandle.
internal class GlobalIconEditorDraft(
    val id: Long,
    val session: ProfileSessionToken,
    val app: PackageInfoStruct
) {
    var imageEdit by mutableStateOf(ImageEdit.NONE)
    var iconColor by mutableStateOf(Color.White)
    var useVector by mutableStateOf(false)
    val adjustments = AdjustmentState()

    var heroBitmap by mutableStateOf<Bitmap?>(null)
        private set
    var source by mutableStateOf(app.baseIcon ?: app.createdIcon)
        private set

    fun installSource(bitmap: Bitmap?) {
        heroBitmap = bitmap
        if (source == null && bitmap != null) source = BitmapIconDrawable(bitmap, false)
    }

    private val eraseMask by derivedStateOf {
        adjustments.eraseStrokes.takeIf { it.isNotEmpty() }?.let { buildEraseMask(it) }
    }
    private val backgroundBrushOperations by derivedStateOf {
        buildBackgroundBrushOperations(adjustments.backgroundBrushStrokes)
    }
    val options by derivedStateOf {
        GenerationOptions(
            source = IconSourceOptions(primarySource = Source.APPLICATION_ICON),
            export = IconExportOptions(
                vector = useVector,
                background = ColorFill(if (adjustments.iconShape != IconShape.NONE && !adjustments.shapeCrop) {
                    adjustments.shapeStyle.firstColor
                } else android.graphics.Color.TRANSPARENT)
            ),
            modifiers = ModifierStack(EffectModifier(imageEdit, ColorizerStyle(firstColor = iconColor.toArgb()))),
            override = true
        ).withModifierAdjustments(adjustments, imageEdit, eraseMask, backgroundBrushOperations)
    }
    val openingOptions = options
    private var capturedOptions = openingOptions
    private var revision = 0L

    fun captureRevision(options: GenerationOptions): Long {
        require(this.options == options)
        if (capturedOptions != options) {
            capturedOptions = options
            revision++
        }
        return revision
    }

    fun ownsPreview(options: GenerationOptions, source: IconPackDrawable): Boolean =
        this.options == options && this.source === source
}
