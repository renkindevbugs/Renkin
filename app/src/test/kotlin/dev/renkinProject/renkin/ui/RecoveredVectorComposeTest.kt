package dev.renkinProject.renkin.ui

import android.app.Application
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import dev.renkinProject.renkin.drawable.ImageVectorDrawable
import dev.renkinProject.renkin.ui.theme.RenkinTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecoveredVectorComposeTest {
    @get:Rule val compose = createComposeRule()

    private fun vector() = ImageVectorDrawable(ImageVector.Builder(defaultWidth = 24.dp,
        defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
        addPath(listOf(PathNode.MoveTo(3f, 3f), PathNode.LineTo(20f, 20f)),
            stroke = SolidColor(Color.White), strokeLineWidth = 1f)
    }.build())

    @Test fun recoveredVectorInitializesWithoutSelectingSourceAndEditsIncludingReversalPublish() {
        val source = vector()
        val editor = EditorRecoveryFixtures.editor(EditorRecoveryFixtures.application(source))
        val state = editor.vectorEditState.apply { restoreDraftSeed(null) }
        val visible = mutableStateOf(true)
        var publications = 0
        compose.setContent {
            RenkinTheme {
                CompositionLocalProvider(LocalToaster provides Toaster()) {
                    if (visible.value) VectorOptionsTab(false, source, PaddingValues(), editor.application, state,
                        { null }, { _, _ -> error("Unexpected import") }, {
                            publications++
                            if (it == null) editor.draft.clearVector() else editor.activateVector(it)
                        })
                }
            }
        }
        compose.runOnIdle {
            assertTrue(state.initialized); assertEquals(1, state.entries.size)
            assertEquals(state.inputs(), state.publishedInputs)
            assertEquals(0, publications); assertEquals(IconOrigin.CREATE, editor.draft.origin)
            visible.value = false
        }
        compose.runOnIdle { visible.value = true }
        compose.runOnIdle { assertEquals(0, publications); state.thickness = 1.5f }
        compose.runOnIdle { assertEquals(1, publications); assertEquals(IconOrigin.VECTOR, editor.draft.origin); state.thickness = 1f }
        compose.runOnIdle { assertEquals(2, publications); assertEquals(state.inputs(), state.publishedInputs) }
    }

    @Test fun normalVectorSeedDoesNotSelectSourceButActualEditsAndReversalPublish() {
        val source = vector()
        val editor = EditorRecoveryFixtures.editor(EditorRecoveryFixtures.application(source))
        var publications = 0
        compose.setContent {
            RenkinTheme {
                CompositionLocalProvider(LocalToaster provides Toaster()) {
                    VectorOptionsTab(false, source, PaddingValues(), editor.application, editor.vectorEditState,
                        { null }, { _, _ -> error("Unexpected import") }, { publications++; editor.activateVector(requireNotNull(it)) })
                }
            }
        }
        compose.runOnIdle {
            assertEquals(0, publications); assertEquals(IconOrigin.CREATE, editor.draft.origin)
            editor.vectorEditState.thickness = 1.5f
        }
        compose.runOnIdle {
            assertEquals(1, publications); assertEquals(IconOrigin.VECTOR, editor.draft.origin)
            editor.vectorEditState.thickness = 1f
        }
        compose.runOnIdle { assertEquals(2, publications) }
    }

    @Test fun recoveredBitmapVisitAcknowledgesEmptyVectorWithoutPublishingNull() {
        val editor = EditorRecoveryFixtures.editor()
        val state = editor.vectorEditState.apply { restoreDraftSeed(null) }
        compose.setContent {
            RenkinTheme {
                CompositionLocalProvider(LocalToaster provides Toaster()) {
                    VectorOptionsTab(false, editor.application.baseIcon, PaddingValues(), editor.application, state,
                        { null }, { _, _ -> error("Unexpected import") }, { error("Unchanged recovered bitmap published a vector") })
                }
            }
        }
        compose.runOnIdle {
            assertTrue(state.initialized); assertTrue(state.entries.isEmpty())
            assertEquals(state.inputs(), state.publishedInputs)
            assertEquals(IconOrigin.CREATE, editor.draft.origin)
        }
    }
}
