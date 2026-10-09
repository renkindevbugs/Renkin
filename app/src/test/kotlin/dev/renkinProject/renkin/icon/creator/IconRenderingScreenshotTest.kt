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
import dev.renkinProject.renkin.drawable.BitmapIconDrawable
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
    @Test fun shadowAndGradientMatchReference() {
        val input = BitmapIconDrawable(Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).apply {
            density = Bitmap.DENSITY_NONE
            Canvas(this).drawCircle(48f, 48f, 28f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        }, false)
        val gradient = ColorizerStyle(ColorizerMode.GRADIENT, GradientType.LINEAR, 0xffff6b35.toInt(),
            listOf(0xff7047eb.toInt()), listOf(0f, 1f), 120f)
        val options = GenerationOptions(IconSourceOptions(Source.ICON_PACK), ModifierStack(
            effect = EffectModifier(ImageEdit.COLORIZE, gradient),
            shadow = ShadowModifier(4f, 5f, 135f, false, ColorizerStyle(firstColor = Color.BLACK), 0.6f)
        ), IconExportOptions(), true)
        val rendered = IconModifierStageRenderer(RuntimeEnvironment.getApplication().resources, options)
            .render(input, ModifierInputPipeline.BITMAP_IMAGE_EDIT)
        val screenshot = RoborazziOptions()
        // Ordinary unit tests verify too; only an explicit record task may update the golden.
        rendered.toModifierBitmap().captureRoboImage("src/test/screenshots/icon-shadow-gradient.png",
            screenshot.copy(taskType = screenshot.taskType.takeUnless { it == RoborazziTaskType.None } ?: RoborazziTaskType.Verify))
    }
}
