package dev.renkinProject.renkin.ui

import android.graphics.Bitmap
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Build
import androidx.annotation.WorkerThread
import dev.renkinProject.renkin.drawable.haveMonochrome
import dev.renkinProject.renkin.drawable.isAdaptiveIconDrawable
import dev.renkinProject.renkin.drawable.toSafeBitmapOrNull
import dev.renkinProject.renkin.icon.creator.AppBackgroundColor
import dev.renkinProject.renkin.icon.creator.appBackgroundColor
import dev.renkinProject.renkin.packages.InstalledIconLoader
import dev.renkinProject.renkin.packages.PackageInfoStruct

internal data class EditorApplicationIcon(
    val application: PackageInfoStruct,
    val hero: Bitmap?,
    val background: AppBackgroundColor?,
    val adaptive: Boolean,
    val monochrome: Boolean
)

@WorkerThread
internal fun prepareEditorApplicationIcon(app: PackageInfoStruct, loader: InstalledIconLoader): EditorApplicationIcon? {
    val loaded = loader.load(app.iconSource) ?: return null
    val drawable = loaded.drawable.constantState?.newDrawable()?.mutate() ?: loaded.drawable
    val width = drawable.intrinsicWidth
    val height = drawable.intrinsicHeight
    val scale = (512f / maxOf(width, height).coerceAtLeast(1)).coerceAtMost(1f)
    val hero = if (width > 0 && height > 0) {
        drawable.toSafeBitmapOrNull((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
    } else null
    val adaptive = drawable.isAdaptiveIconDrawable()
    val monochrome = Build.VERSION.SDK_INT >= 26 && adaptive && (drawable as AdaptiveIconDrawable).haveMonochrome()
    return EditorApplicationIcon(app.withLoadedApplicationIcon(loaded), hero,
        runCatching { appBackgroundColor(drawable) }.getOrNull(), adaptive, monochrome)
}
