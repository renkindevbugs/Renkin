package dev.alembiconsProject.alembicons.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import dev.alembiconsProject.alembicons.R
import java.io.File

/**
 * Shares [bitmap] (the icon currently being edited) to any external image editor — e.g. the FOSS
 * ImageToolbox — so the user can touch it up there with tools we don't have (ML background erase,
 * filters, …). The result doesn't return automatically: standalone editors don't implement an
 * "edit and return" contract. The user shares the edited image back to Renkin, where the share
 * receiver in [dev.alembiconsProject.alembicons.MainActivity] drops it into the upload gallery.
 */
fun shareIconForEditing(context: Context, bitmap: Bitmap) {
    val dir = File(context.cacheDir, "shared").apply { mkdirs() }
    val file = File(dir, "icon_${System.currentTimeMillis()}.png")
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileProvider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, context.getString(R.string.editInAnotherApp)))
}
