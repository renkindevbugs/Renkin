package dev.renkinProject.renkin.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.data.Source
import dev.renkinProject.renkin.data.TextType
import dev.renkinProject.renkin.data.editor.EditorCompleteCodec
import dev.renkinProject.renkin.data.editor.EditorRecoveryResources
import dev.renkinProject.renkin.drawable.ResourceDrawable
import dev.renkinProject.renkin.icon.creator.BackgroundBrushOperation
import dev.renkinProject.renkin.icon.creator.BrushAction
import dev.renkinProject.renkin.icon.creator.ColorFill
import dev.renkinProject.renkin.icon.creator.ColorizerStyle
import dev.renkinProject.renkin.icon.creator.EdgeModifier
import dev.renkinProject.renkin.icon.creator.GenerationOptions
import dev.renkinProject.renkin.icon.creator.IconShape
import dev.renkinProject.renkin.icon.creator.MaterialYouPackModifier
import dev.renkinProject.renkin.icon.creator.ModifierInputOrigin
import dev.renkinProject.renkin.icon.creator.OutlineMode
import dev.renkinProject.renkin.icon.creator.OutlineModifier
import dev.renkinProject.renkin.icon.creator.ShadowModifier
import dev.renkinProject.renkin.icon.creator.ShapeModifier
import dev.renkinProject.renkin.icon.creator.TransformModifier
import dev.renkinProject.renkin.icon.creator.VerifiedModifierInput
import dev.renkinProject.renkin.icon.creator.modifierInputForResult
import dev.renkinProject.renkin.icon.creator.withModifiers
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
class EditorRecoveredRenderingTest {
    private val editor = EditorRecoveryFixtures.editor()
    private val witness get() = editor.captureRenderWitness()
    private val mask = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
    private val saved = EditorRecoveryFixtures.options().let {
        it.copy(source = it.source.copy(secondarySource = Source.APPLICATION_NAME, textCustom = "saved"),
            export = it.export.copy(themed = true, background = ColorFill(Color.YELLOW),
                foregroundStyle = ColorizerStyle(firstColor = Color.MAGENTA)), override = false,
            modifiers = it.modifiers.copy(
                effect = it.modifiers.effect.copy(edges = EdgeModifier(1f, 19f, 1.7f, true),
                    backgroundRemoval = it.modifiers.effect.backgroundRemoval.copy(
                        targets = listOf(Color.RED), tolerance = 0.23f,
                        brushOperations = listOf(BackgroundBrushOperation(BrushAction.RESTORE, mask)))),
                transform = TransformModifier(0.83f, 0.12f, -0.14f),
                shape = ShapeModifier(IconShape.CIRCLE, false, 0.7f, ColorFill(Color.GREEN)),
                outline = OutlineModifier(OutlineMode.ADD, 9f, ColorFill(Color.CYAN), mask),
                shadow = ShadowModifier(4f, 8f, 127f, true, ColorizerStyle(firstColor = Color.RED), 0.42f),
                materialYouPack = MaterialYouPackModifier(strokeScale = 1.4f)))
    }
    private val ui = EditorRecoveryFixtures.options().withModifiers {
        copy(shape = ShapeModifier(IconShape.SQUIRCLE, true, 1f),
            outline = OutlineModifier(OutlineMode.NONE, 6f, ColorFill(Color.BLACK)),
            shadow = ShadowModifier(2f, 3f, 90f, false, ColorizerStyle(firstColor = Color.BLACK), 0.7f))
    }
    private fun projection(options: GenerationOptions = saved, initial: EditorRenderWitness = witness) =
        EditorRecoveredRendering(options, initial, editor.adjustments.eraseStrokes.toList(),
            editor.adjustments.backgroundBrushStrokes.toList())

    @Test fun unchangedControlsKeepExactCapturedRequestForEveryOriginDespiteDifferentUiDefaults() {
        for (origin in IconOrigin.entries) {
            val initial = witness.copy(origin = origin)
            assertEquals(saved, projection(initial = initial).optionsFor(ui, initial))
        }
    }

    @Test fun tabAndBookkeepingChangesDoNotNormalizeGenerationValues() {
        val initial = witness
        val changed = initial.copy(controls = initial.controls.copy(selectedTab = 3,
            onlineImageUrl = "new attribution", calendar = initial.controls.calendar.copy(enabled = true)))
        assertEquals(saved, projection(initial = initial).optionsFor(ui, changed))
    }

