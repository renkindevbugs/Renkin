package dev.renkinProject.renkin.ui

import androidx.annotation.MainThread
import dev.renkinProject.renkin.data.editor.BoundEditorResources
import dev.renkinProject.renkin.data.editor.EditorDraftResource
import dev.renkinProject.renkin.data.editor.EditorDraftResourceKind
import dev.renkinProject.renkin.packages.PackageInfoStruct

/** Retain owned bytes for capture and native values for the editor renderers. */
internal class EditorSessionResources(
    private val owned: Map<String, EditorDraftResource>,
    private val native: BoundEditorResources
) {
    private var fontSelected = true
    fun font(reference: String) = owned["font"]?.takeIf {
        fontSelected && it.kind == EditorDraftResourceKind.FONT && it.selectionReference == reference
    }
    fun upload(reference: String) = owned["upload"]?.takeIf {
        it.kind == EditorDraftResourceKind.UPLOAD && it.selectionReference == reference
    }
    fun typeface(reference: String) = native.typeface.takeIf { font(reference) != null }
    fun uploadBitmap(reference: String) = native.upload.takeIf { upload(reference) != null }
    fun replaceFontSelection() { fontSelected = false }
}

/** Does not attach, accept, revalidate storage or transfer the preparation's native ownership. */
@MainThread
internal fun reconstructEditorSession(prepared: PreparedEditorRecovery): EditorSession {
    val bound = prepared.bound
    val value = bound.capture.snapshot
    val original = value.original
    val live = prepared.baseline.application
    fun artwork(slot: String?) = slot?.let { bound.artwork.getValue(it) }
    val application = PackageInfoStruct(
        appName = original.appName, packageName = live.packageName, activityName = live.activityName,
        iconSource = live.iconSource, iconID = live.iconID, internalVersion = live.internalVersion,
        originalName = original.originalName, baseIcon = artwork(original.base), createdIcon = artwork(original.rendered),
        modifierRecipe = original.recipe, modifierInput = bound.originalInput,
        calendarEnabled = original.calendar.enabled, calendarPrefix = original.calendar.prefix,
        calendarPackName = original.calendar.packName, sourcePackName = original.sourcePack,
        sourceUrl = original.sourceUrl, isFallback = original.fallback, isRefreshMade = original.refreshMade,
        isCustom = original.custom, isLegacy = original.legacy
    )
    return EditorSession(prepared.baseline.session, application, value.controls.create.iconPack,
        value.controls.create.fontReference, live).apply { restoreDraft(bound) }
}
