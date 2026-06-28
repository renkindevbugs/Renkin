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
 * Sends [bitmap] (the icon being edited) to an external image editor so the user can touch it up
 * with tools we don't have (ML background erase, filters, …). Opens ImageToolbox directly when it's
 * installed; otherwise falls back to an `ACTION_EDIT` chooser, which lists only image editors — not
 * unrelated apps like mail or messengers (`ACTION_SEND` would). The result doesn't return
 * automatically: the user shares the edited image back to Renkin, where the share receiver in
 * [dev.alembiconsProject.alembicons.MainActivity] drops it into the upload gallery.
 */
fun shareIconForEditing(context: Context, bitmap: Bitmap) {
    val uri = writeSharedIcon(context, bitmap)

    val toImageToolbox = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        setPackage(IMAGE_TOOLBOX_PACKAGE)
    }
    if (toImageToolbox.resolveActivity(context.packageManager) != null) {
        context.startActivity(toImageToolbox)
        return
    }

    val edit = Intent(Intent.ACTION_EDIT).apply {
        setDataAndType(uri, "image/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(edit, context.getString(R.string.editInAnotherApp)))
}

private fun writeSharedIcon(context: Context, bitmap: Bitmap): Uri {
    val dir = File(context.cacheDir, "shared").apply { mkdirs() }
    val file = File(dir, "icon_${System.currentTimeMillis()}.png")
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    return FileProvider.getUriForFile(context, "${context.packageName}.fileProvider", file)
}
