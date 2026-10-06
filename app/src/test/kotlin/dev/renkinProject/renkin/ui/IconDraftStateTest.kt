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
import dev.renkinProject.renkin.icon.creator.ColorizerStyle
import dev.renkinProject.renkin.icon.creator.EffectModifier
import dev.renkinProject.renkin.icon.creator.GenerationOptions
import dev.renkinProject.renkin.icon.creator.IconGenerationResult
import dev.renkinProject.renkin.icon.creator.ModifierInputPipeline
import dev.renkinProject.renkin.icon.creator.ModifierRecipeCodec
import dev.renkinProject.renkin.icon.creator.ColorFill
import dev.renkinProject.renkin.icon.creator.IconExportOptions
import dev.renkinProject.renkin.icon.creator.IconSourceOptions
import dev.renkinProject.renkin.icon.creator.ModifierStack
import dev.renkinProject.renkin.icon.creator.TransformModifier
import dev.renkinProject.renkin.icon.creator.ModifierInputOrigin
import dev.renkinProject.renkin.icon.creator.modifierInputForResult
import dev.renkinProject.renkin.packages.PackageInfoStruct
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
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
    @Test fun freshCreatePublishesOnlyTheWinningArtworkInputAndEffectiveRecipe() = runBlocking {
        val draft = IconDraftState(null)
        draft.skipInitialGate(FakeBuilder())
        val firstOptions = options(source = Source.APPLICATION_ICON, iconScale = 1.2f)
        val firstInput = checkNotNull(modifierInputForResult(FakeIcon(), firstOptions,
            ModifierInputOrigin.FRESH_SOURCE, ModifierInputPipeline.IMAGE_EDIT))
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val pending = launch {
            draft.regenerateCreate(FakeBuilder(freshResult = IconGenerationResult(FakeIcon(), firstInput, firstOptions),
                previewStarted = started, previewRelease = release), app(), firstOptions, emptyList())
        }
        started.await()
        assertNull(draft.modifierInputToConfirm)
        val winningOptions = options(source = Source.APPLICATION_ICON, iconScale = 1.5f)
        val winningIcon = FakeIcon()
        val winningInput = checkNotNull(modifierInputForResult(FakeIcon(), winningOptions,
            ModifierInputOrigin.FRESH_SOURCE, ModifierInputPipeline.IMAGE_EDIT))
        draft.regenerateCreate(FakeBuilder(freshResult = IconGenerationResult(winningIcon, winningInput, winningOptions)),
            app(), winningOptions, emptyList())
        release.complete(Unit)
        pending.join()
        assertSame(winningIcon, draft.iconToConfirm)
        assertSame(winningInput, draft.modifierInputToConfirm)
        assertEquals(1.5f, ModifierRecipeCodec.decode(draft.modifierRecipeToConfirm!!)!!.transform.scale)
    }

    @Test fun textRecipeUsesCompletedEffectiveOptionsWhenInputIsUnavailable() = runBlocking {
        val draft = IconDraftState(null)
        draft.skipInitialGate(FakeBuilder())
        val requested = options(source = Source.APPLICATION_NAME, imageEdit = ImageEdit.COLORIZE)
        val effective = options(source = Source.APPLICATION_NAME, imageEdit = ImageEdit.NONE)
        val icon = FakeIcon()
        draft.regenerateCreate(FakeBuilder(freshResult = IconGenerationResult(icon, renderedOptions = effective)),
            app(), requested, emptyList())
        assertSame(icon, draft.iconToConfirm)
        assertNull(draft.modifierInputToConfirm)
        assertEquals(ImageEdit.NONE, ModifierRecipeCodec.decode(draft.modifierRecipeToConfirm!!)!!.effect.imageEdit)
    }


    @Test fun initialMetadataIsRetainedUntilACompletedRenderAndClearedWithItsSource() = runBlocking {
        val original = FakeIcon()
        val originalInput = checkNotNull(modifierInputForResult(original, options(), ModifierInputOrigin.EXISTING_ICON))
        val draft = IconDraftState(original, "initial metadata", originalInput)
        assertEquals("initial metadata", draft.modifierRecipeToConfirm)
        assertSame(originalInput, draft.modifierInputToConfirm)
        val base = FakeIcon()
        draft.selectUpload(base)
        assertNull(draft.modifierRecipeToConfirm)
        assertNull(draft.modifierInputToConfirm)
        val firstOptions = options(iconScale = 1.2f)
        val firstResult = FakeIcon()
        draft.regenerateUpload(FakeBuilder(modifierResult = firstResult), firstOptions)
        val firstRecipe = draft.modifierRecipeToConfirm
        val firstInput = checkNotNull(draft.modifierInputToConfirm)
        assertSame(base, firstInput.liveInput)
        assertEquals(ModifierInputOrigin.UPLOAD, firstInput.origin)
        assertEquals(firstOptions, firstInput.options)
        assertFalse(firstInput.containsBakedModifierEdits)
        assertEquals(dev.renkinProject.renkin.icon.creator.StoredIconModifierRecipe.fromRenderedOptions(firstOptions), firstRecipe)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val pending = launch {
            draft.regenerateUpload(FakeBuilder(modifierResult = FakeIcon(), modifierStarted = started, modifierRelease = release), options(iconScale = 1.5f))
        }
        started.await()
        assertSame(firstResult, draft.iconToConfirm)
        assertEquals(firstRecipe, draft.modifierRecipeToConfirm)
        assertSame(firstInput, draft.modifierInputToConfirm)
        pending.cancelAndJoin()
        assertEquals(firstRecipe, draft.modifierRecipeToConfirm)
        assertSame(firstInput, draft.modifierInputToConfirm)
        draft.clearUpload()
        assertNull(draft.iconToConfirm)
        assertNull(draft.modifierRecipeToConfirm)
        assertNull(draft.modifierInputToConfirm)
    }

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
        private val modifierResult: IconPackDrawable? = null,
        private val modifierStarted: CompletableDeferred<Unit>? = null,
        private val modifierRelease: CompletableDeferred<Unit>? = null,
        private val freshResult: IconGenerationResult? = null,
        private val previewStarted: CompletableDeferred<Unit>? = null,
        private val previewRelease: CompletableDeferred<Unit>? = null
    ) : IconPreviewBuilder {
        override suspend fun previewIconWithInput(
            app: PackageInfoStruct, options: GenerationOptions, customIcon: ResourceDrawable?
        ): IconGenerationResult {
            if (freshResult == null) return super.previewIconWithInput(app, options, customIcon)
            previewStarted?.complete(Unit)
            previewRelease?.await()
            return freshResult
        }

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
            modifierStarted?.complete(Unit)
            modifierRelease?.await()
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
        source = IconSourceOptions(
            primarySource = source,
            primaryTextType = TextType.FULL_NAME,
            primaryIconPack = ""
        ),
        export = IconExportOptions(
            vector = false,
            materialYou = false,
            themed = false,
            background = ColorFill(0)
        ),
        override = true,
        modifiers = ModifierStack(
            effect = EffectModifier(imageEdit = imageEdit, colorizer = ColorizerStyle(firstColor = 0)),
            transform = TransformModifier(scale = iconScale),
        )
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
        val draft = IconDraftState(null).apply { selectVector(vector) }
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
        val draft = IconDraftState(null).apply { selectVector(vector) }
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
        val draft = IconDraftState(null).apply { selectUpload(uploaded) }
        val builder = FakeBuilder(modifierResult = modified)

        draft.regenerateUpload(builder, options())

        assertEquals(1, builder.applyCalls)
        assertSame(modified, draft.iconToConfirm)
    }

    @Test
    fun hasIcon_isTrueWhenAnyDraftSourceIsPresent() {
        assertFalse(IconDraftState(null).hasIcon)
        assertTrue(IconDraftState(FakeIcon()).hasIcon)
        assertTrue(IconDraftState(null).apply { selectUpload(FakeIcon()) }.hasIcon)
        assertTrue(IconDraftState(null).apply { selectVector(FakeIcon()) }.hasIcon)
    }

    @Test
    fun selectUpload_forgetsThePreviousCreateDraft() {
        val create = FakeIcon()
        val upload = FakeIcon()
        val draft = IconDraftState(create)

        draft.selectUpload(upload)

        assertEquals(IconOrigin.UPLOAD, draft.origin)
        assertSame(upload, draft.uploadBase)
        assertNull(draft.createIcon)
    }

    @Test
    fun selectVector_forgetsThePreviousUploadDraft() {
        val upload = FakeIcon()
        val vector = FakeIcon()
        val draft = IconDraftState(null).apply { selectUpload(upload) }

        draft.selectVector(vector)

        assertEquals(IconOrigin.VECTOR, draft.origin)
        assertSame(vector, draft.vectorIcon)
        assertNull(draft.uploadBase)
    }

    @Test
    fun selectCreate_forgetsUploadAndVectorDrafts() {
        val draft = IconDraftState(null).apply { selectUpload(FakeIcon()) }
        draft.selectVector(FakeIcon())

        draft.selectCreate()

        assertEquals(IconOrigin.CREATE, draft.origin)
        assertNull(draft.uploadBase)
        assertNull(draft.vectorIcon)
        assertNull(draft.iconToConfirm)
    }

    @Test
    fun cancelledUploadGeneration_clearsLoadingState() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val draft = IconDraftState(null).apply { selectUpload(FakeIcon()) }
        val builder = FakeBuilder(modifierStarted = started, modifierRelease = release)

        val job = launch { draft.regenerateUpload(builder, options()) }
        started.await()
        assertTrue(draft.generating)

        job.cancelAndJoin()

        assertFalse(draft.generating)
    }

    @Test
    fun staleUploadGeneration_doesNotReplaceANewerVectorSelection() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val staleResult = FakeIcon()
        val selectedVector = FakeIcon()
        val draft = IconDraftState(null).apply { selectUpload(FakeIcon()) }
        val builder = FakeBuilder(
            modifierResult = staleResult,
            modifierStarted = started,
            modifierRelease = release
        )

        val job = launch { draft.regenerateUpload(builder, options()) }
        started.await()
        draft.selectVector(selectedVector)
        draft.regenerateVector(FakeBuilder(), options())
        release.complete(Unit)
        job.join()

        assertEquals(IconOrigin.VECTOR, draft.origin)
        assertSame(selectedVector, draft.iconToConfirm)
    }

    @Test
    fun confirmedSourcePack_keepsOnlyThePackThatProducedTheDraft() {
        assertEquals(
            "pack.new",
            confirmedSourcePack(IconOrigin.CREATE, Source.ICON_PACK, "pack.new", "pack.old")
        )
        assertEquals(
            "pack.old",
            confirmedSourcePack(IconOrigin.CREATE, Source.ICON_PACK, null, "pack.old")
        )
        assertNull(confirmedSourcePack(IconOrigin.UPLOAD, Source.ICON_PACK, null, "pack.old"))
        assertNull(confirmedSourcePack(IconOrigin.VECTOR, Source.ICON_PACK, null, "pack.old"))
        assertNull(confirmedSourcePack(IconOrigin.CREATE, Source.APPLICATION_ICON, null, "pack.old"))
        assertNull(confirmedSourcePack(IconOrigin.CREATE, Source.APPLICATION_NAME, null, "pack.old"))
    }

    @Test
    fun unchangedReattachmentDoesNotReinterpretInitialIcon() = runBlocking {
        val initial = FakeIcon()
        val target = app(initial)
        val draft = IconDraftState(initial)
        val builder = FakeBuilder(modifierResult = FakeIcon())
        repeat(2) { draft.regenerateCreate(builder, target, options(), emptyList()) }
        assertEquals(0, builder.applyCalls)
        assertSame(initial, draft.iconToConfirm)
    }

    @Test
    fun completedCreateRenderIsRetainedAcrossReattachment() = runBlocking {
        val draft = IconDraftState(null)
        val built = FakeIcon()
        val builder = FakeBuilder(previewResult = built)
        draft.skipInitialGate(builder)
        repeat(2) { draft.regenerateCreate(builder, app(), options(), listOf(customPick)) }
        assertEquals(1, builder.previewCalls)
        assertSame(built, draft.iconToConfirm)
    }

    @Test
    fun cancelledCreateRenderCanRetryWithSameInputs() = runBlocking {
        val target = app(FakeIcon())
        val draft = IconDraftState(target.createdIcon)
        draft.regenerateCreate(FakeBuilder(), target, options(), emptyList())
        val started = CompletableDeferred<Unit>()
        val blocked = FakeBuilder(modifierStarted = started, modifierRelease = CompletableDeferred())
        val updated = options(iconScale = 0.8f)
        val job = launch { draft.regenerateCreate(blocked, target, updated, emptyList()) }
        started.await()
        job.cancelAndJoin()
        assertFalse(draft.generating)
        val finished = FakeIcon()
        val retry = FakeBuilder(modifierResult = finished)
        draft.regenerateCreate(retry, target, updated, emptyList())
        assertEquals(1, retry.applyCalls)
        assertSame(finished, draft.iconToConfirm)
    }

    @Test
    fun inactiveSourceEffectsCannotChangeUploadDraft() = runBlocking {
        val upload = FakeIcon()
        val draft = IconDraftState(null)
        draft.skipInitialGate(FakeBuilder())
        draft.selectUpload(upload)
        draft.regenerateUpload(FakeBuilder(), options())
        val builder = FakeBuilder(previewResult = FakeIcon(), modifierResult = FakeIcon())
        draft.regenerateCreate(builder, app(), options(), listOf(customPick))
        draft.regenerateVector(builder, options(iconScale = 0.8f))
        assertEquals(0, builder.previewCalls)
        assertEquals(0, builder.applyCalls)
        assertEquals(IconOrigin.UPLOAD, draft.origin)
        assertSame(upload, draft.iconToConfirm)
    }

    @Test
    fun returningToCompletedInputsInvalidatesOlderPendingRender() = runBlocking {
        val target = app(FakeIcon())
        val draft = IconDraftState(target.createdIcon)
        draft.regenerateCreate(FakeBuilder(), target, options(), emptyList())
        val completed = FakeIcon()
        val approved = options(iconScale = 0.8f)
        draft.regenerateCreate(FakeBuilder(modifierResult = completed), target, approved, emptyList())
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val pending = FakeBuilder(modifierResult = FakeIcon(), modifierStarted = started, modifierRelease = release)
        val job = launch { draft.regenerateCreate(pending, target, options(iconScale = 0.9f), emptyList()) }
        started.await()
        draft.regenerateCreate(FakeBuilder(), target, approved, emptyList())
        release.complete(Unit)
        job.join()
        assertSame(completed, draft.iconToConfirm)
    }

    /** Runs the once-only initial gate so the next regenerateCreate actually builds. */
    private suspend fun IconDraftState.skipInitialGate(builder: IconPreviewBuilder) {
        regenerateCreate(builder, app(), options(), customIconList = emptyList())
    }
}
