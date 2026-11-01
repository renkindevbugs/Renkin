package dev.renkinProject.renkin.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.PathNode
import dev.renkinProject.renkin.data.editor.*
import dev.renkinProject.renkin.drawable.BitmapIconDrawable
import dev.renkinProject.renkin.icon.creator.*
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
class EditorRecoveryBindingTest {
    private val resources get() = EditorRecoveryResources(RuntimeEnvironment.getApplication())
    private fun rejected(block: () -> Unit) = assertThrows(IllegalArgumentException::class.java, block)
    private fun controls(value: EditorCompleteCapture, change: (EditorControlsSnapshot) -> EditorControlsSnapshot) =
        value.copy(snapshot = value.snapshot.copy(controls = change(value.snapshot.controls)))

    @Test fun defaultDraftBindsExactPixelsWithoutRetainingTheOldLiveObjects() = runBlocking<Unit> {
        val editor = EditorRecoveryFixtures.editor(); val captured = EditorRecoveryFixtures.capture(editor)
        val decoded = EditorCompleteCodec.decode(EditorCompleteCodec.encode(captured))
        val bound = bindEditorRecovery(decoded, resources)
        val bitmap = (bound.artwork.getValue(decoded.snapshot.rendered!!) as BitmapIconDrawable).toBitmap()
        assertNotSame(editor.draft.iconToConfirm, bound.artwork[decoded.snapshot.rendered])
        assertEquals(android.graphics.Color.RED, bitmap.getPixel(0, 0))
        assertEquals(captured.snapshot.requested.options, bound.requested)
        assertNotNull(bound.resources.typeface); assertNull(bound.resources.upload)
        bound.disposeUnattached(); assertTrue(bitmap.isRecycled)
        assertFalse((editor.draft.iconToConfirm as BitmapIconDrawable).toBitmap().isRecycled)
        bound.disposeUnattached()
    }

    @Test fun disabledGroupsCustomSchemesAndSmallZoomedBrushWidthsRemainValid() = runBlocking<Unit> {
        val value = EditorRecoveryFixtures.capture()
        val gradient = ColorizerStyle(mode = ColorizerMode.GRADIENT, firstColor = 0, gradientStops = List(9) { it }, gradientPositions = emptyList(), gradientAngle = 360f)
        val changed = controls(value) { it.copy(create = it.create.copy(materialYouScheme = 999,
            materialYouForegroundStyle = gradient), materialYouPack = it.materialYouPack.copy(selectedScheme = -1),
            adjustments = it.adjustments.copy(outlineMode = OutlineMode.NONE, outlineWidth = 16f,
                shadowEnabled = false, shadowBlur = 32f, shadowDistance = 32f, shadowOpacity = 0f,
                eraseStrokes = listOf(EditorBrushStroke(0.001f, listOf(EditorBrushPoint(0f, 1f)), BrushAction.ERASE)))) }
        EditorRecoveryValidation.validate(changed)
        EditorRecoveryValidation.validate(controls(changed) { it.copy(materialYouPack = it.materialYouPack.copy(selectedScheme = 999)) })
        assertEquals(999, changed.snapshot.controls.create.materialYouScheme)
    }

    @Test fun outOfDomainControlsRejectBeforeNativeResourcesAreOpened() = runBlocking<Unit> {
        val value = EditorRecoveryFixtures.capture()
        val changes: List<(EditorControlsSnapshot) -> EditorControlsSnapshot> = listOf(
            { it.copy(upload = it.upload.copy(zoomLevel = 2.01f)) },
            { it.copy(create = it.create.copy(iconColorBits = Color.Unspecified.value.toLong())) },
            { it.copy(create = it.create.copy(backgroundColorBits = 63L)) },
            { it.copy(materialYouPack = it.materialYouPack.copy(selectedScheme = -2)) },
            { it.copy(materialYouPack = it.materialYouPack.copy(strokeScale = 2.1f)) },
            { it.copy(adjustments = it.adjustments.copy(edgeThreshold = 0.49f)) },
            { it.copy(adjustments = it.adjustments.copy(edgeSmoothing = 4.01f)) },
            { it.copy(adjustments = it.adjustments.copy(iconScale = 1.51f)) },
            { it.copy(adjustments = it.adjustments.copy(offsetX = -0.51f)) },
            { it.copy(adjustments = it.adjustments.copy(backgroundTolerance = 0.51f)) },
            { it.copy(adjustments = it.adjustments.copy(shapeScale = 0.49f)) },
            { it.copy(adjustments = it.adjustments.copy(outlineWidth = 16.01f)) },
            { it.copy(adjustments = it.adjustments.copy(shadowOpacity = 1.01f)) },
            { it.copy(adjustments = it.adjustments.copy(shadowAngle = -1f)) },
            { it.copy(adjustments = it.adjustments.copy(eraseStrokes = listOf(EditorBrushStroke(0f, emptyList(), BrushAction.ERASE)))) },
            { it.copy(adjustments = it.adjustments.copy(backgroundBrushStrokes = listOf(EditorBrushStroke(0.1f, listOf(EditorBrushPoint(1.01f, 0f)), BrushAction.RESTORE)))) }
        )
        changes.forEach { change -> rejected { EditorRecoveryValidation.validate(controls(value, change)) } }
    }

