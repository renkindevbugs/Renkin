package dev.renkinProject.renkin.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import dev.renkinProject.renkin.IconPreviewBuilder
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.data.Source
import dev.renkinProject.renkin.data.TextType
import dev.renkinProject.renkin.drawable.IconPackDrawable
import dev.renkinProject.renkin.drawable.ResourceDrawable
import dev.renkinProject.renkin.icon.creator.GenerationOptions
import dev.renkinProject.renkin.packages.PackageInfoStruct
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises the per-app dialog's [IconDraftState] — the holder extracted from OptionsDialog
 * that decides how a preview icon is (re)generated and which draft Confirm would store. The
 * holder takes an [IconPreviewBuilder], so a fake stands in for the view model and the
 * branching is verified without Android UI. Robolectric only supplies the Drawable plumbing
 * the fake icons inherit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class IconDraftStateTest {

    /** A no-op [IconPackDrawable] used as a distinguishable sentinel. */
    private class FakeIcon : IconPackDrawable() {
        @Composable override fun getPainter(): Painter = throw UnsupportedOperationException()
        override fun toBitmap(): Bitmap = throw UnsupportedOperationException()
        override fun toDbString(): String = "fake"
        override fun draw(canvas: Canvas) {}
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("deprecated in Drawable")
        override fun getOpacity(): Int = PixelFormat.OPAQUE
    }

    /** Records what was asked of it and returns the configured icons. */
    private class FakeBuilder(
        private val previewResult: IconPackDrawable? = null,
        private val modifierResult: IconPackDrawable? = null
    ) : IconPreviewBuilder {
        var previewCalls = 0
        var applyCalls = 0
        var lastCustom: ResourceDrawable? = null
        var lastModifierInput: IconPackDrawable? = null

        override suspend fun previewIcon(
            app: PackageInfoStruct,
            options: GenerationOptions,
            customIcon: ResourceDrawable?
        ): IconPackDrawable? {
            previewCalls++
            lastCustom = customIcon
            return previewResult
        }

        override suspend fun applyModifier(
            icon: IconPackDrawable,
            options: GenerationOptions
        ): IconPackDrawable {
            applyCalls++
            lastModifierInput = icon
            return modifierResult ?: icon
        }
    }

    private fun app(createdIcon: IconPackDrawable? = null) = PackageInfoStruct(
        appName = "App",
        packageName = "com.example.app",
        activityName = "com.example.app.Main",
        icon = ColorDrawable(0),
        iconID = 0,
        createdIcon = createdIcon
    )

    private fun options(
        source: Source = Source.ICON_PACK,
        imageEdit: ImageEdit = ImageEdit.NONE,
        iconScale: Float = 1f
    ) = GenerationOptions(
        primarySource = source,
        primaryImageEdit = imageEdit,
        primaryTextType = TextType.FULL_NAME,
        primaryIconPack = "",
        color = 0,
        bgColor = 0,
        vector = false,
        materialYou = false,
        themed = false,
        override = true,
        iconScale = iconScale
    )

    private val customPick = ResourceDrawable(1, ColorDrawable(0))

    @Test
    fun firstRegenerateCreate_keepsInitialIcon_withoutCallingBuilder() = runBlocking {
        val initial = FakeIcon()
        val draft = IconDraftState(initial)
        val builder = FakeBuilder(previewResult = FakeIcon())

        // The first pass is the initial composition — it must not regenerate.
        draft.regenerateCreate(builder, app(), options(), customIconList = listOf(customPick))

        assertEquals(0, builder.previewCalls)
        assertEquals(0, builder.applyCalls)
        assertSame(initial, draft.iconToConfirm)
        assertFalse(draft.generating)
    }

    @Test
    fun regenerateCreate_explicitPick_buildsFromTheCustomDrawable() = runBlocking {
        val built = FakeIcon()
        val draft = IconDraftState(null)
        val builder = FakeBuilder(previewResult = built)

        draft.skipInitialGate(builder)
        draft.regenerateCreate(builder, app(), options(), customIconList = listOf(customPick))

        assertEquals(1, builder.previewCalls)
        assertEquals(0, builder.applyCalls)
        assertSame(customPick, builder.lastCustom)
        assertSame(built, draft.iconToConfirm)
    }

    @Test
    fun regenerateCreate_iconPackSourceWithoutPick_modifiesTheExistingIcon() = runBlocking {
        val existing = FakeIcon()
        val modified = FakeIcon()
        val draft = IconDraftState(existing)
        val builder = FakeBuilder(modifierResult = modified)

        draft.skipInitialGate(builder)
        draft.regenerateCreate(
            builder,
            app(createdIcon = existing),
            options(source = Source.ICON_PACK),
            customIconList = emptyList()
        )

        // No fresh pull from a pack — the saved icon is run through the modifier instead.
        assertEquals(0, builder.previewCalls)
        assertEquals(1, builder.applyCalls)
        assertSame(existing, builder.lastModifierInput)
        assertSame(modified, draft.iconToConfirm)
    }

    @Test
    fun regenerateCreate_textSource_buildsFromSourceWithNoCustomPick() = runBlocking {
        val built = FakeIcon()
        val draft = IconDraftState(null)
        val builder = FakeBuilder(previewResult = built)

        draft.skipInitialGate(builder)
        draft.regenerateCreate(
            builder,
            app(),
            options(source = Source.APPLICATION_NAME),
            customIconList = emptyList()
        )

        assertEquals(1, builder.previewCalls)
        assertNull(builder.lastCustom)
        assertSame(built, draft.iconToConfirm)
    }

    @Test
    fun regenerateVector_noEditAndDefaultScale_skipsTheBuilder() = runBlocking {
        val vector = FakeIcon()
        val draft = IconDraftState(null).apply {
            vectorIcon = vector
            origin = IconOrigin.VECTOR
        }
        val builder = FakeBuilder(modifierResult = FakeIcon())

        draft.regenerateVector(builder, options(imageEdit = ImageEdit.NONE, iconScale = 1f))

        // Nothing to apply → the untouched vector is what would be confirmed.
        assertEquals(0, builder.applyCalls)
        assertSame(vector, draft.iconToConfirm)
    }

    @Test
    fun regenerateVector_withScaleChange_runsTheModifier() = runBlocking {
        val vector = FakeIcon()
        val modified = FakeIcon()
        val draft = IconDraftState(null).apply {
            vectorIcon = vector
            origin = IconOrigin.VECTOR
        }
        val builder = FakeBuilder(modifierResult = modified)

        draft.regenerateVector(builder, options(imageEdit = ImageEdit.NONE, iconScale = 0.8f))

        assertEquals(1, builder.applyCalls)
        assertSame(vector, builder.lastModifierInput)
        assertSame(modified, draft.iconToConfirm)
    }

    @Test
    fun regenerateUpload_appliesModifierAndIsWhatUploadOriginConfirms() = runBlocking {
        val uploaded = FakeIcon()
        val modified = FakeIcon()
        val draft = IconDraftState(null).apply {
            uploadBase = uploaded
            origin = IconOrigin.UPLOAD
        }
        val builder = FakeBuilder(modifierResult = modified)

        draft.regenerateUpload(builder, options())

        assertEquals(1, builder.applyCalls)
        assertSame(modified, draft.iconToConfirm)
    }

    @Test
    fun hasIcon_isTrueWhenAnyDraftSourceIsPresent() {
        assertFalse(IconDraftState(null).hasIcon)
        assertTrue(IconDraftState(FakeIcon()).hasIcon)
        assertTrue(IconDraftState(null).apply { uploadBase = FakeIcon() }.hasIcon)
        assertTrue(IconDraftState(null).apply { vectorIcon = FakeIcon() }.hasIcon)
    }

    @Test
    fun iconToConfirm_uploadOrigin_fallsBackToCreateIconWhenNoUpload() {
        val create = FakeIcon()
        val draft = IconDraftState(create).apply { origin = IconOrigin.UPLOAD }
        // No upload generated yet → confirm keeps the create-tab icon rather than null.
        assertSame(create, draft.iconToConfirm)
    }

    /** Runs the once-only initial gate so the next regenerateCreate actually builds. */
    private suspend fun IconDraftState.skipInitialGate(builder: IconPreviewBuilder) {
        regenerateCreate(builder, app(), options(), customIconList = emptyList())
    }
}
