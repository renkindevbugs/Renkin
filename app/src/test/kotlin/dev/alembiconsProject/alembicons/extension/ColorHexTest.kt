package dev.alembiconsProject.alembicons.extension

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

    @Test
    fun toHexString_roundsComponents() {
        // alpha 128/255 ≈ 0.50196 → 127.999…; rounding keeps it 0x80 instead of 0x7f.
        assertEquals("#80ff0000", Color(0x80FF0000).toHexString())
    }
}
