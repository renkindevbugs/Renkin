package dev.renkinProject.renkin.icon.creator

import android.content.Context
import androidx.compose.ui.graphics.Color
import dev.renkinProject.renkin.packages.supportDynamicColors

/**
 * The system colours of each Material You pair (foreground to background), in picker order. Live
 * themed icons reference the same ids from the pack, so they follow later wallpaper changes.
 */
internal val MATERIAL_YOU_SCHEME_COLOR_IDS: List<Pair<Int, Int>> = listOf(
    android.R.color.system_accent1_100 to android.R.color.system_accent1_800,
    android.R.color.system_accent2_100 to android.R.color.system_accent2_800,
    android.R.color.system_accent3_100 to android.R.color.system_accent3_800,
    android.R.color.system_neutral1_100 to android.R.color.system_neutral1_800,
    android.R.color.system_accent1_800 to android.R.color.system_accent1_100
)

/**
 * The Material You colour pairs (foreground to background) offered for app icons, taken from the
 * current wallpaper. Devices without dynamic colours get plain light and dark pairs instead.
 * Shared by the editor's picker and the refresh, so both resolve the same index to the same pair.
 */
fun materialYouSchemes(context: Context): List<Pair<Color, Color>> {
    if (!supportDynamicColors()) return listOf(Color.White to Color.Black, Color.Black to Color.White)
    fun c(id: Int) = Color(context.resources.getColor(id, context.theme))
    return MATERIAL_YOU_SCHEME_COLOR_IDS.map { (foreground, background) -> c(foreground) to c(background) }
}
