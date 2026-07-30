package dev.renkinProject.renkin

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Window
import android.view.WindowManager

/**
 * Makes transparent content reveal the system wallpaper rather than the preceding Activity.
 * The explicit flags mirror the wallpaper theme for older OEM WindowManager implementations.
 */
internal fun Window.showWallpaperBehindContent() {
    addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
    clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
}
