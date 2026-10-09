package dev.renkinProject.renkin.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import dev.renkinProject.renkin.apk.ProfileSessionToken
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.data.Source
import dev.renkinProject.renkin.data.TextType
import dev.renkinProject.renkin.drawable.AdaptiveIconPackDrawable
import dev.renkinProject.renkin.drawable.IconPackDrawable
import dev.renkinProject.renkin.drawable.ResourceDrawable
import dev.renkinProject.renkin.icon.creator.ApplicationIconVariant
import dev.renkinProject.renkin.icon.creator.ColorizerStyle
import dev.renkinProject.renkin.icon.creator.TextCase
import dev.renkinProject.renkin.icon.creator.VerifiedModifierInput
import dev.renkinProject.renkin.packages.PackageInfoStruct

// Live artwork and brush geometry belong to the same owner as their controls. Only the
// session identifier and profile token go into saved state; this object never enters a Bundle.
@Stable
internal class EditorSession(
    val owner: ProfileSessionToken,
    val application: PackageInfoStruct,
    initialPack: String,
    initialFont: String
) {
    var selectedTab by mutableIntStateOf(0)
    var source by mutableStateOf(Source.ICON_PACK)
    var browsingIconPacks by mutableStateOf(false)
    val createSourceToDisplay: Source get() = if (browsingIconPacks) Source.ICON_PACK else source
    var imageEdit by mutableStateOf(ImageEdit.NONE)
    var textType by mutableStateOf(TextType.FULL_NAME)
    var customText by mutableStateOf(application.appName)
    var textCase by mutableStateOf(TextCase.AS_IS)
    var textFontPath by mutableStateOf(initialFont)
    var useVector by mutableStateOf(false)
    var applicationIconVariant by mutableStateOf(ApplicationIconVariant.DEFAULT)
    var useFullApplicationIcon by mutableStateOf(false)
    var invertMonochrome by mutableStateOf(false)
    var materialYouScheme by mutableIntStateOf(0)
    var iconColor by mutableStateOf(Color.White)
    var customBgColor by mutableStateOf(Color.Black)
    var materialYouForegroundStyle by mutableStateOf(ColorizerStyle(firstColor = iconColor.toArgb()))
    var materialYouBackgroundStyle by mutableStateOf(ColorizerStyle(firstColor = customBgColor.toArgb()))
    var iconPack by mutableStateOf(initialPack)
    var customIconList by mutableStateOf<List<ResourceDrawable>>(emptyList())
    val draft = IconDraftState(
        application.baseIcon ?: application.createdIcon, application.modifierRecipe, application.modifierInput
    )
    val vectorEditState = VectorEditState()
    // Returning to the saved artwork must keep the raw baseline even after replay ends.
    var restoredIconSource by mutableStateOf<IconPackDrawable?>(null)
        private set
    private var restoredSourceContainsBakedModifierEdits = false
    internal fun restoredSourceBakedForDraft(): Boolean = restoredSourceContainsBakedModifierEdits
    val upload = UploadEditorState()
    val adjustments = AdjustmentState()
    val storedMaterialYouPackState =
        ((application.baseIcon ?: application.createdIcon) as? AdaptiveIconPackDrawable)?.materialYouEditState
    val materialYouPackAdjustments = MaterialYouPackAdjustmentState(storedMaterialYouPackState)
    var onlineImageUrl by mutableStateOf<String?>(null)
    var onlineImagePath by mutableStateOf<String?>(null)
    var calendarEnabled by mutableStateOf(application.calendarEnabled)
    var calendarPrefix by mutableStateOf(application.calendarPrefix)
    var calendarPackName by mutableStateOf(application.calendarPackName)
    var modifierReplay by mutableStateOf<EditorModifierReplay?>(null)
        private set
    var restoringModifiers by mutableStateOf(false)
    var modifierRestoreFailed by mutableStateOf(false)
    var replayMaterialYou by mutableStateOf(false)

    fun restoreModifiers(verified: VerifiedModifierInput) {
        browsingIconPacks = false
        restoredIconSource = verified.input
        restoredSourceContainsBakedModifierEdits = verified.retained.containsBakedModifierEdits
        vectorEditState.reset(restoredSourceContainsBakedModifierEdits)
        modifierReplay = EditorModifierReplay(verified).also { it.restoreControls(this) }
        draft.restore(verified)
        modifierRestoreFailed = false
    }

    private fun selectNewSource() {
        if (source == Source.NONE) source = Source.ICON_PACK
        modifierReplay = null
        modifierRestoreFailed = false
        draft.selectNewSource()
    }

    private fun clearCreateSelection() {
        customIconList = emptyList()
        materialYouPackAdjustments.reset()
        calendarEnabled = false
        calendarPrefix = null
        calendarPackName = null
    }

    fun selectCreateSource(newSource: Source) {
        if (newSource == Source.ICON_PACK && modifierReplay != null) {
            browsingIconPacks = true
        } else {
            source = newSource
            customIconList = emptyList()
            activateCreate()
        }
    }

    fun activateCreate() {
        // Browsing a pack is not a replacement source until an artwork is picked.
        if (modifierReplay != null && source == Source.ICON_PACK && customIconList.isEmpty()) return
        browsingIconPacks = false
        selectNewSource()
        vectorEditState.reset(restoredSourceContainsBakedModifierEdits)
        if (draft.origin != IconOrigin.CREATE) {
            draft.selectCreate()
            onlineImageUrl = null
            onlineImagePath = null
            upload.reset()
        }
    }

    fun activateUpload(icon: IconPackDrawable) {
        browsingIconPacks = false
        selectNewSource()
        if (draft.origin != IconOrigin.UPLOAD) {
            clearCreateSelection()
            vectorEditState.reset(restoredSourceContainsBakedModifierEdits)
        }
        draft.selectUpload(icon)
        upload.publishedSelection = upload.selection()
    }

    fun activateVector(icon: IconPackDrawable) {
        browsingIconPacks = false
        selectNewSource()
        if (draft.origin != IconOrigin.VECTOR) {
            clearCreateSelection()
            onlineImageUrl = null
            onlineImagePath = null
            upload.reset()
        }
        draft.selectVector(icon, vectorEditState.containsBakedModifierEdits)
        vectorEditState.publishedInputs = vectorEditState.inputs()
    }
}

@Stable
internal class UploadEditorState {
    data class Selection(val path: String?, val adaptive: Boolean, val zoom: Float)
    internal var publishedSelection: Selection? = null
    internal fun selection() = Selection(selectedImagePath, asAdaptiveIcon, zoomLevel)
    var selectedImagePath by mutableStateOf<String?>(null)
    var asAdaptiveIcon by mutableStateOf(false)
    var zoomLevel by mutableFloatStateOf(1f)

    fun reset() {
        publishedSelection = null
        selectedImagePath = null
        asAdaptiveIcon = false
        zoomLevel = 1f
    }

    fun selectImported(path: String?) {
        reset()
        selectedImagePath = path
    }
}
