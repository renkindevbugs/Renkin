package dev.renkinProject.renkin.feature.presets

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createComposeRule
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.data.ModifierPreset
import dev.renkinProject.renkin.icon.creator.ModifierPresetPayload
import dev.renkinProject.renkin.icon.creator.encodeModifierPreset
import dev.renkinProject.renkin.ui.AdjustmentState
import dev.renkinProject.renkin.ui.ModifierPreviews
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Editing the icon restarts every preset thumbnail render. A render cancelled that way must not
 * be shown as a failed preview while its replacement is still on the way.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PresetPreviewComposeTest {

    @get:Rule
    val compose = createComposeRule()

    private val preset = ModifierPreset(
        id = 1,
        name = "Bigger",
        payload = encodeModifierPreset(ModifierPresetPayload(iconScale = 1.2f)),
        schemaVersion = 1
    )

    @Test
    fun cancelledRenderKeepsTheLoadingStateUntilTheNewRenderFinishes() {
        val newRender = CompletableDeferred<Bitmap>()
        var previews by mutableStateOf(previews(key = 1) { awaitCancellation() })
        compose.mainClock.autoAdvance = false
        compose.setContent {
            ProvideModifierPresets(listOf(preset), { _, _, _ -> }, { _, _, _ -> }, { _, _ -> }, {}, {}) {
                ModifierPresetsSection(
                    adjustments = AdjustmentState(),
                    imageEdit = ImageEdit.NONE,
                    iconColor = Color.Black,
                    previews = previews,
                    onPresetLoaded = {}
                )
            }
        }
        compose.mainClock.advanceTimeBy(RENDER_START_MS)

        previews = previews(key = 2) { newRender.await() }
        compose.mainClock.advanceTimeBy(FRAME_MS)

        compose.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertExists()

        newRender.complete(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888))
        compose.mainClock.advanceTimeBy(RENDER_START_MS)

        compose.onNode(hasContentDescription("Bigger", substring = true)).assertExists()
    }

    private fun previews(key: Int, render: suspend () -> Bitmap?) = ModifierPreviews(
        colorizeBase = null,
        positionBase = { null },
        backgroundBrush = { null },
        colorize = { null },
        shape = { null },
        outline = { null },
        shadow = { null },
        preset = { render() },
        presetKey = key,
        presetSourceKey = "source",
        layers = { _, _ -> null },
        materialYouPackForeground = { null },
        materialYouPackBackground = { null }
    )

    private companion object {
        /** Past the thumbnail's settle delay, so the render itself is running. */
        const val RENDER_START_MS = 500L
        const val FRAME_MS = 32L
    }
}
