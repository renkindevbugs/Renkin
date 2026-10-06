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
    val draft = IconDraftState(application.baseIcon ?: application.createdIcon)
    val vectorEditState = VectorEditState()
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

    private fun clearCreateSelection() {
        customIconList = emptyList()
        materialYouPackAdjustments.reset()
        calendarEnabled = false
        calendarPrefix = null
        calendarPackName = null
    }

    fun activateCreate() {
        if (draft.origin != IconOrigin.CREATE) {
            draft.selectCreate()
            vectorEditState.reset()
            onlineImageUrl = null
            onlineImagePath = null
            upload.reset()
        }
    }

    fun activateUpload(icon: IconPackDrawable) {
        if (draft.origin != IconOrigin.UPLOAD) {
            clearCreateSelection()
            vectorEditState.reset()
        }
        draft.selectUpload(icon)
    }

    fun activateVector(icon: IconPackDrawable) {
        if (draft.origin != IconOrigin.VECTOR) {
            clearCreateSelection()
            onlineImageUrl = null
            onlineImagePath = null
            upload.reset()
        }
        draft.selectVector(icon)
    }
}

@Stable
internal class UploadEditorState {
    var selectedImagePath by mutableStateOf<String?>(null)
    var asAdaptiveIcon by mutableStateOf(false)
    var zoomLevel by mutableFloatStateOf(1f)

    fun reset() {
        selectedImagePath = null
        asAdaptiveIcon = false
        zoomLevel = 1f
    }

    fun selectImported(path: String?) {
        reset()
        selectedImagePath = path
    }
}
