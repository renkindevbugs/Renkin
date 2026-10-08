package dev.renkinProject.renkin.data.editor

import android.app.Application
import android.graphics.Bitmap
import dev.renkinProject.renkin.data.UploadedImageStore
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33], shadows = [EditorResourceOsShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorDraftResourcesTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val reader get() = EditorDraftResources(context)
    private fun png(width: Int = 4): File = File(UploadedImageStore.directory(context), "draft-test.png").apply {
        val bitmap = Bitmap.createBitmap(width, 2, Bitmap.Config.ARGB_8888)
        outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }; bitmap.recycle()
    }
    @After fun cleanup() { assertEquals(0, EditorResourceOsShadow.openCount); EditorResourceOsShadow.reset() }
    private suspend fun rejected(block: suspend () -> Unit) {
        try { block(); fail("Invalid resource accepted") } catch (_: IOException) { } catch (_: IllegalArgumentException) { }
    }

    @Test fun capturesExactGalleryBytesDigestAndSelectionWithoutExposingMutableContent() = runBlocking {
        val file = png(); val bytes = file.readBytes()
        EditorResourceOsShadow.readLimit = 3
        val captured = reader.upload(file.absolutePath)
        assertEquals(EditorDraftResourceKind.UPLOAD, captured.kind)
        assertEquals(file.absolutePath, captured.selectionReference)
        assertArrayEquals(bytes, captured.copyBytes())
        assertEquals(bytes.size, captured.byteCount)
        assertEquals(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, captured.sha256)
        captured.copyBytes().fill(0); file.writeBytes(byteArrayOf(0))
        assertArrayEquals(bytes, captured.copyBytes())
    }

    @Test fun resourceConstructorAlsoDetachesCallerBytesAndRejectsInvalidBudgets() {
        val bytes = byteArrayOf(1, 2, 3)
        val captured = EditorDraftResource(EditorDraftResourceKind.FONT, "font", bytes)
        bytes.fill(0); assertArrayEquals(byteArrayOf(1, 2, 3), captured.copyBytes())
        assertThrows(IllegalArgumentException::class.java) { EditorDraftResource(EditorDraftResourceKind.FONT, "", byteArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) { EditorDraftResource(EditorDraftResourceKind.FONT, "x".repeat(65537), byteArrayOf(1)) }
    }

    @Test fun bundledFontIsCapturedAsOwnedBytesWithoutASystemPath() = runBlocking {
        val captured = reader.font("")
        assertEquals(EditorDraftResourceKind.FONT, captured.kind); assertEquals("", captured.selectionReference)
        assertTrue(captured.byteCount > 12); assertEquals(64, captured.sha256.length)
        assertArrayEquals(captured.copyBytes(), reader.font("").copyBytes())
    }

    @Test fun missingOrOutOfCatalogFontsDoNotBecomeTheBundledDefault() = runBlocking {
        val file = png()
        rejected { reader.font(file.absolutePath) }
        rejected { reader.font("/system/fonts/missing-draft-font.ttf") }
        rejected { reader.font("../font.ttf") }
    }

    @Test fun uploadPathsMustBeDirectAbsolutePngChildren() = runBlocking {
        val file = png()
        rejected { reader.upload(file.name) }
        val outside = File(context.filesDir, "outside.png").apply { writeBytes(file.readBytes()) }
        rejected { reader.upload(outside.absolutePath) }
        rejected { reader.upload(File(file.parentFile, "../outside.png").absolutePath) }
        val nested = File(file.parentFile, "nested/image.png").apply { parentFile!!.mkdirs(); writeBytes(file.readBytes()) }
        rejected { reader.upload(nested.absolutePath) }
        val renamed = File(file.parentFile, "image.dat").apply { writeBytes(file.readBytes()) }
        rejected { reader.upload(renamed.absolutePath) }
    }

    @Test fun invalidPngAndOverLimitBackupImageAreRejected() = runBlocking {
        val file = png(4097)
        rejected { reader.upload(file.absolutePath) }
        file.writeBytes("not PNG".toByteArray()); rejected { reader.upload(file.absolutePath) }
        file.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 13, 10, 26, 10))
        rejected { reader.upload(file.absolutePath) }
        assertEquals(0, EditorResourceOsShadow.openCount)
    }

    @Test fun emptyAndOversizedFilesFailBeforeReadingPayloads() = runBlocking {
        val file = png(); file.writeBytes(byteArrayOf())
        rejected { reader.upload(file.absolutePath) }
        RandomAccessFile(file, "rw").use { it.setLength(EditorDraftCodec.MAX_ARTIFACT_BYTES + 1) }
        try { rejected { reader.upload(file.absolutePath) } } finally { file.delete() }
    }

    @Test fun truncationGrowthAndPathReplacementRejectCaptureAndCloseDescriptors() = runBlocking {
        var file = png()
        EditorResourceOsShadow.beforeRead = { RandomAccessFile(file, "rw").use { it.setLength(8) } }
        rejected { reader.upload(file.absolutePath) }; assertEquals(0, EditorResourceOsShadow.openCount)
        file = png()
        EditorResourceOsShadow.beforeRead = { file.appendBytes(byteArrayOf(1)) }
        rejected { reader.upload(file.absolutePath) }; assertEquals(0, EditorResourceOsShadow.openCount)
        file = png(); EditorResourceOsShadow.changedPathIdentity = true
        rejected { reader.upload(file.absolutePath) }; assertEquals(0, EditorResourceOsShadow.openCount)
    }

    @Test fun cancellationDuringReadPropagatesAndClosesItsDescriptor() {
        val file = png()
        EditorResourceOsShadow.beforeRead = { throw CancellationException("cancel capture") }
        assertThrows(CancellationException::class.java) { runBlocking { reader.upload(file.absolutePath) } }
        assertEquals(0, EditorResourceOsShadow.openCount)
    }
}
