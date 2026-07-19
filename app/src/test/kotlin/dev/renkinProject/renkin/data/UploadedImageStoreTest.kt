package dev.renkinProject.renkin.data

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class UploadedImageStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before
    fun clearStore() {
        UploadedImageStore.directory(context).deleteRecursively()
    }

    @Test
    fun trashHidesImageAndUndoRestoresIt() {
        val image = UploadedImageStore.directory(context).resolve("image.png").apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }

        val entries = UploadedImageStore.moveToTrash(context, listOf(image))

        assertEquals(1, entries.size)
        assertFalse(image.exists())
        assertTrue(UploadedImageStore.list(context).isEmpty())

        UploadedImageStore.restore(entries)

        assertTrue(image.exists())
        assertEquals(listOf(image), UploadedImageStore.list(context))
    }

    @Test
    fun cleanupFinalizesInterruptedDeletion() {
        val image = UploadedImageStore.directory(context).resolve("image.png").apply {
            writeBytes(byteArrayOf(1))
        }
        val entries = UploadedImageStore.moveToTrash(context, listOf(image))

        UploadedImageStore.cleanupTrash(context)

        assertFalse(entries.single().trashed.exists())
        assertTrue(UploadedImageStore.list(context).isEmpty())
    }
}
