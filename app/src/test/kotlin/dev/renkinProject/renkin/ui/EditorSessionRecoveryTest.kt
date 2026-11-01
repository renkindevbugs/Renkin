package dev.renkinProject.renkin.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.PathNode
import dev.renkinProject.renkin.IconPreviewBuilder
import dev.renkinProject.renkin.apk.ApplicationProvider
import dev.renkinProject.renkin.apk.ProfileSessionToken
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.data.Profile
import dev.renkinProject.renkin.data.Source
import dev.renkinProject.renkin.data.TextType
import dev.renkinProject.renkin.data.editor.*
import dev.renkinProject.renkin.drawable.AdaptiveIconPackDrawable
import dev.renkinProject.renkin.drawable.BitmapIconDrawable
import dev.renkinProject.renkin.drawable.IconPackDrawable
import dev.renkinProject.renkin.drawable.ResourceDrawable
import dev.renkinProject.renkin.icon.creator.*
import dev.renkinProject.renkin.packages.PackageInfoStruct
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
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
class EditorSessionRecoveryTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val owner = ProfileSessionToken(1, 17, "current-process")
    private val noBuilder = object : IconPreviewBuilder {
        override suspend fun previewIcon(app: PackageInfoStruct, options: GenerationOptions, customIcon: ResourceDrawable?): IconPackDrawable? =
            error("Recovery must not resolve a live source")
        override suspend fun applyModifier(icon: IconPackDrawable, options: GenerationOptions): IconPackDrawable =
            error("Recovery must not modify the rendered result")
    }

    private suspend fun prepare(value: EditorCompleteCapture): PreparedEditorRecovery {
        val decoded = EditorCompleteCodec.decode(EditorCompleteCodec.encode(value))
        val bound = bindEditorRecovery(decoded, EditorRecoveryResources(context))
        val live = EditorRecoveryFixtures.application(EditorRecoveryFixtures.icon(android.graphics.Color.GREEN))
        val baseline = ApplicationProvider.EditorDraftBaselineSource(owner, live, Profile(1, "Test"), null, 0, 0, 0)
        return PreparedEditorRecovery(EditorDraftCheckpoint("generation", "draft"),
            EditorDraftOwner(1, live.packageName, live.activityName, "profile", "icon"), baseline, 0, bound, Any())
    }

    private suspend fun withRecovery(value: EditorCompleteCapture, action: suspend (PreparedEditorRecovery, EditorSession) -> Unit) {
        val prepared = prepare(value)
        try { action(prepared, prepared.sessionForAcceptance()) } finally { prepared.disposeUnattached() }
    }

    private fun pixels(expected: IconPackDrawable, actual: IconPackDrawable?) {
        assertNotNull(actual)
        assertTrue("Rendered pixels differ", expected.toModifierBitmap().sameAs(actual!!.toModifierBitmap()))
    }

    @Test fun restoresControlsAndBrushGeometryWithoutReplacingSeparatelyBoundVectorInputs() {
        val initial = EditorRecoveryFixtures.editor().captureControls()
        fun style(color: Int) = ColorizerStyle(ColorizerMode.GRADIENT, GradientType.RADIAL, color,
            listOf(color + 1, color + 2), listOf(0f, 0.3f, 1f), 219f, true, true, true, true)
        val value = initial.copy(selectedTab = 3, create = initial.create.copy(source = Source.APPLICATION_NAME,
            browsingIconPacks = true, imageEdit = ImageEdit.COLORIZE, textType = TextType.CUSTOM,
            customText = "ž 😀", textCase = TextCase.UPPER, fontReference = "/missing/font", useVector = true,
            applicationIconVariant = ApplicationIconVariant.MATERIAL_YOU, useFullApplicationIcon = true,
            invertMonochrome = true, materialYouScheme = 42, iconColorBits = Color.Red.value.toLong(),
            backgroundColorBits = Color.Blue.value.toLong(), materialYouForegroundStyle = style(11),
            materialYouBackgroundStyle = style(22), iconPack = "absent.pack", replayMaterialYou = true),
            upload = EditorUploadControls(true, 0.73f), vector = EditorVectorControls(true, 1.7f, false,
                EditorViewport(17f, 29f), "vector-url", true), adjustments = initial.adjustments.copy(
                colorizerStyle = style(33), shapeStyle = style(44), outlineStyle = style(55), shadowStyle = style(66),
                edgeThreshold = 1.3f, edgeSmoothing = 3.1f, edgeContrast = true,
                colorizeLayers = listOf(SegmentLayer(listOf(7, 8), 0.4f, style(77))), iconScale = 0.81f,
                backgroundTargets = listOf(9, 10), backgroundTolerance = 0.23f, autoCenter = false,
                offsetX = -0.13f, offsetY = 0.14f, shape = IconShape.CIRCLE, shapeCrop = false, shapeScale = 0.91f,
                outlineMode = OutlineMode.NONE, outlineWidth = 15f, shadowEnabled = false, shadowBlur = 31f,
                shadowDistance = 27f, shadowAngle = 123f, shadowAllDirections = true, shadowOpacity = 0.49f,
                eraseStrokes = listOf(EditorBrushStroke(0.12f, listOf(EditorBrushPoint(0.3f, 0.4f)), BrushAction.ERASE)),
                backgroundBrushStrokes = listOf(EditorBrushStroke(0.21f, listOf(EditorBrushPoint(0.6f, 0.7f)), BrushAction.RESTORE))),
            materialYouPack = EditorPackControls(-1, style(88), style(99), 1.37f),
            calendar = EditorCalendarControls(false, "prefix", "calendar-pack"), onlineImageUrl = "online-url")
        val editor = EditorRecoveryFixtures.editor()
        editor.restoreDraftControls(EditorControlsCodec.decode(EditorControlsCodec.encode(value)))
        assertEquals(value.copy(vector = initial.vector), editor.captureControls())
    }

    @Test fun initialSavedFrameKeepsItsUnknownEffectiveOptionsAndDoesNotRunABuilder() = runBlocking<Unit> {
        val value = EditorRecoveryFixtures.capture()
        withRecovery(value) { prepared, editor ->
            assertSame(prepared.baseline.application, editor.baselineApplication)
            assertNotSame(editor.application, editor.baselineApplication)
            assertEquals(owner, editor.owner)
            assertTrue(editor.canConfirmDraft(editor.captureRenderWitness()))
            val before = editor.draft.iconToConfirm
            editor.draft.regenerateCreate(noBuilder, editor.application, prepared.bound.requested,
                editor.customIconList, editor.captureRenderWitness())
            assertSame(before, editor.draft.iconToConfirm)
            assertNull(editor.draft.captureState(editor.captureRenderWitness()).rendered)
            pixels(EditorRecoveryFixtures.icon(), before)
        }
    }

    @Test fun capturedOriginalUnsavedArtworkAndAttributionStaySeparateFromLiveBaseline() = runBlocking<Unit> {
        val original = PackageInfoStruct("Unsaved label", "test.editor", "Main", ColorDrawable(0), 3,
            createdIcon = EditorRecoveryFixtures.icon(android.graphics.Color.BLUE), baseIcon = EditorRecoveryFixtures.icon(),
            originalName = "Original name", calendarEnabled = true, calendarPrefix = "day_", calendarPackName = "calendar",
            sourcePackName = "pack", sourceUrl = "url", isFallback = true, isRefreshMade = true, isCustom = true, isLegacy = true)
        withRecovery(EditorRecoveryFixtures.capture(EditorRecoveryFixtures.editor(original))) { prepared, editor ->
            assertEquals("Unsaved label", editor.application.appName)
            assertEquals("Original name", editor.application.originalName)
            assertEquals("day_", editor.application.calendarPrefix)
            assertEquals("calendar", editor.application.calendarPackName)
            assertEquals("pack", editor.application.sourcePackName)
            assertEquals("url", editor.application.sourceUrl)
            assertTrue(editor.application.isFallback && editor.application.isRefreshMade && editor.application.isCustom && editor.application.isLegacy)
            pixels(EditorRecoveryFixtures.icon(), editor.application.baseIcon)
            pixels(EditorRecoveryFixtures.icon(android.graphics.Color.BLUE), editor.application.createdIcon)
            pixels(EditorRecoveryFixtures.icon(android.graphics.Color.GREEN), prepared.baseline.application.baseIcon)
        }
    }

    private suspend fun packDraft(raw: IconPackDrawable, pipeline: ModifierInputPipeline): EditorCompleteCapture {
        val editor = EditorRecoveryFixtures.editor()
        val options = EditorRecoveryFixtures.options()
        editor.draft.regenerateCreate(EditorRecoveryFixtures.builder, editor.application, options, emptyList())
        editor.customIconList = listOf(ResourceDrawable(1234567, raw)); editor.iconPack = "uninstalled.pack"
        editor.activateCreate()
        val result = IconModifierStageRenderer(context.resources, options).render(raw, pipeline)
        editor.draft.regenerateCreate(noBuilder, editor.application, options, editor.customIconList, editor.captureRenderWitness(),
            renderSource = { _, _ -> IconGenerationResult(result, renderedOptions = options, draftInput = raw, draftPipeline = pipeline) })
        return EditorRecoveryFixtures.capture(editor)
    }

    @Test fun missingPackModifierEditRendersCanonicalStageAndRetainsItsPipeline() = runBlocking<Unit> {
        val raw = BitmapIconDrawable(Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).drawCircle(16f, 16f, 8f, Paint().apply { color = android.graphics.Color.RED }) }, false)
        for (pipeline in listOf(ModifierInputPipeline.IMAGE_EDIT, ModifierInputPipeline.BITMAP_IMAGE_EDIT)) {
            withRecovery(packDraft(raw, pipeline)) { prepared, editor ->
                val options = prepared.bound.requested.withModifiers { copy(transform = TransformModifier(0.8f, 0.1f, -0.1f)) }
                val expected = IconModifierStageRenderer(context.resources, options).render(raw, pipeline)
                assertNotNull(editor.draft.recoveredCreateStage)
                editor.draft.regenerateCreate(noBuilder, editor.application, options, editor.customIconList, editor.captureRenderWitness())
                pixels(expected, editor.draft.iconToConfirm)
                val frame = editor.draft.captureState(editor.captureRenderWitness())
                assertEquals(pipeline, frame.pipeline); assertSame(prepared.bound.createStage!!.input, frame.raw)
                assertTrue(editor.canConfirmDraft(editor.captureRenderWitness()))
            }
        }
    }

    @Test fun adaptivePackStageIsNotRestyledTwiceOrResolvedByResourceId() = runBlocking<Unit> {
        val raw = AdaptiveIconPackDrawable.capture(AdaptiveIconDrawable(ColorDrawable(android.graphics.Color.BLUE), ColorDrawable(android.graphics.Color.RED)))
        withRecovery(packDraft(raw, ModifierInputPipeline.IMAGE_EDIT)) { prepared, editor ->
            val options = prepared.bound.requested.withModifiers { copy(materialYouPack = MaterialYouPackModifier(
                foreground = android.graphics.Color.GREEN, background = android.graphics.Color.YELLOW, strokeScale = 1.7f),
                transform = TransformModifier(0.9f)) }
            val expected = IconModifierStageRenderer(context.resources, options).render(raw, ModifierInputPipeline.IMAGE_EDIT)
            editor.draft.regenerateCreate(noBuilder, editor.application, options, editor.customIconList, editor.captureRenderWitness())
            pixels(expected, editor.draft.iconToConfirm)
            assertEquals(ModifierInputPipeline.IMAGE_EDIT, editor.draft.captureState(editor.captureRenderWitness()).pipeline)
        }
    }

    @Test fun explicitNewSourceReleasesFrozenStageAndUsesTheNormalBuilder() = runBlocking<Unit> {
        withRecovery(packDraft(EditorRecoveryFixtures.icon(), ModifierInputPipeline.BITMAP_IMAGE_EDIT)) { prepared, editor ->
            editor.selectCreateSource(Source.APPLICATION_NAME)
            assertNull(editor.draft.recoveredCreateStage)
            var calls = 0
            editor.draft.regenerateCreate(noBuilder, editor.application, prepared.bound.requested.withSource { copy(primarySource = Source.APPLICATION_NAME) },
                editor.customIconList, editor.captureRenderWitness(), renderSource = { _, custom ->
                    assertNull(custom); calls++; IconGenerationResult(EditorRecoveryFixtures.icon(android.graphics.Color.GREEN)) })
            assertEquals(1, calls)
            pixels(EditorRecoveryFixtures.icon(android.graphics.Color.GREEN), editor.draft.iconToConfirm)
        }
    }

    private suspend fun uploadDraft(): EditorCompleteCapture {
        val editor = EditorRecoveryFixtures.editor()
        val raw = EditorRecoveryFixtures.icon()
        editor.upload.selectImported("/missing/gallery.png"); editor.upload.asAdaptiveIcon = true; editor.upload.zoomLevel = 0.7f
        editor.activateUpload(raw)
        editor.draft.regenerateUpload(EditorRecoveryFixtures.builder, EditorRecoveryFixtures.options(), editor.captureRenderWitness())
        val bytes = ByteArrayOutputStream().also { raw.toBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val owned = mapOf("font" to EditorRecoveryFixtures.font(), "upload" to EditorDraftResource(EditorDraftResourceKind.UPLOAD, "/missing/gallery.png", bytes))
        return EditorCompleteMainCapture.capture(editor, owned.values.sumOf { it.byteCount.toLong() }).finish(owned)
    }

    @Test fun recoveredUploadHasOwnedPixelsAndAcknowledgedSelectionWithoutOpeningGallery() = runBlocking<Unit> {
        withRecovery(uploadDraft()) { prepared, editor ->
            assertSame(prepared.bound.resources.upload, editor.upload.ownedImage)
            assertEquals(editor.upload.selection(), editor.upload.publishedSelection)
            assertTrue(editor.canConfirmDraft(editor.captureRenderWitness()))
            editor.draft.regenerateUpload(noBuilder, prepared.bound.requested, editor.captureRenderWitness())
            assertTrue(editor.canConfirmDraft(editor.captureRenderWitness()))
            val owned = prepared.bound.capture.resources
            val recaptured = EditorCompleteMainCapture.capture(editor, owned.values.sumOf { it.byteCount.toLong() }).finish(owned)
            assertEquals(prepared.bound.capture.snapshot.controls, recaptured.snapshot.controls)
            assertEquals(prepared.bound.capture.resources["upload"]!!.sha256, recaptured.resources["upload"]!!.sha256)
        }
    }

    @Test fun samePathGalleryReplacementDropsOwnedPixelsAndAcknowledgement() = runBlocking<Unit> {
        withRecovery(uploadDraft()) { _, editor ->
            editor.upload.selectGallery(editor.upload.selectedImagePath)
            assertNull(editor.upload.ownedImage); assertNull(editor.upload.publishedSelection)
            assertFalse(editor.canConfirmDraft(editor.captureRenderWitness()))
        }
    }

    @Test fun tappingCurrentNormalGallerySelectionKeepsApplyAvailable() {
        val state = UploadEditorState().apply { selectedImagePath = "gallery.png"; publishedSelection = selection() }
        val published = state.publishedSelection
        state.selectGallery("gallery.png")
        assertSame(published, state.publishedSelection)
    }

    @Test fun ownedFontSurvivesMissingPathUntilExplicitSameReferenceSelection() = runBlocking<Unit> {
        val editor = EditorRecoveryFixtures.editor().apply { textFontPath = "/missing/font.ttf" }
        withRecovery(EditorRecoveryFixtures.capture(editor)) { prepared, recovered ->
            assertSame(prepared.bound.resources.typeface, recovered.recoveryResources!!.typeface(recovered.textFontPath))
            assertNull(recovered.recoveryResources!!.typeface("different"))
            val expected = LetterGenerator(context, ownedTypeface = prepared.bound.resources.typeface).generateExact("AB", 0xff112233.toInt(), 2f, 256)
            val actual = LetterGenerator(context, "/missing/font.ttf", recovered.recoveryResources!!.typeface(recovered.textFontPath)).generateExact("AB", 0xff112233.toInt(), 2f, 256)
            val a = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
            val b = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
            expected.setBounds(0, 0, 256, 256); expected.draw(Canvas(a))
            actual.setBounds(0, 0, 256, 256); actual.draw(Canvas(b)); assertTrue(a.sameAs(b))
            recovered.selectFont(recovered.textFontPath)
            assertNull(recovered.recoveryResources!!.font(recovered.textFontPath))
            assertNull(recovered.recoveryResources!!.typeface(recovered.textFontPath))
        }
    }

    @Test fun vectorRestoresCurrentAndSeedIndependentlyAndDoesNotRepublish() = runBlocking<Unit> {
        val editor = EditorRecoveryFixtures.editor()
        val path = EditorVectorPath("seed", listOf(PathNode.MoveTo(1f, 2f), PathNode.LineTo(3f, 4f)), false,
            EditorVectorBrush(Color.Red.value.toLong()), 1f, null, 0.5f, 2f, "Round", "Bevel", 7f, 0.2f, 0.8f, -0.5f)
        editor.vectorEditState.apply {
            entries = listOf(PathEntry(path.toDraftVectorPath(), false, 2.7f)); captureSeed()
            entries = listOf(PathEntry(path.copy(name = "edited").toDraftVectorPath(), true, 3.1f)); thickness = 2f
            initialized = true
        }
        editor.activateVector(EditorRecoveryFixtures.icon())
        editor.draft.regenerateVector(EditorRecoveryFixtures.builder, EditorRecoveryFixtures.options(), editor.captureRenderWitness())
        withRecovery(EditorRecoveryFixtures.capture(editor)) { prepared, recovered ->
            assertEquals("edited", recovered.vectorEditState.entries.single().path.name)
            assertEquals("seed", recovered.vectorEditState.seedForDraft()!!.entries.single().path.name)
            assertTrue(recovered.vectorEditState.hasChangesFromSeed)
            assertEquals(recovered.vectorEditState.inputs(), recovered.vectorEditState.publishedInputs)
            recovered.draft.regenerateVector(noBuilder, prepared.bound.requested, recovered.captureRenderWitness())
            assertTrue(recovered.canConfirmDraft(recovered.captureRenderWitness()))
        }
    }

    @Test fun constructionIsCachedAndDisposalRejectsReuseWithoutRecyclingOriginalEditor() = runBlocking<Unit> {
        val original = EditorRecoveryFixtures.editor()
        val prepared = prepare(EditorRecoveryFixtures.capture(original))
        val first = prepared.sessionForAcceptance()
        assertSame(first, prepared.sessionForAcceptance())
        val pixels = (first.draft.iconToConfirm as BitmapIconDrawable).toBitmap()
        prepared.disposeUnattached(); prepared.disposeUnattached()
        assertTrue(pixels.isRecycled)
        assertFalse((original.draft.iconToConfirm as BitmapIconDrawable).toBitmap().isRecycled)
        assertThrows(IllegalStateException::class.java) { prepared.sessionForAcceptance() }
    }

    @Test fun reconstructionCannotOverwriteAnAlreadyPublishedFrame() = runBlocking<Unit> {
        withRecovery(EditorRecoveryFixtures.capture()) { prepared, editor ->
            assertThrows(IllegalStateException::class.java) { editor.restoreDraft(prepared.bound) }
        }
    }

    @Test fun replayRestoresCurrentControlsWithoutReplacingThemWithItsOriginalRecipe() = runBlocking<Unit> {
        val raw = EditorRecoveryFixtures.icon()
        val options = EditorRecoveryFixtures.options()
        val input = requireNotNull(modifierInputForResult(raw, options, ModifierInputOrigin.FRESH_SOURCE))
        val verified = requireNotNull(VerifiedModifierInput.verify(context.resources, raw, input, raw))
        val editor = EditorRecoveryFixtures.editor(EditorRecoveryFixtures.application(raw).changeExport(raw, modifierInput = input))
        editor.restoreModifiers(verified)
        editor.draft.regenerateCreate(noBuilder, editor.application, options, emptyList(), editor.captureRenderWitness())
        editor.adjustments.iconScale = 0.83f
        val changed = options.withModifiers { copy(transform = TransformModifier(0.83f)) }
        editor.draft.regenerateCreate(noBuilder, editor.application, changed, emptyList(), editor.captureRenderWitness())
        withRecovery(EditorRecoveryFixtures.capture(editor)) { prepared, recovered ->
            assertEquals(0.83f, recovered.adjustments.iconScale)
            assertSame(prepared.bound.replay, recovered.modifierReplay!!.verified)
            assertSame(prepared.bound.replay, recovered.draft.replay)
            assertSame(prepared.bound.restoredExisting!!.input, recovered.restoredIconSource)
            assertEquals(prepared.bound.capture.snapshot.controls, recovered.captureControls())
            recovered.draft.regenerateCreate(noBuilder, recovered.application, prepared.bound.requested, emptyList(), recovered.captureRenderWitness())
            assertTrue(recovered.canConfirmDraft(recovered.captureRenderWitness()))
        }
    }

    @Test fun recoveryKeepsMaskAliasesAndNullModifierGroupsInTheCompletedFrame() = runBlocking<Unit> {
        val value = EditorRecoveryFixtures.capture()
        val options = value.snapshot.requested.options.withModifiers { copy(outline = OutlineModifier(OutlineMode.NONE, 3f, ColorFill(0)),
            shadow = null, shape = null, effect = effect.copy(edges = EdgeModifier(2f, 19f, 1f, true))) }
        val generation = EditorGenerationSnapshot(options, "mask-0", listOf(EditorMaskReference(BrushAction.ERASE, "mask-0"),
            EditorMaskReference(BrushAction.RESTORE, "mask-0")))
        val changed = value.copy(artwork = value.artwork + ("mask-0" to value.artwork.values.first()),
            snapshot = value.snapshot.copy(requested = generation, renderedOptions = generation))
        withRecovery(changed) { prepared, recovered ->
            val frame = recovered.draft.captureState(recovered.captureRenderWitness())
            assertNull(frame.requested.modifiers.shadow); assertNull(frame.requested.modifiers.shape)
            assertEquals(19f, frame.requested.modifiers.effect.edges.highThreshold)
            val masks = frame.requested.modifiers.effect.backgroundRemoval.brushOperations
            assertEquals(listOf(BrushAction.ERASE, BrushAction.RESTORE), masks.map { it.action })
            assertSame(masks[0].mask, masks[1].mask)
            assertSame(frame.requested.modifiers.outline!!.eraseMask, masks[0].mask)
            assertSame(prepared.bound.requested, frame.requested)
        }
    }
}