    @Test fun scaleEditChangesOnlyScaleAndReversalRecoversTheExactRequest() {
        val p = projection()
        val initial = witness
        editor.adjustments.iconScale = 0.65f
        val proposed = ui.withModifiers { copy(transform = TransformModifier(0.65f)) }
        assertEquals(saved.withModifiers { copy(transform = transform.copy(scale = 0.65f)) }, p.optionsFor(proposed, witness))
        editor.adjustments.iconScale = initial.controls.adjustments.iconScale
        assertEquals(saved, p.optionsFor(proposed, witness))
    }

    @Test fun smoothingDoesNotReplaceIndependentHighThresholdOrContrast() {
        val p = projection()
        editor.adjustments.edgeSmoothing = 3f
        val proposed = ui.withModifiers { copy(effect = effect.copy(edges = EdgeModifier(2f, 6f, 3f, false))) }
        assertEquals(saved.withModifiers { copy(effect = effect.copy(edges = effect.edges.copy(smoothing = 3f))) },
            p.optionsFor(proposed, witness))
    }

    @Test fun thresholdEditAdoptsDerivedThresholdsWithoutChangingOtherEdgeValues() {
        val p = projection()
        editor.adjustments.edgeThreshold = 2f
        val proposed = ui.withModifiers { copy(effect = effect.copy(edges = EdgeModifier(2f, 6f, 4f, false))) }
        assertEquals(saved.withModifiers { copy(effect = effect.copy(edges = effect.edges.copy(lowThreshold = 2f, highThreshold = 6f))) },
            p.optionsFor(proposed, witness))
    }

    @Test fun unrelatedEditsKeepNullGroupsAndRelatedEditsIntroduceOnlyTheSelectedGroup() {
        val absent = saved.withModifiers { copy(shape = null, outline = null, shadow = null) }
        val p = projection(absent)
        editor.adjustments.iconOffsetX = 0.2f
        var proposed = ui.withModifiers { copy(transform = TransformModifier(offsetX = 0.2f)) }
        assertEquals(absent.withModifiers { copy(transform = transform.copy(offsetX = 0.2f)) }, p.optionsFor(proposed, witness))
        editor.adjustments.outlineWidth = 12f
        proposed = proposed.withModifiers { copy(outline = outline!!.copy(width = 12f)) }
        val result = p.optionsFor(proposed, witness)
        assertEquals(proposed.modifiers.outline, result.modifiers.outline)
        assertNull(result.modifiers.shape); assertNull(result.modifiers.shadow)
    }

    @Test fun shapeScaleKeepsExplicitFillAndPlateEditUpdatesIt() {
        val p = projection()
        editor.adjustments.shapeScale = 0.9f
        val proposed = ui.withModifiers { copy(shape = shape!!.copy(scale = 0.9f)) }
        val scaled = p.optionsFor(proposed, witness)
        assertEquals(saved.modifiers.shape!!.copy(scale = 0.9f), scaled.modifiers.shape)
        editor.adjustments.shapeStyle = ColorizerStyle(firstColor = Color.MAGENTA)
        val plate = proposed.copy(export = proposed.export.copy(background = ColorFill(Color.BLUE, editor.adjustments.shapeStyle)))
        val result = p.optionsFor(plate, witness)
        assertEquals(ColorFill(Color.MAGENTA, editor.adjustments.shapeStyle), result.modifiers.shape!!.fill)
        assertEquals(plate.export.background, result.export.background)
    }

    @Test fun outlineWidthDoesNotReplaceItsFillOrNativeMask() {
        val p = projection()
        editor.adjustments.outlineWidth = 13f
        val proposed = ui.withModifiers { copy(outline = outline!!.copy(width = 13f)) }
        assertEquals(saved.withModifiers { copy(outline = outline!!.copy(width = 13f)) }, p.optionsFor(proposed, witness))
        assertSame(mask, p.optionsFor(proposed, witness).modifiers.outline!!.eraseMask)
    }

    @Test fun shadowOpacityDoesNotNormalizeItsGeometryOrStyle() {
        val p = projection()
        editor.adjustments.shadowOpacity = 0.6f
        val proposed = ui.withModifiers { copy(shadow = shadow!!.copy(opacity = 0.6f)) }
        assertEquals(saved.withModifiers { copy(shadow = shadow!!.copy(opacity = 0.6f)) }, p.optionsFor(proposed, witness))
    }

