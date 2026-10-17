package dev.renkinProject.renkin.icon.creator

import android.content.Context
import androidx.compose.ui.graphics.Color
import dev.renkinProject.renkin.packages.supportDynamicColors

/**
 * The Material You colour pairs (foreground to background) offered for app icons, taken from the
 * current wallpaper. Devices without dynamic colours get plain light and dark pairs instead.
 * Shared by the editor's picker and the refresh, so both resolve the same index to the same pair.
 */
fun materialYouSchemes(context: Context): List<Pair<Color, Color>> {
    if (!supportDynamicColors()) return listOf(Color.White to Color.Black, Color.Black to Color.White)
    fun c(id: Int) = Color(context.resources.getColor(id, context.theme))
    return listOf(
        c(android.R.color.system_accent1_100) to c(android.R.color.system_accent1_800),
        c(android.R.color.system_accent2_100) to c(android.R.color.system_accent2_800),
        c(android.R.color.system_accent3_100) to c(android.R.color.system_accent3_800),
        c(android.R.color.system_neutral1_100) to c(android.R.color.system_neutral1_800),
        c(android.R.color.system_accent1_800) to c(android.R.color.system_accent1_100)
    )
}
