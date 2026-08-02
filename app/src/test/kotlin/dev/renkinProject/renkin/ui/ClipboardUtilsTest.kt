package dev.renkinProject.renkin.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ClipboardUtilsTest {

    @Test
    fun copyPlainTextCreatesLabeledTextEntry() = runBlocking {
        val clipboard = RecordingClipboard(
            ApplicationProvider.getApplicationContext<Context>()
                .getSystemService(ClipboardManager::class.java)
        )

        clipboard.copyPlainText("Crash log", "stack trace")

        val clipData = requireNotNull(clipboard.entry).clipData
        assertEquals("Crash log", clipData.description.label.toString())
        assertEquals("stack trace", clipData.getItemAt(0).text.toString())
    }

    private class RecordingClipboard(
        override val nativeClipboard: ClipboardManager
    ) : Clipboard {
        var entry: ClipEntry? = null

        override suspend fun getClipEntry(): ClipEntry? = entry

        override suspend fun setClipEntry(clipEntry: ClipEntry?) {
            entry = clipEntry
        }
    }
}