    @Test fun backgroundToleranceDoesNotReplaceTargetsOrSharedNativeMask() {
        val p = projection()
        editor.adjustments.bgRemovalTolerance = 0.31f
        val proposed = ui.withModifiers { copy(effect = effect.copy(backgroundRemoval = effect.backgroundRemoval.copy(tolerance = 0.31f))) }
        assertEquals(saved.withModifiers { copy(effect = effect.copy(backgroundRemoval = effect.backgroundRemoval.copy(tolerance = 0.31f))) },
            p.optionsFor(proposed, witness))
    }

    @Test fun textEditKeepsSecondarySourceAndUnrelatedPresentation() {
        val p = projection()
        editor.customText = "new text"
        val proposed = ui.copy(source = ui.source.copy(textCustom = "new text"))
        assertEquals(saved.copy(source = saved.source.copy(textCustom = "new text")), p.optionsFor(proposed, witness))
    }

    @Test fun sourceReplacementUsesCurrentPresentationButKeepsSharedModifiersAndMasks() {
        val p = projection()
        editor.selectCreateSource(Source.APPLICATION_NAME)
        val proposed = ui.copy(source = ui.source.copy(primarySource = Source.APPLICATION_NAME))
        val result = p.optionsFor(proposed, witness)
        assertEquals(proposed.source, result.source); assertEquals(proposed.export, result.export)
        assertEquals(proposed.override, result.override)
        assertEquals(saved.modifiers.copy(effect = saved.modifiers.effect.copy(colorizer = proposed.modifiers.effect.colorizer),
            materialYouPack = proposed.modifiers.materialYouPack), result.modifiers)
        assertSame(mask, result.modifiers.outline!!.eraseMask)
    }

    @Test fun changedCreateSelectionCountsAsReplacementEvenWhenSourceTypeIsUnchanged() {
        val initial = witness
        val changed = initial.copy(selections = listOf(ResourceDrawable(27, EditorRecoveryFixtures.icon())))
        assertEquals(ui.source, projection(initial = initial).optionsFor(ui, changed).source)
    }

    @Test fun uploadZoomAndVectorGeometryKeepUnrelatedCapturedValues() {
        for (origin in listOf(IconOrigin.UPLOAD, IconOrigin.VECTOR)) {
            val initial = witness.copy(origin = origin)
            val current = initial.copy(upload = initial.upload.copy(zoom = 0.6f),
                vector = initial.vector.copy(thickness = 2f))
            assertEquals(saved, projection(initial = initial).optionsFor(ui, current))
        }
    }

    @Test fun uploadPathReplacementAdoptsCurrentSourcePresentation() {
        val initial = witness.copy(origin = IconOrigin.UPLOAD)
        val current = initial.copy(upload = initial.upload.copy(path = "new.png"))
        assertEquals(ui.export, projection(initial = initial).optionsFor(ui, current).export)
    }

    @Test fun unchangedNativeMasksAreLazyAndPreserveIdentityIncludingAliases() {
        val p = projection()
        repeat(2) {
            assertSame(mask, p.outlineMask(emptyList(), OutlineMode.ADD) { error("Unexpected rasterization") })
            val operations = p.backgroundMasks(emptyList(), editor.imageEdit) { error("Unexpected rasterization") }
            assertSame(mask, operations.single().mask)
        }
    }

    @Test fun capturedNullAndEmptyMasksDoNotFallBackToRasterization() {
        val absent = saved.withModifiers { copy(outline = outline!!.copy(eraseMask = null),
            effect = effect.copy(backgroundRemoval = effect.backgroundRemoval.copy(brushOperations = emptyList()))) }
        val p = projection(absent)
        assertNull(p.outlineMask(emptyList(), editor.adjustments.outlineMode) { error("Unexpected rasterization") })
        assertTrue(p.backgroundMasks(emptyList(), editor.imageEdit) { error("Unexpected rasterization") }.isEmpty())
    }

