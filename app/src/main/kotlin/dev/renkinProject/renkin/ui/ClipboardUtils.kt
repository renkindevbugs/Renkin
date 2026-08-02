package dev.renkinProject.renkin.ui

import android.content.ClipData
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.toClipEntry

internal suspend fun Clipboard.copyPlainText(label: String, text: String) {
    setClipEntry(ClipData.newPlainText(label, text).toClipEntry())
}