    @Test fun gradientsRejectExcessColorsMismatchedUnorderedAndOutOfRangePositions() = runBlocking<Unit> {
        val value = EditorRecoveryFixtures.capture()
        val base = ColorizerStyle(mode = ColorizerMode.GRADIENT, firstColor = 0, gradientStops = listOf(1, 2))
        listOf(base.copy(gradientStops = emptyList()), base.copy(gradientStops = List(10) { it }),
            base.copy(gradientPositions = listOf(0f)), base.copy(gradientPositions = listOf(0f, 1f, 0.5f)),
            base.copy(gradientPositions = listOf(-0.1f, 0.5f, 1f)), base.copy(gradientAngle = 361f)).forEach { invalid ->
            rejected { EditorRecoveryValidation.validate(controls(value) { it.copy(create = it.create.copy(materialYouForegroundStyle = invalid)) }) }
        }
        EditorRecoveryValidation.validate(controls(value) { it.copy(create = it.create.copy(
            materialYouForegroundStyle = base.copy(gradientPositions = listOf(0f, 0.5f, 1f)))) })
    }

    @Test fun requestedAndRenderedOptionsKeepExplicitHighThresholdAndNullableGroups() = runBlocking<Unit> {
        val value = EditorRecoveryFixtures.capture(); val original = value.snapshot.requested.options
        val options = original.copy(modifiers = original.modifiers.copy(shape = null, outline = null, shadow = null,
            effect = original.modifiers.effect.copy(edges = EdgeModifier(2f, 19f, 1f, true))))
        val changed = value.copy(snapshot = value.snapshot.copy(requested = value.snapshot.requested.copy(options = options),
            renderedOptions = EditorGenerationSnapshot(options, null, emptyList())))
        val bound = bindEditorRecovery(changed, resources)
        try { assertEquals(options, bound.requested); assertEquals(options, bound.renderedOptions) } finally { bound.disposeUnattached() }
        rejected { EditorRecoveryValidation.validate(changed.copy(snapshot = changed.snapshot.copy(requested =
            changed.snapshot.requested.copy(options = options.copy(modifiers = options.modifiers.copy(effect =
                options.modifiers.effect.copy(edges = EdgeModifier(2f, 1f, 1f, false)))))))) }
    }

    @Test fun orderedMaskOperationsShareTheSameReboundBitmapReference() = runBlocking<Unit> {
        val value = EditorRecoveryFixtures.capture()
        val mask = value.artwork.values.first()
        val changed = value.copy(artwork = value.artwork + ("mask-0" to mask), snapshot = value.snapshot.copy(requested =
            value.snapshot.requested.copy(options = value.snapshot.requested.options.copy(modifiers =
                value.snapshot.requested.options.modifiers.copy(outline = OutlineModifier(OutlineMode.NONE, 3f, ColorFill(0)))),
                eraseMask = "mask-0", backgroundMasks = listOf(EditorMaskReference(BrushAction.ERASE, "mask-0"),
                    EditorMaskReference(BrushAction.RESTORE, "mask-0")))))
        val bound = bindEditorRecovery(changed, resources)
        try {
            val operations = bound.requested.modifiers.effect.backgroundRemoval.brushOperations
            assertEquals(listOf(BrushAction.ERASE, BrushAction.RESTORE), operations.map { it.action })
            assertSame(operations.first().mask, operations.last().mask)
            assertSame(bound.requested.modifiers.outline!!.eraseMask, operations.first().mask)
        } finally { bound.disposeUnattached() }
        rejected { EditorRecoveryValidation.validate(changed.copy(artwork = changed.artwork + ("mask-0" to
            (mask as EditorBitmapArtwork).copy(adaptive = true)))) }
    }

    @Test fun createRequiresInitializationButUploadDoesNotUseTheCreateGuard() = runBlocking<Unit> {
        val value = EditorRecoveryFixtures.capture()
        rejected { EditorRecoveryValidation.validate(value.copy(snapshot = value.snapshot.copy(initialized = false))) }
        EditorRecoveryValidation.validate(value.copy(snapshot = value.snapshot.copy(origin = EditorDraftOrigin.UPLOAD, initialized = false, rawBaked = false)))
    }