    @Test fun changedBrushGeometryBuildsOnlyItsMaskAndReversalRestoresNativeValues() {
        val p = projection()
        val strokes = listOf(BrushStroke(0.1f, listOf(Offset(0.3f, 0.4f))))
        val rebuilt = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        assertSame(rebuilt, p.outlineMask(strokes, editor.adjustments.outlineMode) { rebuilt })
        assertSame(mask, p.backgroundMasks(emptyList(), editor.imageEdit) { error("Unrelated mask changed") }.single().mask)
        val operations = listOf(BackgroundBrushOperation(BrushAction.ERASE, rebuilt))
        assertSame(operations, p.backgroundMasks(strokes, editor.imageEdit) { operations })
        assertSame(mask, p.outlineMask(emptyList(), editor.adjustments.outlineMode) { error("Reversal rasterized") })
        assertSame(mask, p.backgroundMasks(emptyList(), editor.imageEdit) { error("Reversal rasterized") }.single().mask)
    }

    @Test fun ordinarySessionUsesUiOptionsAndUnavailableWitnessDoesNotBlockRendering() {
        assertSame(ui, editor.generationOptionsFor(ui, witness))
        assertSame(ui, editor.generationOptionsFor(ui, null))
    }

    @Test fun decodedRecoveryKeepsRequestAndCompletedFrameAcrossUiProjectionAndAllTabMounts() = runBlocking<Unit> {
        for (tab in 0..3) {
            val original = EditorRecoveryFixtures.editor().apply { selectedTab = tab; imageEdit = ImageEdit.COLORIZE }
            original.draft.regenerateCreate(EditorRecoveryFixtures.builder, original.application, saved, emptyList(), original.captureRenderWitness())
            val decoded = EditorCompleteCodec.decode(EditorCompleteCodec.encode(EditorRecoveryFixtures.capture(original)))
            val bound = bindEditorRecovery(decoded, EditorRecoveryResources(RuntimeEnvironment.getApplication()))
            try {
                val recovered = EditorRecoveryFixtures.editor().apply { restoreDraft(bound) }
                val frame = recovered.draft.iconToConfirm
                recovered.enterSelectedTab(); recovered.enterSelectedTab()
                assertEquals(ImageEdit.COLORIZE, recovered.imageEdit)
                val proposed = recovered.generationOptionsFor(ui, recovered.captureRenderWitness())
                assertEquals(bound.requested, proposed)
                recovered.draft.regenerateCreate(EditorRecoveryFixtures.builder, recovered.application, proposed, emptyList(), recovered.captureRenderWitness())
                assertSame(frame, recovered.draft.iconToConfirm)
                assertTrue(recovered.canConfirmDraft(recovered.captureRenderWitness()))
                recovered.selectedTab = if (tab == 0) 1 else 0
                recovered.enterSelectedTab()
                assertEquals(ImageEdit.NONE, recovered.imageEdit)
            } finally { bound.disposeUnattached() }
        }
    }

    @Test fun verifiedReplayRecoveryPreservesCurrentEditsAndExplicitRestoreReplacesProjection() = runBlocking<Unit> {
        val raw = EditorRecoveryFixtures.icon()
        val options = EditorRecoveryFixtures.options()
        val input = requireNotNull(modifierInputForResult(raw, options, ModifierInputOrigin.FRESH_SOURCE))
        val verified = requireNotNull(VerifiedModifierInput.verify(RuntimeEnvironment.getApplication().resources, raw, input, raw))
        val original = EditorRecoveryFixtures.editor(EditorRecoveryFixtures.application(raw).changeExport(raw, modifierInput = input))
        original.restoreModifiers(verified)
        original.adjustments.iconScale = 0.83f
        val changed = options.withModifiers { copy(transform = TransformModifier(0.83f)) }
        original.draft.regenerateCreate(EditorRecoveryFixtures.builder, original.application, changed, emptyList(), original.captureRenderWitness())
        val decoded = EditorCompleteCodec.decode(EditorCompleteCodec.encode(EditorRecoveryFixtures.capture(original)))
        val bound = bindEditorRecovery(decoded, EditorRecoveryResources(RuntimeEnvironment.getApplication()))
        try {
            val recovered = EditorRecoveryFixtures.editor().apply { restoreDraft(bound) }
            assertEquals(bound.requested, recovered.generationOptionsFor(ui, recovered.captureRenderWitness()))
            recovered.selectedTab = 0; recovered.enterSelectedTab()
            assertEquals(bound.requested.modifiers.effect.imageEdit, recovered.imageEdit)
            recovered.restoreModifiers(verified)
            assertNull(recovered.recoveredRendering)
            assertEquals(recovered.modifierReplay!!.optionsFor(ui), recovered.generationOptionsFor(ui, recovered.captureRenderWitness()))
        } finally { bound.disposeUnattached() }
    }
}
