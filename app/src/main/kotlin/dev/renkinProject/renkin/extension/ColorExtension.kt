package dev.renkinProject.renkin.extension

import android.graphics.Color as AndroidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.roundToInt

// Pure colour conversions shared across the data, vector, apk and ui layers. They live in
// `extension` (not `ui`) so non-UI code can use them without depending on the UI layer.

/** "#aarrggbb" hex for [this] colour (alpha first), the format persisted in preferences/XML. */
fun Color.toHexString(): String {
    return String.format(
        "#%02x%02x%02x%02x", this.alphaInt, this.redInt, this.greenInt, this.blueInt
    )
}

val Color.alphaInt: Int
    get() = floatTo255Component(this.alpha)

val Color.redInt: Int
    get() = floatTo255Component(this.red)

val Color.greenInt: Int
    get() = floatTo255Component(this.green)

val Color.blueInt: Int
    get() = floatTo255Component(this.blue)

private fun floatTo255Component(component: Float): Int {
    // Round, not truncate: e.g. alpha 128/255 (≈0.50196) * 255 = 127.999… which would
    // truncate to 127 (7f) and drift on every save/parse round-trip.
    return (component * 255).roundToInt()
}

fun String.toColor(): Color {
    return Color(AndroidColor.parseColor(this))
}

fun String.toNullableColor(): Color? {
    return try {
        this.toColor()
    } catch (ex: IllegalArgumentException) {
        null
    }
}

fun Color.toInt(): Int {
    return this.toArgb()
}
