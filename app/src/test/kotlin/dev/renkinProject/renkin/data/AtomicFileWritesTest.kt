package dev.renkinProject.renkin.data

import android.app.Application
import android.util.AtomicFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class AtomicFileWritesTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun target() = File(folder.root, "metadata.json")

    private fun leftovers() = folder.root.listFiles().orEmpty().map { it.name }.filter { it != "metadata.json" }

    @Test fun publishesExactlyTheWrittenBytes() {
        val file = target()
        file.writeText("older")
        val bytes = "{\"id\":\"next\"}".toByteArray()

        AtomicFile(file).writeVerified(bytes)

        assertArrayEquals(bytes, file.readBytes())
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test fun verifiesALargeFileInChunks() {
        val file = target()
        val bytes = ByteArray(5 * 1024 * 1024 + 17) { (it % 251).toByte() }

        AtomicFile(file).writeVerified(bytes)

        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun beforePublishRunsBeforeTheFileIsVisible() {
        val file = target()
        var visibleInHook = true

        AtomicFile(file).writeVerified("data".toByteArray()) { visibleInHook = file.exists() }

        assertFalse(visibleInHook)
        assertTrue(file.isFile)
    }

    @Test fun failureBeforePublishingKeepsThePreviousFileAndRemovesTheTemporaryOne() {
        val file = target()
        file.writeText("previous")

        val error = assertThrows(IOException::class.java) {
            AtomicFile(file).writeVerified("next".toByteArray()) { throw IOException("directory sync failed") }
        }

        assertEquals("directory sync failed", error.message)
        assertEquals("previous", file.readText())
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test fun silentlyFailedRenameIsReported() {
        // A non-empty directory in the target's place makes AtomicFile's rename fail; it only logs that.
        val file = target().apply { mkdirs() }
        File(file, "child").writeText("x")

        val error = assertThrows(IOException::class.java) { AtomicFile(file).writeVerified("next".toByteArray()) }
        assertEquals("Cannot publish metadata.json", error.message)
    }
}
