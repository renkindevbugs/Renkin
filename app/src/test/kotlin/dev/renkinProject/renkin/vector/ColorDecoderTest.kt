package dev.renkinProject.renkin.vector

import android.app.Application
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [ColorDecoder] parses the colour notations found in vectors / imported SVGs. Runs under
 * Robolectric because the hex path goes through android.graphics.Color. The rgba case is a
 * regression guard: "rgba(...)" used to be swallowed by the "rgb" prefix check and mis-parsed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class ColorDecoderTest {

    private val resources get() = RuntimeEnvironment.getApplication().resources

    @Test
    fun parsesRgbaWithItsAlpha() {
        assertEquals(Color(1f, 0f, 0f, 0.5f), ColorDecoder.decode(resources, "rgba(1,0,0,0.5)"))
    }

    @Test
    fun parsesRgb() {
        assertEquals(Color(0f, 1f, 0f), ColorDecoder.decode(resources, "rgb(0,1,0)"))
    }

    @Test
    fun parsesHex() {
        assertEquals(Color.Red, ColorDecoder.decode(resources, "#FF0000"))
    }

    @Test
    fun unknownNotationFallsBackToDefault() {
        assertEquals(Color.Blue, ColorDecoder.decode(resources, "not-a-colour", Color.Blue))
    }

    @Test
    fun svgCssRgbUsesByteChannelsAndPercentAlpha() {
        assertEquals(
            Color(128f / 255f, 64f / 255f, 0f),
            ColorDecoder.decodeSvgCss("rgb(128, 64, 0)")
        )
        assertEquals(
            Color(1f, 0f, 0f, 0.5f),
            ColorDecoder.decodeSvgCss("rgba(100%, 0%, 0%, 50%)")
        )
    }

    @Test
    fun svgCssHexKeepsTrailingAlphaAndNamedColours() {
        assertEquals(Color(1f, 0f, 0f, 128f / 255f), ColorDecoder.decodeSvgCss("#ff000080"))
        assertEquals(Color(0f, 1f, 0f, 0x88 / 255f), ColorDecoder.decodeSvgCss("#0f08"))
        assertEquals(Color.Red, ColorDecoder.decodeSvgCss("red"))
    }
}
