package dev.renkinProject.renkin.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorLoadingBorderComposeTest {
    @get:Rule val compose = createComposeRule()
    private var contentView: View? = null

    private fun content(loading: MutableState<Boolean>, apply: () -> Unit = {}) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val view = LocalView.current
            SideEffect { contentView = view }
            MaterialTheme(colorScheme = lightColorScheme(primary = Color.Red)) {
                Box(
                    Modifier.size(280.dp, 160.dp).background(Color.Black).testTag("card").editorLoadingBorder(loading.value),
                    contentAlignment = Alignment.Center
                ) {
                    Button(onClick = apply, enabled = !loading.value, modifier = Modifier.testTag("apply")) {
                        Text("Apply")
                    }
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
    }

    private fun indicators() = compose.onAllNodes(
        SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate),
        useUnmergedTree = true
    )

    private fun borderRed(): Float {
        val bounds = compose.onNodeWithTag("card").fetchSemanticsNode().boundsInRoot
        var red = 0f
        compose.runOnIdle {
            val view = requireNotNull(contentView)
            val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            // Window capture waits for a real redraw; this keeps the controlled test clock frozen.
            view.draw(Canvas(image))
            red = Color(image.getPixel(bounds.center.x.toInt(), bounds.top.toInt() + 1)).red
        }
        return red
    }

    @Test fun completionDoesNotMoveContentOrDelayInteraction() {
        val loading = mutableStateOf(true)
        var applications = 0
        content(loading) { applications++ }
        val cardBounds = compose.onNodeWithTag("card").fetchSemanticsNode().boundsInRoot
        val applyBounds = compose.onNodeWithTag("apply").fetchSemanticsNode().boundsInRoot
        indicators().assertCountEquals(1)
        compose.onNodeWithTag("apply").assertIsNotEnabled()

        compose.runOnIdle { loading.value = false }
        compose.mainClock.advanceTimeByFrame()

        indicators().assertCountEquals(0)
        compose.onNodeWithTag("apply").assertIsEnabled().performClick()
        assertEquals(1, applications)
        assertTrue("The visual outline must remain briefly after real loading ends", borderRed() > 0.05f)
        assertEquals(cardBounds, compose.onNodeWithTag("card").fetchSemanticsNode().boundsInRoot)
        assertEquals(applyBounds, compose.onNodeWithTag("apply").fetchSemanticsNode().boundsInRoot)
        compose.mainClock.advanceTimeBy(500)
        assertTrue("The completion outline must eventually fade away", borderRed() < 0.01f)
        assertEquals(cardBounds, compose.onNodeWithTag("card").fetchSemanticsNode().boundsInRoot)
        assertEquals(applyBounds, compose.onNodeWithTag("apply").fetchSemanticsNode().boundsInRoot)
    }

    @Test fun newWorkInterruptsCompletionWithoutLosingTheActiveIndicator() {
        val loading = mutableStateOf(true)
        content(loading)
        compose.mainClock.advanceTimeBy(160)
        compose.runOnIdle { loading.value = false }
        compose.mainClock.advanceTimeBy(80)
        indicators().assertCountEquals(0)

        compose.runOnIdle { loading.value = true }
        compose.mainClock.advanceTimeBy(500)

        indicators().assertCountEquals(1)
        compose.onNodeWithTag("apply").assertIsNotEnabled()
        compose.runOnIdle { loading.value = false }
        compose.mainClock.advanceTimeBy(500)
        indicators().assertCountEquals(0)
        compose.onNodeWithTag("apply").assertIsEnabled()
    }
}
