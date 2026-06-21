package dev.alembiconsProject.alembicons.drawable

import android.graphics.Path
import android.graphics.drawable.Drawable

// Drawable's draw / setAlpha / setColorFilter / getOpacity are already abstract, so they're
// left for the concrete subclasses (TextDrawable, MultiLineTextDrawable) to implement.
abstract class BaseTextDrawable: Drawable() {
    abstract fun getPaths(): List<Path>
}