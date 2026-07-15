package dev.renkinProject.renkin.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import dev.renkinProject.renkin.R
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
 * [dev.renkinProject.renkin.MainActivity] drops it into the upload gallery.
 */
internal suspend fun openInImageToolbox(context: Context, bitmap: Bitmap): Boolean {
    val sharedIcon = runCatching { writeSharedIcon(context, bitmap) }.getOrNull() ?: return false
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, sharedIcon)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        setPackage(IMAGE_TOOLBOX_PACKAGE)
    }
    if (intent.resolveActivity(context.packageManager) == null) return false
    return runCatching { context.startActivity(intent) }.isSuccess
}

/** Opens ImageToolbox's Play Store page (browser fallback) so the user can install it. */
fun openImageToolboxStore(context: Context) {
    val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$IMAGE_TOOLBOX_PACKAGE"))
    if (market.resolveActivity(context.packageManager) != null) {
        context.startActivity(market)
    } else {
        val browser = Intent(Intent.ACTION_VIEW,
            Uri.parse("https://play.google.com/store/apps/details?id=$IMAGE_TOOLBOX_PACKAGE"))
        if (browser.resolveActivity(context.packageManager) != null) context.startActivity(browser)
    }
}

/**
 * Sends [bitmap] to an editor the user picks. `ACTION_EDIT` lists only image editors — not
 * unrelated apps like mail or messengers (`ACTION_SEND` would). The edited image comes back the
 * same way as [openInImageToolbox]: shared back to Renkin into the upload gallery.
 */
internal suspend fun editInAnotherApp(context: Context, bitmap: Bitmap): Boolean {
    val sharedIcon = runCatching { writeSharedIcon(context, bitmap) }.getOrNull() ?: return false
    val edit = Intent(Intent.ACTION_EDIT).apply {
        setDataAndType(sharedIcon, "image/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    if (edit.resolveActivity(context.packageManager) == null) return false
    return runCatching {
        context.startActivity(Intent.createChooser(edit, context.getString(R.string.editInAnotherApp)))
    }.isSuccess
}

private suspend fun writeSharedIcon(context: Context, bitmap: Bitmap): Uri = withContext(Dispatchers.IO) {
    val dir = File(context.cacheDir, "shared").apply { mkdirs() }
    // Previous handoffs are stale once a new one starts — don't let them pile up in the cache.
    dir.listFiles()?.forEach { it.delete() }
    val file = File(dir, "icon_${System.currentTimeMillis()}.png")
    file.outputStream().use {
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "PNG compression failed" }
    }
    FileProvider.getUriForFile(context, "${context.packageName}.fileProvider", file)
}
