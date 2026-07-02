package dev.alembiconsProject.alembicons.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import dev.alembiconsProject.alembicons.R
import java.io.File

// ImageToolbox keeps its original applicationId for update compatibility, even though its package
// namespace is now com.t8rin.imagetoolbox.
private const val IMAGE_TOOLBOX_PACKAGE = "ru.tech.imageresizershrinker"

/** Whether the FOSS ImageToolbox editor is installed, so the UI can offer to open it directly. */
fun imageToolboxInstalled(context: Context): Boolean =
    runCatching { context.packageManager.getPackageInfo(IMAGE_TOOLBOX_PACKAGE, 0) }.isSuccess

/**
 * Sends [bitmap] (the icon being edited) straight to ImageToolbox so the user can touch it up with
 * tools we don't have (ML background erase, filters, …). The result doesn't return automatically:
 * the user shares the edited image back to Renkin, where the share receiver in
 * [dev.alembiconsProject.alembicons.MainActivity] drops it into the upload gallery.
 */
fun openInImageToolbox(context: Context, bitmap: Bitmap) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, writeSharedIcon(context, bitmap))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        setPackage(IMAGE_TOOLBOX_PACKAGE)
    }
    if (intent.resolveActivity(context.packageManager) != null) context.startActivity(intent)
}

/** Opens ImageToolbox's Play Store page (browser fallback) so the user can install it. */
fun openImageToolboxStore(context: Context) {
    val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$IMAGE_TOOLBOX_PACKAGE"))
    if (market.resolveActivity(context.packageManager) != null) {
        context.startActivity(market)
    } else {
        context.startActivity(Intent(Intent.ACTION_VIEW,
            Uri.parse("https://play.google.com/store/apps/details?id=$IMAGE_TOOLBOX_PACKAGE")))
    }
}

/**
 * Sends [bitmap] to an editor the user picks. `ACTION_EDIT` lists only image editors — not
 * unrelated apps like mail or messengers (`ACTION_SEND` would). The edited image comes back the
 * same way as [openInImageToolbox]: shared back to Renkin into the upload gallery.
 */
fun editInAnotherApp(context: Context, bitmap: Bitmap) {
    val edit = Intent(Intent.ACTION_EDIT).apply {
        setDataAndType(writeSharedIcon(context, bitmap), "image/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(edit, context.getString(R.string.editInAnotherApp)))
}

private fun writeSharedIcon(context: Context, bitmap: Bitmap): Uri {
    val dir = File(context.cacheDir, "shared").apply { mkdirs() }
    // Previous handoffs are stale once a new one starts — don't let them pile up in the cache.
    dir.listFiles()?.forEach { it.delete() }
    val file = File(dir, "icon_${System.currentTimeMillis()}.png")
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    return FileProvider.getUriForFile(context, "${context.packageName}.fileProvider", file)
}
