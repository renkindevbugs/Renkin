package dev.alembiconsProject.alembicons.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [toHexString] is pure (Compose [Color] components + String.format), so it can run as a
 * plain JVM unit test. The inverse [toColor] uses android.graphics.Color and is excluded.
 */
class ColorHexTest {

    @Test
    fun toHexString_opaquePrimaries() {
        assertEquals("#ffffffff", Color.White.toHexString())
        assertEquals("#ff000000", Color.Black.toHexString())
        assertEquals("#ffff0000", Color.Red.toHexString())
        assertEquals("#ff00ff00", Color.Green.toHexString())
        assertEquals("#ff0000ff", Color.Blue.toHexString())
    }

    @Test
    fun toHexString_alphaComesFirst() {
        // Transparent white: alpha 0, RGB 255 — both exact floats, so no rounding quirk.
        assertEquals("#00ffffff", Color(0x00FFFFFF).toHexString())
    }
}
