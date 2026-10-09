package dev.renkinProject.renkin.icon.creator

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.data.Source
import dev.renkinProject.renkin.data.editor.EditorCompleteCodec
import dev.renkinProject.renkin.data.editor.EditorRecoveryResources
import dev.renkinProject.renkin.drawable.BitmapIconDrawable
import dev.renkinProject.renkin.drawable.IconPackDrawable
import dev.renkinProject.renkin.drawable.ResourceDrawable
import dev.renkinProject.renkin.ui.EditorRecoveryFixtures
import dev.renkinProject.renkin.ui.bindEditorRecovery
import dev.renkinProject.renkin.ui.captureRenderWitness
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IconRenderingScreenshotTest {
    private fun input() = BitmapIconDrawable(Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).apply {
        density = Bitmap.DENSITY_NONE
        Canvas(this).drawCircle(48f, 48f, 28f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
    }, false)

    private fun options(): GenerationOptions {
        val gradient = ColorizerStyle(ColorizerMode.GRADIENT, GradientType.LINEAR, 0xffff6b35.toInt(),
            listOf(0xff7047eb.toInt()), listOf(0f, 1f), 120f)
        return GenerationOptions(IconSourceOptions(Source.ICON_PACK), ModifierStack(
            effect = EffectModifier(ImageEdit.COLORIZE, gradient),
            shadow = ShadowModifier(4f, 5f, 135f, false, ColorizerStyle(firstColor = Color.BLACK), 0.6f)
        ), IconExportOptions(), true)
    }

    @Test fun shadowAndGradientMatchReference() = verify(input(), options())

    @Test fun recoveredRequestMatchesReferenceDespiteDifferentCurrentUiColors() = runBlocking<Unit> {
        val input = input()
        val options = options()
        val original = EditorRecoveryFixtures.editor()
        original.draft.regenerateCreate(EditorRecoveryFixtures.builder, original.application,
            EditorRecoveryFixtures.options(), emptyList(), original.captureRenderWitness())
        original.customIconList = listOf(ResourceDrawable(27, input))
        original.activateCreate()
        original.draft.regenerateCreate(EditorRecoveryFixtures.builder, original.application, options, original.customIconList,
            original.captureRenderWitness(), renderSource = { _, _ ->
                IconGenerationResult(IconModifierStageRenderer(RuntimeEnvironment.getApplication().resources, options)
                    .render(input, ModifierInputPipeline.BITMAP_IMAGE_EDIT), renderedOptions = options,
                    draftInput = input, draftPipeline = ModifierInputPipeline.BITMAP_IMAGE_EDIT)
            })
        val decoded = EditorCompleteCodec.decode(EditorCompleteCodec.encode(EditorRecoveryFixtures.capture(original)))
        val bound = bindEditorRecovery(decoded, EditorRecoveryResources(RuntimeEnvironment.getApplication()))
        try {
            val recovered = EditorRecoveryFixtures.editor().apply { restoreDraft(bound) }
            val ui = options.withColorizerStyle(ColorizerStyle(firstColor = Color.GREEN))
                .withExport { copy(themed = true, background = ColorFill(Color.MAGENTA)) }
            verify(requireNotNull(bound.artwork[bound.capture.snapshot.raw]),
                recovered.generationOptionsFor(ui, recovered.captureRenderWitness()))
        } finally { bound.disposeUnattached() }
    }

    private fun verify(input: IconPackDrawable, options: GenerationOptions) {
        val rendered = IconModifierStageRenderer(RuntimeEnvironment.getApplication().resources, options)
            .render(input, ModifierInputPipeline.BITMAP_IMAGE_EDIT)
        val screenshot = RoborazziOptions()
        // Ordinary unit tests verify too; only an explicit record task may update the golden.
        rendered.toModifierBitmap().captureRoboImage("src/test/screenshots/icon-shadow-gradient.png",
            screenshot.copy(taskType = screenshot.taskType.takeUnless { it == RoborazziTaskType.None } ?: RoborazziTaskType.Verify))
    }
}
