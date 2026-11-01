package dev.renkinProject.renkin.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import androidx.compose.ui.graphics.toArgb
import dev.renkinProject.renkin.IconPreviewBuilder
import dev.renkinProject.renkin.apk.ProfileSessionToken
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.data.Source
import dev.renkinProject.renkin.drawable.AdaptiveIconPackDrawable
import dev.renkinProject.renkin.drawable.BitmapIconDrawable
import dev.renkinProject.renkin.drawable.MaterialYouPackEditState
import dev.renkinProject.renkin.extension.getBytes
import dev.renkinProject.renkin.drawable.IconPackDrawable
import dev.renkinProject.renkin.drawable.ResourceDrawable
import dev.renkinProject.renkin.icon.creator.*
import dev.renkinProject.renkin.packages.PackageInfoStruct
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
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
class ModifierReplayTest {
    private val resources get() = RuntimeEnvironment.getApplication().resources
    private fun icon(color: Int = Color.RED, scale: Float = 1f) = BitmapIconDrawable(
        resources, Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(color) },
        exportAsAdaptiveIcon = false, previewScale = scale
    )
    private fun options() = GenerationOptions(
        source = IconSourceOptions(primarySource = Source.ICON_PACK),
        modifiers = ModifierStack(EffectModifier(ImageEdit.NONE, ColorizerStyle(firstColor = Color.BLUE))),
        export = IconExportOptions(background = ColorFill(Color.BLACK)), override = true
    )
    private suspend fun verified(settings: GenerationOptions = options(), baked: Boolean = false): VerifiedModifierInput {
        val input = icon()
        val state = IconModifierInput(settings, baked, ModifierInputOrigin.EXISTING_ICON, liveInput = input)
        val base = IconModifierStageRenderer(resources, settings).render(input, state.pipeline)
        return checkNotNull(VerifiedModifierInput.verify(resources, input, state, base))
    }
    private fun app(base: IconPackDrawable, input: IconModifierInput) = PackageInfoStruct(
        "App", "test.app", "test.app.Main", ColorDrawable(0), 0
    ).changeExport(base, modifierInput = input)
    private fun session(verified: VerifiedModifierInput) = EditorSession(
        ProfileSessionToken(1, 1, "provider"), app(icon(Color.GREEN), verified.retained), "pack", "font"
    ).apply { restoreModifiers(verified) }
    private fun ui(session: EditorSession, saved: GenerationOptions): GenerationOptions = saved.copy(
        source = saved.source.copy(primarySource = session.source),
        modifiers = session.adjustments.applyToModifierStack(
            saved.modifiers, session.imageEdit, session.iconColor.toArgb(), null, emptyList()
        ),
        export = saved.export.copy(background = ColorFill(Color.BLACK, session.adjustments.shapeStyle.takeIf {
            session.adjustments.iconShape != IconShape.NONE && !session.adjustments.shapeCrop
        }))
    )

    @Test fun verificationRejectsDifferentPixelsAndPreviewScale() = runBlocking {
        val input = icon()
        val state = IconModifierInput(options(), false, ModifierInputOrigin.UPLOAD, liveInput = input)
        assertNotNull(VerifiedModifierInput.verify(resources, input, state, input))
        assertNull(VerifiedModifierInput.verify(resources, input, state, icon(Color.GREEN)))
        assertNull(VerifiedModifierInput.verify(resources, input, state, icon(scale = 1.5f)))
    }

    @Test fun replayUsesImmutableInputAndPublishesMatchingMetadata() = runBlocking {
        val verified = verified()
        val edited = options().withModifiers { withEffect { copy(imageEdit = ImageEdit.COLORIZE, colorizer = colorizer.copy(flat = true)) } }
        val result = verified.render(edited)
        assertEquals(Color.BLUE, result.icon!!.toBitmap().getPixel(3, 3))
        assertEquals(Color.RED, verified.input.toBitmap().getPixel(3, 3))
        assertSame(verified.input, result.modifierInput!!.liveInput)
        assertEquals(edited, result.renderedOptions)
        assertEquals(verified.retained.pipeline, result.modifierInput.pipeline)
        val recolored = verified.render(edited.withModifiers { withEffect { copy(colorizer = colorizer.copy(firstColor = Color.GREEN)) } })
        assertEquals(Color.GREEN, recolored.icon!!.toBitmap().getPixel(3, 3))
        assertEquals(Color.RED, verified.input.toBitmap().getPixel(3, 3))
    }

    @Test fun unchangedProjectionKeepsNullableGroupsAndUnrepresentedThreshold() = runBlocking {
        val saved = options().withModifiers { withEffect { copy(edges = EdgeModifier(2f, 19f, 3f, true)) } }
        val session = session(verified(saved))
        assertEquals(Source.NONE, session.source)
        assertEquals(saved, session.modifierReplay!!.optionsFor(ui(session, saved)))
    }

    @Test fun geometryEditPreservesInheritedShapeFillAndFrozenSourceBackground() = runBlocking {
        val saved = options().withModifiers { copy(shape = ShapeModifier(IconShape.CIRCLE, false, 1f, null)) }
        val session = session(verified(saved))
        session.adjustments.shapeScale = 0.7f
        val result = session.modifierReplay!!.optionsFor(ui(session, saved))
        assertEquals(0.7f, result.modifiers.shape!!.scale)
        assertNull(result.modifiers.shape.fill)
        assertEquals(saved.export, result.export)
        assertEquals(saved.source, result.source)
    }

    @Test fun untouchedPlateKeepsTheRecipesInheritedFill() = runBlocking {
        val saved = options().withModifiers { copy(shape = ShapeModifier(IconShape.CIRCLE, false, 1f, null)) }
        val session = session(verified(saved))
        val result = session.modifierReplay!!.optionsFor(ui(session, saved))
        assertEquals(saved.modifiers.shape, result.modifiers.shape)
    }

    @Test fun changedPlateWritesItsCompleteFill() = runBlocking {
        val saved = options().withModifiers { copy(shape = ShapeModifier(IconShape.CIRCLE, false, 1f, null)) }
        val session = session(verified(saved))
        val plate = ColorizerStyle(firstColor = Color.WHITE)
        session.adjustments.shapeStyle = plate
        val result = session.modifierReplay!!.optionsFor(ui(session, saved))
        assertEquals(ColorFill(Color.WHITE, plate), result.modifiers.shape!!.fill)
        assertEquals(saved.export, result.export)
    }

    private fun layer(color: Int) = Bitmap.createBitmap(
        AdaptiveIconPackDrawable.LAYER_SIZE, AdaptiveIconPackDrawable.LAYER_SIZE, Bitmap.Config.ARGB_8888
    ).apply { eraseColor(color) }.getBytes(Bitmap.CompressFormat.PNG, 100)

    @Test fun replayedPackIconRestoresAndForwardsEditableColours() = runBlocking {
        val state = MaterialYouPackEditState(5, ColorizerStyle(firstColor = Color.RED), ColorizerStyle(firstColor = Color.BLACK), 1f)
        val pack = MaterialYouPackModifier(Color.RED, Color.BLACK, 5, state.customForeground, state.customBackground)
        val saved = options().withModifiers { copy(materialYouPack = pack) }
        val input = AdaptiveIconPackDrawable(layer(Color.RED), layer(Color.BLACK), materialYouEditState = state)
        val retained = IconModifierInput(saved, false, ModifierInputOrigin.FRESH_SOURCE, liveInput = input,
            pipeline = ModifierInputPipeline.IMAGE_EDIT)
        val base = IconModifierStageRenderer(resources, saved).render(input, ModifierInputPipeline.IMAGE_EDIT)
        val session = session(checkNotNull(VerifiedModifierInput.verify(resources, input, retained, base)))
        val replay = session.modifierReplay!!

        assertEquals(state, replay.packEditState)
        assertEquals(5, session.materialYouPackAdjustments.selectedScheme)
        assertEquals(state.customForeground, session.materialYouPackAdjustments.customForeground)
        assertEquals(pack, replay.optionsFor(ui(session, saved)).modifiers.materialYouPack)
        val edited = pack.copy(foreground = Color.GREEN, customForeground = ColorizerStyle(firstColor = Color.GREEN))
        val result = replay.optionsFor(ui(session, saved).withModifiers { copy(materialYouPack = edited) })
        assertEquals(edited, result.modifiers.materialYouPack)
        val recoloured = replay.verified.render(result).icon!!.toBitmap()
        assertEquals(Color.GREEN, recoloured.getPixel(recoloured.width / 2, recoloured.height / 2))
    }

    @Test fun secondReplayRestoresTheColoursSavedByTheFirstReplay() = runBlocking {
        val original = MaterialYouPackEditState(5, ColorizerStyle(firstColor = Color.RED), ColorizerStyle(firstColor = Color.BLACK), 1f)
        val edited = MaterialYouPackModifier(Color.RED, Color.BLUE, 5, original.customForeground, ColorizerStyle(firstColor = Color.BLUE))
        val saved = options().withModifiers { copy(materialYouPack = edited) }
        val input = AdaptiveIconPackDrawable(layer(Color.RED), layer(Color.BLACK), materialYouEditState = original)
        val retained = IconModifierInput(saved, false, ModifierInputOrigin.FRESH_SOURCE, liveInput = input,
            pipeline = ModifierInputPipeline.IMAGE_EDIT)
        val base = IconModifierStageRenderer(resources, saved).renderRetained(input, ModifierInputPipeline.IMAGE_EDIT)
        val session = session(checkNotNull(VerifiedModifierInput.verify(resources, input, retained, base)))

        assertEquals(edited.editState(), session.modifierReplay!!.packEditState)
        assertEquals(edited.customBackground, session.materialYouPackAdjustments.customBackground)
        assertEquals(edited, session.modifierReplay!!.optionsFor(ui(session, saved)).modifiers.materialYouPack)
    }

    @Test fun packBrowsingKeepsReplayButActualPickEndsIt() = runBlocking {
        val session = session(verified())
        val replay = session.modifierReplay
        session.selectCreateSource(Source.ICON_PACK)
        assertSame(replay, session.modifierReplay)
        assertEquals(Source.NONE, session.source)
        assertEquals(Source.ICON_PACK, session.createSourceToDisplay)
        session.source = Source.ICON_PACK
        session.customIconList = listOf(ResourceDrawable(7, ColorDrawable(Color.RED)))
        session.activateCreate()
        assertNull(session.modifierReplay)
        assertFalse(session.browsingIconPacks)
        assertEquals(7, session.customIconList.single().resourceId)
    }

    @Test fun ordinaryTextToPackSwitchActivatesSavedArtwork() = runBlocking {
        val verified = verified()
        val session = EditorSession(ProfileSessionToken(1, 1, "provider"), app(icon(), verified.retained), "pack", "font")
        session.selectCreateSource(Source.APPLICATION_NAME)
        session.customIconList = listOf(ResourceDrawable(7, ColorDrawable(0)))
        session.selectCreateSource(Source.ICON_PACK)
        assertEquals(Source.ICON_PACK, session.source)
        assertFalse(session.browsingIconPacks)
        assertTrue(session.customIconList.isEmpty())
        assertEquals(IconOrigin.CREATE, session.draft.origin)
    }

    @Test fun sourceReplacementKeepsRawVectorSeedAndBakedProvenance() = runBlocking {
        val verified = verified(baked = true)
        val session = session(verified)
        session.selectCreateSource(Source.APPLICATION_ICON)
        assertNull(session.modifierReplay)
        assertSame(verified.input, session.restoredIconSource)
        session.activateVector(icon())
        session.draft.regenerateVector(RecordingBuilder(), options())
        assertTrue(session.draft.modifierInputToConfirm!!.containsBakedModifierEdits)
        session.vectorEditState.containsBakedModifierEdits = false
        session.activateVector(icon(Color.GREEN))
        session.draft.regenerateVector(RecordingBuilder(), options())
        assertFalse(session.draft.modifierInputToConfirm!!.containsBakedModifierEdits)
    }

    @Test fun uploadLeavesValidCreateSelectionWithoutReplacingUploadDraft() = runBlocking {
        val session = session(verified())
        val uploaded = icon(Color.GREEN)
        session.activateUpload(uploaded)
        assertNull(session.modifierReplay)
        assertEquals(Source.ICON_PACK, session.createSourceToDisplay)
        assertEquals(IconOrigin.UPLOAD, session.draft.origin)
        assertSame(uploaded, session.draft.uploadBase)
    }

    @Test fun returnToSavedArtworkAfterReplacementRendersRawInputAndKeepsProvenance() = runBlocking {
        for (baked in listOf(false, true)) {
            val verified = verified(baked = baked)
            val session = session(verified)
            val builder = RecordingBuilder()
            session.draft.regenerateCreate(builder, session.application, options(), emptyList())
            session.selectCreateSource(Source.APPLICATION_ICON)
            session.selectCreateSource(Source.ICON_PACK)
            session.draft.regenerateCreate(builder, session.application, options(), emptyList())
            assertSame(verified.input, builder.appliedInput)
            assertSame(verified.input, session.draft.modifierInputToConfirm!!.liveInput)
            assertEquals(baked, session.draft.modifierInputToConfirm!!.containsBakedModifierEdits)
        }
    }

    @Test fun failedReplayRetainsCompletedTrioAndBlocksApplyUntilControlsReturn() = runBlocking {
        val verified = verified()
        val session = session(verified)
        val draft = session.draft
        draft.regenerateCreate(RecordingBuilder(), session.application, options(), emptyList())
        val before = Triple(draft.iconToConfirm, draft.modifierRecipeToConfirm, draft.modifierInputToConfirm)
        val edited = options().withModifiers { copy(transform = TransformModifier(scale = 0.8f)) }
        draft.regenerateCreate(RecordingBuilder(), session.application, edited, emptyList()) { _, _ -> IconGenerationResult(null) }
        assertTrue(draft.replayRenderFailed)
        assertEquals(before, Triple(draft.iconToConfirm, draft.modifierRecipeToConfirm, draft.modifierInputToConfirm))
        draft.regenerateCreate(RecordingBuilder(), session.application, options(), emptyList())
        assertFalse(draft.replayRenderFailed)
    }

    @Test fun pendingReplayCannotPublishAfterSourceReplacement() = runBlocking {
        val verified = verified()
        val session = session(verified)
        val draft = session.draft
        draft.regenerateCreate(RecordingBuilder(), session.application, options(), emptyList())
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val edited = options().withModifiers { copy(transform = TransformModifier(scale = 0.8f)) }
        val pending = launch {
            draft.regenerateCreate(RecordingBuilder(), session.application, edited, emptyList()) { _, settings ->
                started.complete(Unit)
                release.await()
                verified.render(settings)
            }
        }
        started.await()
        val uploaded = icon(Color.GREEN)
        session.activateUpload(uploaded)
        draft.regenerateUpload(RecordingBuilder(), options())
        val completed = draft.modifierInputToConfirm
        release.complete(Unit)
        pending.join()
        assertEquals(IconOrigin.UPLOAD, draft.origin)
        assertSame(uploaded, draft.iconToConfirm)
        assertSame(completed, draft.modifierInputToConfirm)
    }

    private class RecordingBuilder : IconPreviewBuilder {
        var appliedInput: IconPackDrawable? = null
        override suspend fun previewIcon(app: PackageInfoStruct, options: GenerationOptions, customIcon: ResourceDrawable?) = null
        override suspend fun applyModifier(icon: IconPackDrawable, options: GenerationOptions): IconPackDrawable {
            appliedInput = icon
            return icon
        }
    }
}
