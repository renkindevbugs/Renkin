package dev.renkinProject.renkin.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import dev.renkinProject.renkin.IconPreviewBuilder
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.apk.ProfileSessionToken
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.data.Source
import dev.renkinProject.renkin.data.editor.EditorCompleteCapture
import dev.renkinProject.renkin.data.editor.EditorDraftResource
import dev.renkinProject.renkin.data.editor.EditorDraftResourceKind
import dev.renkinProject.renkin.drawable.BitmapIconDrawable
import dev.renkinProject.renkin.drawable.IconPackDrawable
import dev.renkinProject.renkin.drawable.ResourceDrawable
import dev.renkinProject.renkin.icon.creator.ColorizerStyle
import dev.renkinProject.renkin.icon.creator.EffectModifier
import dev.renkinProject.renkin.icon.creator.GenerationOptions
import dev.renkinProject.renkin.icon.creator.IconExportOptions
import dev.renkinProject.renkin.icon.creator.IconSourceOptions
import dev.renkinProject.renkin.icon.creator.ModifierStack
import dev.renkinProject.renkin.packages.PackageInfoStruct
import org.robolectric.RuntimeEnvironment

internal object EditorRecoveryFixtures {
    fun icon(color: Int = Color.RED) = BitmapIconDrawable(
        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }, false)
    fun application(icon: IconPackDrawable = icon()) = PackageInfoStruct(
        "App", "test.editor", "Main", ColorDrawable(0), 0, createdIcon = icon, baseIcon = icon)
    fun editor(app: PackageInfoStruct = application(), owner: ProfileSessionToken = ProfileSessionToken(1, 2, "previous-process")) =
        EditorSession(owner, app, "pack", "")
    fun options() = GenerationOptions(IconSourceOptions(Source.ICON_PACK),
        ModifierStack(EffectModifier(ImageEdit.NONE, ColorizerStyle(firstColor = Color.BLUE))), IconExportOptions(), true)
    val builder = object : IconPreviewBuilder {
        override suspend fun previewIcon(app: PackageInfoStruct, options: GenerationOptions, customIcon: ResourceDrawable?): IconPackDrawable? = error("Unexpected source generation")
        override suspend fun applyModifier(icon: IconPackDrawable, options: GenerationOptions) = icon
    }
    fun font(reference: String = "") = EditorDraftResource(EditorDraftResourceKind.FONT, reference,
        RuntimeEnvironment.getApplication().resources.openRawResource(R.font.arcticonssans_regular).use { it.readBytes() })
    suspend fun capture(editor: EditorSession = editor()): EditorCompleteCapture {
        if (editor.draft.completedFrameSerial == null) editor.draft.regenerateCreate(builder, editor.application, options(), emptyList(), editor.captureRenderWitness())
        val owned = mapOf("font" to font(editor.textFontPath))
        return EditorCompleteMainCapture.capture(editor, owned.values.sumOf { it.byteCount.toLong() }).finish(owned)
    }
}