    @Test fun vectorSeedAndCurrentInputsAreReboundIndependently() = runBlocking<Unit> {
        val editor = EditorRecoveryFixtures.editor()
        val path = EditorVectorPath("seed", listOf(PathNode.MoveTo(1f, 2f), PathNode.LineTo(3f, 4f)), false,
            EditorVectorBrush(Color.Red.value.toLong()), 1f, null, 0.5f, 2f, "Round", "Bevel", 7f, 0.2f, 0.8f, -0.5f)
        editor.vectorEditState.entries = listOf(PathEntry(path.toDraftVectorPath(), false, 2.7f))
        editor.vectorEditState.viewportOverride = 17f to 19f
        editor.vectorEditState.captureSeed(); editor.vectorEditState.thickness = 2f
        editor.vectorEditState.entries = listOf(PathEntry(path.copy(name = "edited", trimStart = 0.3f).toDraftVectorPath(), true, 3.1f))
        editor.vectorEditState.publishedInputs = editor.vectorEditState.inputs()
        val value = EditorRecoveryFixtures.capture(editor)
        val bound = bindEditorRecovery(value, resources)
        try {
            assertEquals(2f, bound.vector.thickness); assertEquals(1f, bound.vectorSeed!!.thickness)
            assertEquals("edited", bound.vector.entries.single().path.name); assertEquals("seed", bound.vectorSeed.entries.single().path.name)
            assertEquals(0.3f, bound.vector.entries.single().path.trimPathStart)
            assertEquals(0.2f, bound.vectorSeed.entries.single().path.trimPathStart)
            assertEquals(3.1f, bound.vector.entries.single().baseStroke); assertEquals(2.7f, bound.vectorSeed.entries.single().baseStroke)
            assertEquals(17f to 19f, bound.vector.viewport)
        } finally { bound.disposeUnattached() }
        val bad = value.snapshot.vector.current.copy(thickness = 4.1f)
        rejected { EditorRecoveryValidation.validate(value.copy(snapshot = value.snapshot.copy(vector = value.snapshot.vector.copy(current = bad),
            controls = value.snapshot.controls.copy(vector = value.snapshot.controls.vector.copy(thickness = 4.1f))))) }
    }

    @Test fun replayReverifiesTheCapturedOriginalAndKeepsRetainedInputAliases() = runBlocking<Unit> {
        val raw = EditorRecoveryFixtures.icon()
        val retained = IconModifierInput(EditorRecoveryFixtures.options(), true, ModifierInputOrigin.EXISTING_ICON, liveInput = raw)
        val verified = requireNotNull(VerifiedModifierInput.verify(RuntimeEnvironment.getApplication().resources, raw, retained, raw))
        val editor = EditorRecoveryFixtures.editor(EditorRecoveryFixtures.application(raw).changeExport(raw, modifierInput = retained))
        editor.restoreModifiers(verified)
        val value = EditorRecoveryFixtures.capture(editor)
        val bound = bindEditorRecovery(value, resources)
        try {
            assertNotNull(bound.replay); assertSame(bound.restoredExisting, bound.replay)
            assertSame(bound.originalInput, bound.restoredExisting!!.retained)
        } finally { bound.disposeUnattached() }
        val different = captureEditorArtwork(EditorRecoveryFixtures.icon(android.graphics.Color.GREEN))
        rejected { runBlocking { bindEditorRecovery(value.copy(artwork = value.artwork + ("artwork-99" to different),
            snapshot = value.snapshot.copy(original = value.snapshot.original.copy(base = "artwork-99"))), resources) } }
    }

    @Test fun ownedResourcesIgnoreSelectionPathsAndRemoveTemporaryFonts() = runBlocking<Unit> {
        val context = RuntimeEnvironment.getApplication()
        val png = ByteArrayOutputStream().also { EditorRecoveryFixtures.icon().toBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val resource = EditorDraftResource(EditorDraftResourceKind.UPLOAD, "../../untrusted.png", png)
        val bound = resources.bind(mapOf("font" to EditorRecoveryFixtures.font("/missing/font.ttf"), "upload" to resource), 1024)
        assertEquals(android.graphics.Color.RED, bound.upload!!.getPixel(0, 0)); bound.upload.recycle()
        assertTrue(context.cacheDir.listFiles()!!.none { it.name.startsWith("editor-recovery-font-") })
    }

    @Test fun missingShortAndWrongKindFontsRejectAndCleanTheirTemporaryFiles() {
        val context = RuntimeEnvironment.getApplication()
        for (owned in listOf(emptyMap(), mapOf("font" to EditorDraftResource(EditorDraftResourceKind.FONT, "", byteArrayOf(1))),
            mapOf("font" to EditorDraftResource(EditorDraftResourceKind.UPLOAD, "", ByteArray(12))))) {
            rejected { runBlocking { resources.bind(owned, 1024) } }
        }
        assertTrue(context.cacheDir.listFiles()!!.none { it.name.startsWith("editor-recovery-font-") })
    }

    @Test fun uploadRejectsBadSignaturesTruncationAndExpandedPixelBudget() {
        val png = ByteArrayOutputStream().also { EditorRecoveryFixtures.icon().toBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        for ((bytes, budget) in listOf(byteArrayOf(1, 2) to 1024L, png.copyOf(8) to 1024L, png to 255L)) {
            rejected { runBlocking { resources.bind(mapOf("font" to EditorRecoveryFixtures.font(),
                "upload" to EditorDraftResource(EditorDraftResourceKind.UPLOAD, "gone.png", bytes)), budget) } }
        }
    }
}
