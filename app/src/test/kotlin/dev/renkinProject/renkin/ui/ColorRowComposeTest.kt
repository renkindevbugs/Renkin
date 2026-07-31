package dev.renkinProject.renkin.ui

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.renkinProject.renkin.ui.theme.InnerShape
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The shared colour row replaced three byte-identical copies (global options, modifier tab,
 * icon browser). These pin the contract every caller relied on — the label shows and the tap
 * opens the picker — including the nested variant that passes its own shape.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ColorRowComposeTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun showsItsLabelAndOpensThePickerOnTap() {
        var taps = 0
        compose.setContent {
            ColorRow(label = "Icon color", color = Color.Red) { taps++ }
        }

        compose.onNodeWithText("Icon color").assertIsDisplayed()
        compose.onNodeWithText("Icon color").performClick()
        assertEquals(1, taps)
    }

    @Test
    fun nestedVariantWithItsOwnShapeBehavesTheSame() {
        var taps = 0
        compose.setContent {
            ColorRow(label = "Background color", color = Color.Blue, shape = InnerShape) { taps++ }
        }

        compose.onNodeWithText("Background color").assertIsDisplayed()
        compose.onNodeWithText("Background color").performClick()
        assertEquals(1, taps)
    }
}
