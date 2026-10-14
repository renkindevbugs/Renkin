package dev.renkinProject.renkin.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.renkinProject.renkin.R

// ImageToolbox keeps its original applicationId for update compatibility, even though its package
// namespace is now com.t8rin.imagetoolbox.
private const val IMAGE_TOOLBOX_PACKAGE = "ru.tech.imageresizershrinker"

/** Whether the FOSS ImageToolbox editor is installed, so the UI can offer to open it directly. */
fun imageToolboxInstalled(context: Context): Boolean =
    runCatching { context.packageManager.getPackageInfo(IMAGE_TOOLBOX_PACKAGE, 0) }.isSuccess

/**
 * Sends [sharedIcon] (the edited icon, written by [dev.renkinProject.renkin.data.SharedIconHandoff])
 * straight to ImageToolbox so the user can touch it up with tools we don't have (ML background
 * erase, filters, …). The result doesn't return automatically: the user shares the edited image
 * back to Renkin, where the share receiver in [dev.renkinProject.renkin.MainActivity] drops it
 * into the upload gallery.
 */
internal fun openInImageToolbox(context: Context, sharedIcon: Uri): Boolean {
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
 * Sends [sharedIcon] to an editor the user picks. `ACTION_EDIT` lists only image editors — not
 * unrelated apps like mail or messengers (`ACTION_SEND` would). The edited image comes back the
 * same way as [openInImageToolbox]: shared back to Renkin into the upload gallery.
 */
internal fun editInAnotherApp(context: Context, sharedIcon: Uri): Boolean {
    val edit = Intent(Intent.ACTION_EDIT).apply {
        setDataAndType(sharedIcon, "image/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    if (edit.resolveActivity(context.packageManager) == null) return false
    return runCatching {
        context.startActivity(Intent.createChooser(edit, context.getString(R.string.editInAnotherApp)))
    }.isSuccess
}

