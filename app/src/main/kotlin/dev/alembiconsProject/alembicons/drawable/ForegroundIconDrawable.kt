package dev.alembiconsProject.alembicons.drawable

import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.O)
class ForegroundIconDrawable(drawable: Drawable): AdaptiveIconDrawable(null, drawable) {
    override fun draw(canvas: Canvas) {
        foreground.draw(canvas)
    }
}