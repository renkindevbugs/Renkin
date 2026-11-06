package dev.renkinProject.renkin.ui

import android.app.Application
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import dev.renkinProject.renkin.ui.theme.RenkinTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PreviewVariantStripTest {
    @get:Rule val compose = createComposeRule()

    @Test fun changingTheRenderKeyRegeneratesVisibleThumbnailsWithoutClosingTheStrip() {
        val revision = mutableStateOf(1)
        val calls = mutableListOf<Pair<Int, Int>>()
        compose.setContent {
            val current = revision.value
            RenkinTheme {
                PreviewVariantStrip(PreviewVariants(
                    RotatingIconKind.CALENDAR, listOf("1", "2"), null, null, current, {},
                    render = { index ->
                        calls += current to index
                        EditorRecoveryFixtures.icon()
                    }
                ), CircleShape)
            }
        }
        compose.runOnIdle { assertEquals(setOf(1 to 0, 1 to 1), calls.toSet()) }
        compose.runOnIdle { revision.value = 2 }
        compose.runOnIdle {
            assertEquals(setOf(1 to 0, 1 to 1, 2 to 0, 2 to 1), calls.toSet())
            assertEquals(4, calls.size)
        }
    }
}
