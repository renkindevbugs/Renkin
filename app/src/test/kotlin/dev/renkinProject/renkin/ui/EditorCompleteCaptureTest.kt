package dev.renkinProject.renkin.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import dev.renkinProject.renkin.IconPreviewBuilder
import dev.renkinProject.renkin.apk.ProfileSessionToken
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.data.Source
import dev.renkinProject.renkin.data.editor.*
import dev.renkinProject.renkin.drawable.BitmapIconDrawable
import dev.renkinProject.renkin.drawable.IconPackDrawable
import dev.renkinProject.renkin.drawable.ResourceDrawable
import dev.renkinProject.renkin.icon.creator.*
import dev.renkinProject.renkin.packages.PackageInfoStruct
import java.nio.ByteBuffer
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorCompleteCaptureTest {
    private fun icon(color: Int = Color.RED) = BitmapIconDrawable(
        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }, false)
    private fun editor(base: IconPackDrawable = icon(), rendered: IconPackDrawable = base) = EditorSession(
        ProfileSessionToken(1, 2, "old-process"), PackageInfoStruct("App", "test.app", "Main", ColorDrawable(0), 0,
            createdIcon = rendered, baseIcon = base, sourcePackName = "original-pack", sourceUrl = "original-url",
            isCustom = true, modifierRecipe = "original-recipe"), "pack", "")
    private fun options() = GenerationOptions(IconSourceOptions(Source.ICON_PACK),
        ModifierStack(EffectModifier(ImageEdit.NONE, ColorizerStyle(firstColor = Color.BLUE))), IconExportOptions(), true)
    private class Builder(
        val result: IconGenerationResult? = null,
        val started: CompletableDeferred<Unit>? = null, val release: CompletableDeferred<Unit>? = null
    ) : IconPreviewBuilder {
        var calls = 0
        override suspend fun previewIcon(app: PackageInfoStruct, options: GenerationOptions, customIcon: ResourceDrawable?): IconPackDrawable? = result?.icon
        override suspend fun previewIconWithInput(app: PackageInfoStruct, options: GenerationOptions, customIcon: ResourceDrawable?): IconGenerationResult {
            calls++; started?.complete(Unit); release?.await(); return requireNotNull(result)
        }
        override suspend fun applyModifier(icon: IconPackDrawable, options: GenerationOptions): IconPackDrawable {
            calls++; started?.complete(Unit); release?.await(); return result?.icon ?: icon
        }
    }
    private suspend fun initialize(editor: EditorSession, options: GenerationOptions = options()) {
        editor.draft.regenerateCreate(Builder(), editor.application, options, emptyList(), editor.captureRenderWitness())
    }
    private fun resources(editor: EditorSession): Map<String, EditorDraftResource> = buildMap {
        put("font", EditorDraftResource(EditorDraftResourceKind.FONT, editor.textFontPath, byteArrayOf(1, 2, 3)))
        editor.upload.selectedImagePath?.let { put("upload", EditorDraftResource(EditorDraftResourceKind.UPLOAD, it, byteArrayOf(4, 5))) }
    }
    private fun capture(editor: EditorSession): EditorCompleteCapture {
        val owned = resources(editor)
        return EditorCompleteMainCapture.capture(editor, owned.values.sumOf { it.byteCount.toLong() }).finish(owned)
    }
    private fun rejected(block: () -> Unit) = assertThrows(IllegalArgumentException::class.java, block)
    private fun pending(block: () -> Unit) = assertThrows(EditorCapturePendingException::class.java, block)
    private suspend fun roundTrip(value: EditorCompleteCapture): EditorCompleteCapture {
        val encoded = EditorCompleteCodec.encode(value)
        val decoded = EditorCompleteCodec.decode(encoded)
        assertEquals(value.snapshot, decoded.snapshot)
        val again = EditorCompleteCodec.encode(decoded)
        assertEquals(encoded.keys, again.keys)
        encoded.forEach { (key, bytes) -> assertArrayEquals(bytes, again.getValue(key)) }
        return decoded
    }

    @Test fun inactiveSourceClearsPreserveCreateFrameAndApply() = runBlocking<Unit> {
        val editor = editor(); initialize(editor)
        val before = editor.draft.captureState(editor.captureRenderWitness())
        editor.draft.clearVector(); editor.draft.clearUpload()
        assertTrue(editor.canConfirmDraft(editor.captureRenderWitness()))
        assertEquals(before.serial, editor.draft.captureState(editor.captureRenderWitness()).serial)
        assertSame(editor.application.baseIcon, editor.draft.iconToConfirm)
    }

    @Test fun activeSourceClearRejectsCaptureAndApply() = runBlocking<Unit> {
        val editor = editor(); editor.activateUpload(icon()); editor.draft.regenerateUpload(Builder(), options(), editor.captureRenderWitness())
        editor.draft.clearUpload()
        assertFalse(editor.canConfirmDraft(editor.captureRenderWitness())); pending { capture(editor) }
        editor.activateVector(icon()); editor.draft.regenerateVector(Builder(), options(), editor.captureRenderWitness())
        editor.draft.clearVector()
        assertFalse(editor.canConfirmDraft(editor.captureRenderWitness())); pending { capture(editor) }
    }

    @Test fun idleTabAcknowledgmentPreservesArtworkRecipeAndEffectiveOptions() = runBlocking<Unit> {
        val editor = editor(); initialize(editor)
        val before = editor.draft.captureState(editor.captureRenderWitness())
        editor.selectedTab = 3
        assertFalse(editor.canConfirmDraft(editor.captureRenderWitness()))
        editor.draft.acknowledgeWitness(editor.captureRenderWitness(), editor.application, options(), emptyList())
        val after = editor.draft.captureState(editor.captureRenderWitness())
        assertTrue(after.serial > before.serial); assertSame(before.artwork, after.artwork)
        assertEquals(before.recipe, after.recipe); assertNull(after.rendered)
        assertTrue(editor.canConfirmDraft(editor.captureRenderWitness()))
    }

    @Test fun acknowledgmentCannotAttachNewRenderOptionsToOldArtwork() = runBlocking<Unit> {
        val editor = editor(); initialize(editor); editor.selectedTab = 2
        val changed = options().copy(modifiers = options().modifiers.copy(transform = TransformModifier(1.5f)))
        editor.draft.acknowledgeWitness(editor.captureRenderWitness(), editor.application, changed, emptyList())
        assertFalse(editor.canConfirmDraft(editor.captureRenderWitness())); pending { capture(editor) }
    }

    @Test fun tabChangeDuringRenderCanAcknowledgeTheFinishedFrameWithoutRerendering() = runBlocking<Unit> {
        val editor = editor(); initialize(editor)
        val settings = options().copy(source = IconSourceOptions(Source.APPLICATION_ICON))
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val builder = Builder(IconGenerationResult(icon(Color.GREEN), draftInput = icon()), started, release)
        val witness = editor.captureRenderWitness()
        val job = launch { editor.draft.regenerateCreate(builder, editor.application, settings, emptyList(), witness) }
        started.await(); editor.selectedTab = 1
        pending { capture(editor) }
        editor.draft.acknowledgeWitness(editor.captureRenderWitness(), editor.application, settings, emptyList())
        assertTrue(editor.draft.generating)
        release.complete(Unit); job.join()
        editor.draft.acknowledgeWitness(editor.captureRenderWitness(), editor.application, settings, emptyList())
        assertTrue(editor.canConfirmDraft(editor.captureRenderWitness())); assertEquals(1, builder.calls)
        assertEquals(settings, capture(editor).snapshot.requested.options)
    }

    @Test fun obsoletePublicationGuardRejectsACompletedRender() = runBlocking<Unit> {
        val editor = editor(); initialize(editor)
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var current = true
        val builder = Builder(IconGenerationResult(icon(Color.GREEN), draftInput = icon()), started, release)
        val settings = options().copy(source = IconSourceOptions(Source.APPLICATION_ICON))
        val job = launch { editor.draft.regenerateCreate(builder, editor.application, settings, emptyList(), editor.captureRenderWitness(), { current }) }
        started.await(); current = false; editor.selectedTab = 2; release.complete(Unit); job.join()
        assertSame(editor.application.baseIcon, editor.draft.iconToConfirm)
        editor.draft.acknowledgeWitness(editor.captureRenderWitness(), editor.application, settings, emptyList())
        pending { capture(editor) }
    }

    @Test fun cancelledRenderKeepsThePreviousCompletedFrame() = runBlocking<Unit> {
        val editor = editor(); initialize(editor)
        val serial = editor.draft.completedFrameSerial
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val job = launch { editor.draft.regenerateCreate(Builder(IconGenerationResult(icon()), started, release), editor.application,
            options().copy(source = IconSourceOptions(Source.APPLICATION_ICON)), emptyList(), editor.captureRenderWitness()) }
        started.await(); job.cancelAndJoin()
        assertFalse(editor.draft.generating); assertEquals(serial, editor.draft.completedFrameSerial)
        assertSame(editor.application.baseIcon, editor.draft.iconToConfirm)
    }

    @Test fun sameSourceSelectionInvalidatesTheFrameAndChangesTheRenderRevision() = runBlocking<Unit> {
        val editor = editor(); initialize(editor); val revision = editor.draft.sourceRevision
        editor.activateCreate()
        assertTrue(editor.draft.sourceRevision > revision); pending { capture(editor) }
        initialize(editor)
        assertTrue(editor.canConfirmDraft(editor.captureRenderWitness()))
    }

    @Test fun originalUnsavedBaseAndRenderedArtworkRemainDistinctAndDetached() = runBlocking<Unit> {
        val base = icon(); val rendered = icon(Color.GREEN); val editor = editor(base, rendered); initialize(editor)
        val captured = capture(editor); val original = captured.snapshot.original
        assertNotEquals(original.base, original.rendered)
        assertEquals("original-recipe", original.recipe); assertTrue(original.custom)
        assertEquals("original-pack", original.sourcePack); assertEquals("original-url", original.sourceUrl)
        val bytes = EditorCompleteCodec.encode(captured)
        base.toBitmap().eraseColor(Color.BLACK); rendered.toBitmap().eraseColor(Color.BLACK)
        bytes.forEach { (key, value) -> assertArrayEquals(value, EditorCompleteCodec.encode(captured).getValue(key)) }
        roundTrip(captured)
    }

    @Test fun actualStageInputIsCapturedWithoutPortableModifierMetadata() = runBlocking<Unit> {
        val editor = editor(); initialize(editor); val raw = icon(); val rendered = icon(Color.GREEN)
        val settings = options().copy(source = IconSourceOptions(Source.APPLICATION_ICON))
        editor.draft.regenerateCreate(Builder(IconGenerationResult(rendered, renderedOptions = settings, draftInput = raw,
            draftPipeline = ModifierInputPipeline.IMAGE_EDIT)), editor.application, settings, emptyList(), editor.captureRenderWitness())
        val captured = roundTrip(capture(editor))
        assertNull(captured.snapshot.input); assertNotNull(captured.snapshot.raw)
        assertNotEquals(captured.snapshot.raw, captured.snapshot.rendered)
        assertEquals(ModifierInputPipeline.IMAGE_EDIT, captured.snapshot.pipeline)
    }

    @Test fun masksKeepActionsOrderAndIdentityDeduplicationInOneArtifact() = runBlocking<Unit> {
        val editor = editor(); initialize(editor)
        val masks = List(24) { icon(it).toBitmap() }
        val actions = masks.mapIndexed { index, bitmap -> BackgroundBrushOperation(if (index % 2 == 0) BrushAction.ERASE else BrushAction.RESTORE, bitmap) }
        val settings = options().copy(source = IconSourceOptions(Source.APPLICATION_ICON), modifiers = options().modifiers.copy(
            effect = options().modifiers.effect.copy(backgroundRemoval = BackgroundRemovalModifier(brushOperations = actions)),
            outline = OutlineModifier(OutlineMode.NONE, 3f, ColorFill(0), masks.first())))
        editor.draft.regenerateCreate(Builder(IconGenerationResult(icon(Color.GREEN), renderedOptions = settings, draftInput = icon())),
            editor.application, settings, emptyList(), editor.captureRenderWitness())
        val captured = capture(editor); val encoded = EditorCompleteCodec.encode(captured)
        assertTrue(encoded.size <= EditorDraftCodec.MAX_ARTIFACTS); assertTrue("masks" in encoded)
        assertFalse(encoded.keys.any { it.startsWith("mask-") })
        assertEquals(24, captured.artwork.keys.count(EditorCompleteCodec::isMaskSlot))
        val decoded = roundTrip(captured)
        assertEquals(actions.map { it.action }, decoded.snapshot.requested.backgroundMasks.map { it.action })
        assertEquals(decoded.snapshot.requested.eraseMask, decoded.snapshot.requested.backgroundMasks.first().artwork)
    }

    @Test fun allGenerationFieldsAndNullableGroupsRoundTripWithoutRecipeNormalization() = runBlocking<Unit> {
        val editor = editor(); initialize(editor)
        val style = ColorizerStyle(ColorizerMode.GRADIENT, GradientType.RADIAL, 123, listOf(5, 6), listOf(0.12f, 0.89f), 27.5f,
            flat = true, monochrome = true, inverse = true, lighten = true)
        val settings = options().copy(source = IconSourceOptions(Source.APPLICATION_NAME, primaryIconPack = "one", secondarySource = Source.ICON_PACK,
            secondaryImageEdit = ImageEdit.COLORIZE, secondaryIconPack = "two", textCustom = "ž😀", textCase = TextCase.UPPER,
            textFontPath = "attribution-only", applicationIconVariant = ApplicationIconVariant.MONOCHROME, invertMonochrome = true, useFullApplicationIcon = true),
            export = IconExportOptions(true, true, true, ColorFill(47, style), style), override = false,
            modifiers = ModifierStack(EffectModifier(ImageEdit.NONE, style, EdgeModifier(0.123f, 29.456f, -0f, true),
                BackgroundRemovalModifier(listOf(7, 8), 0.234f), listOf(SegmentLayer(listOf(9, 10), 0.345f, style))),
                TransformModifier(1.234f, -0.456f, 0.567f), ShapeModifier(IconShape.CIRCLE, false, 0.789f, null),
                OutlineModifier(OutlineMode.NONE, 5.678f, ColorFill(53, style)), ShadowModifier(2.345f, 3.456f, 54.321f, true, style, 0.678f, false),
                MaterialYouPackModifier(61, 62, 99, style, style, 1.345f)))
        editor.draft.regenerateCreate(Builder(IconGenerationResult(icon(), renderedOptions = settings, draftInput = icon())),
            editor.application, settings, emptyList(), editor.captureRenderWitness())
        assertEquals(settings, roundTrip(capture(editor)).snapshot.requested.options)
        roundTrip(capture(editor).let { it.copy(snapshot = it.snapshot.copy(requested = it.snapshot.requested.copy(
            options = settings.copy(modifiers = settings.modifiers.copy(shape = null, outline = null, shadow = null)))) ) })
    }

    @Test fun missingRequiredArtifactsAlwaysRejectWithIllegalArgumentException() = runBlocking<Unit> {
        val editor = editor(); initialize(editor); val bytes = EditorCompleteCodec.encode(capture(editor))
        for (slot in listOf("controls", "session", "vector")) rejected { runBlocking { EditorCompleteCodec.decode(bytes - slot) } }
    }

    @Test fun controlDigestTruncationAndTrailingSessionBytesRejectCapture() = runBlocking<Unit> {
        val editor = editor(); initialize(editor); val bytes = EditorCompleteCodec.encode(capture(editor))
        editor.selectedTab = 1
        rejected { runBlocking { EditorCompleteCodec.decode(bytes + ("controls" to EditorControlsCodec.encode(editor.captureControls()))) } }
        val session = bytes.getValue("session")
        for (invalid in listOf(session.copyOf(11), session + 0.toByte(), session.copyOf().also { ByteBuffer.wrap(it).putInt(4, 99) })) {
            rejected { runBlocking { EditorCompleteCodec.decode(bytes + ("session" to invalid)) } }
        }
    }

    @Test fun unknownUnreferencedAndMissingArtworkAreRejected() = runBlocking<Unit> {
        val editor = editor(); initialize(editor); val value = capture(editor); val bytes = EditorCompleteCodec.encode(value)
        rejected { runBlocking { EditorCompleteCodec.decode(bytes + ("unknown" to byteArrayOf(1))) } }
        rejected { EditorCompleteCodec.validate(value.copy(artwork = value.artwork - value.snapshot.raw!!)) }
        rejected { EditorCompleteCodec.validate(value.copy(artwork = value.artwork + ("artwork-99" to value.artwork.values.first()))) }
    }

    @Test fun resourceSelectionDigestAndVectorControlCorrespondenceAreRequired() = runBlocking<Unit> {
        val editor = editor(); initialize(editor); val value = capture(editor)
        rejected { EditorCompleteCodec.validate(value.copy(resources = emptyMap())) }
        rejected { EditorCompleteCodec.validate(value.copy(resources = mapOf("font" to EditorDraftResource(EditorDraftResourceKind.FONT, "other", byteArrayOf(1))))) }
        rejected { EditorCompleteCodec.validate(value.copy(snapshot = value.snapshot.copy(vector = value.snapshot.vector.copy(
            current = value.snapshot.vector.current.copy(thickness = 9f))))) }
    }

    @Test fun unpublishedUploadAndVectorEditsDeferCapture() = runBlocking<Unit> {
        val editor = editor(); editor.upload.selectImported("owned.png"); editor.activateUpload(icon())
        editor.draft.regenerateUpload(Builder(), options(), editor.captureRenderWitness())
        editor.upload.zoomLevel = 2f
        pending { capture(editor) }; assertFalse(editor.canConfirmDraft(editor.captureRenderWitness()))
        editor.vectorEditState.captureSeed(); editor.vectorEditState.thickness = 2f
        pending { editor.requirePublishedSources(editor.captureRenderWitness()) }
    }

    @Test fun preparedVectorSeedAcknowledgesCurrentEditsWithoutReplacingTheOriginalSeed() {
        val state = VectorEditState(); val seed = state.inputs(); state.thickness = 2f
        state.initialized = true; state.restoreDraftSeed(seed)
        assertEquals(seed, state.seedForDraft()); assertEquals(state.inputs(), state.publishedInputs)
        assertTrue(state.preserveDraftSeedPublication); assertTrue(state.hasChangesFromSeed)
    }

    @Test fun replayCaptureKeepsBaselineAndBakedFlagsAfterSwitchingSource() = runBlocking<Unit> {
        val raw = icon(); val settings = options()
        val retained = IconModifierInput(settings, true, ModifierInputOrigin.EXISTING_ICON, liveInput = raw)
        val verified = requireNotNull(VerifiedModifierInput.verify(RuntimeEnvironment.getApplication().resources, raw, retained, raw))
        val editor = EditorSession(ProfileSessionToken(1, 2, "provider"), PackageInfoStruct("App", "test", "Main", ColorDrawable(0), 0,
            createdIcon = raw, modifierInput = retained), "pack", "").apply { restoreModifiers(verified) }
        initialize(editor)
        val replay = roundTrip(capture(editor)).snapshot
        assertNotNull(replay.replay); assertTrue(replay.rawBaked); assertNotNull(replay.restoredExisting)
        editor.activateUpload(icon()); editor.draft.regenerateUpload(Builder(), settings, editor.captureRenderWitness())
        val switched = roundTrip(capture(editor)).snapshot
        assertNull(switched.replay); assertNotNull(switched.restoredExisting); assertTrue(switched.restoredSourceBaked); assertFalse(switched.rawBaked)
    }

    @Test fun budgetAndUnsupportedMainArtworkRejectBeforePublication() = runBlocking<Unit> {
        val editor = editor(); initialize(editor)
        rejected { EditorCompleteMainCapture.capture(editor, EditorDraftCodec.MAX_TOTAL_BYTES) }
        editor.adjustments.backgroundBrushStrokes = List(EditorControlsCodec.MAX_STROKES + 1) { BrushStroke(1f, emptyList(), BrushAction.ERASE) }
        rejected { editor.captureRenderWitness() }
    }

    @Test fun maskBundleRejectsBadVersionsCountsTruncationAndTrailingBytes() = runBlocking<Unit> {
        val editor = editor(); initialize(editor)
        val value = capture(editor)
        val masked = value.copy(snapshot = value.snapshot.copy(requested = value.snapshot.requested.copy(eraseMask = "mask-0")),
            artwork = value.artwork + ("mask-0" to value.artwork.values.first()))
        val encoded = EditorCompleteCodec.encode(masked); val masks = encoded.getValue("masks")
        val invalid = listOf(masks.copyOf(12), masks + 0.toByte(),
            masks.copyOf().also { ByteBuffer.wrap(it).putInt(4, 99) },
            masks.copyOf().also { ByteBuffer.wrap(it).putInt(8, EditorCompleteCodec.MAX_MASKS + 1) })
        for (bytes in invalid) rejected { runBlocking { EditorCompleteCodec.decode(encoded + ("masks" to bytes)) } }
    }

    @Test fun duplicateMaskSlotsAndNonBitmapMaskDocumentsAreRejected() = runBlocking<Unit> {
        val editor = editor(); initialize(editor); val value = capture(editor)
        val masked = value.copy(snapshot = value.snapshot.copy(requested = value.snapshot.requested.copy(eraseMask = "mask-0")),
            artwork = value.artwork + ("mask-0" to value.artwork.values.first()))
        val encoded = EditorCompleteCodec.encode(masked)
        val duplicate = EditorArtworkWriter().run {
            output.writeInt(0x524b454d); output.writeInt(1); output.writeInt(2)
            val mask = EditorArtworkCodec.encode(value.artwork.values.first())
            repeat(2) { text("mask-0"); output.writeInt(mask.size); output.write(mask) }; result()
        }
        rejected { runBlocking { EditorCompleteCodec.decode(encoded + ("masks" to duplicate)) } }
        val adaptive = EditorAdaptiveArtwork(EditorArtworkBytes(byteArrayOf(1)), EditorArtworkBytes(byteArrayOf(2)), null, null, null, null)
        rejected { EditorCompleteCodec.validate(masked.copy(artwork = masked.artwork + ("mask-0" to adaptive))) }
    }

    @Test fun multiDigitArtworkSlotsUseTheSameValidationAndDecodingRules() = runBlocking<Unit> {
        val editor = editor(); initialize(editor); val value = capture(editor)
        val old = value.snapshot.raw!!
        val renamed = value.copy(snapshot = value.snapshot.copy(raw = "artwork-10", rendered = "artwork-10",
            original = value.snapshot.original.copy(base = "artwork-10", rendered = "artwork-10")),
            artwork = (value.artwork - old) + ("artwork-10" to value.artwork.getValue(old)))
        roundTrip(renamed)
    }
}
