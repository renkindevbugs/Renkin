package dev.renkinProject.renkin.data

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.ShareImportActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class SharedImageImporterTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val uri = Uri.parse("content://editor/shared/icon.png")

    private class FakeLibrary : UploadedImageLibrary {
        val imports = AtomicInteger()
        var release: CompletableDeferred<Unit>? = null
        var succeeds = true

        override suspend fun import(uris: List<Uri>): UploadImport {
            imports.incrementAndGet()
            release?.await()
            return if (succeeds) UploadImport(listOf(File("img.png")), false) else UploadImport(emptyList(), true)
        }

        override suspend fun load(): List<File> = emptyList()
        override suspend fun list(): List<File> = emptyList()
        override suspend fun moveToTrash(files: List<File>): List<UploadedImageStore.TrashEntry> = emptyList()
        override suspend fun restore(entries: List<UploadedImageStore.TrashEntry>) = Unit
        override suspend fun permanentlyDelete(entries: List<UploadedImageStore.TrashEntry>) = Unit
        override suspend fun decode(path: String): Bitmap? = null
        override suspend fun thumbnail(file: File): Bitmap? = null
    }

    @After fun cancelScope() = scope.cancel()

    @Test fun aRequestForAUriStillBeingCopiedWaitsInsteadOfCopyingAgain() = runBlocking {
        val library = FakeLibrary().apply { release = CompletableDeferred() }
        val importer = SharedImageImporter(library, scope)

        val first = async(Dispatchers.Default) { importer.import(uri) }
        val second = async(Dispatchers.Default) { importer.import(uri) }
        withTimeout(5_000) { while (library.imports.get() == 0) kotlinx.coroutines.yield() }
        library.release?.complete(Unit)
        withTimeout(5_000) { first.await(); second.await() }

        assertEquals(1, library.imports.get())
        assertEquals(R.string.sharedImageAdded, importer.toastEvents.first())
    }

    @Test fun aNewShareOfTheSameUriAfterTheCopyImportsAgain() = runBlocking {
        val library = FakeLibrary()
        val importer = SharedImageImporter(library, scope)

        importer.import(uri)
        importer.import(uri)

        assertEquals(2, library.imports.get())
    }

    @Test fun aFailedCopyReportsTheErrorAndCanBeRetried() = runBlocking {
        val library = FakeLibrary().apply { succeeds = false }
        val importer = SharedImageImporter(library, scope)

        importer.import(uri)
        library.succeeds = true
        importer.import(uri)

        assertEquals(R.string.uploadImageError, importer.toastEvents.first())
        assertEquals(R.string.sharedImageAdded, importer.toastEvents.first())
    }

    @Test fun theCopyOutlivesACancelledCaller() = runBlocking {
        val library = FakeLibrary().apply { release = CompletableDeferred() }
        val importer = SharedImageImporter(library, scope)

        val caller = async(Dispatchers.Default) { importer.import(uri) }
        withTimeout(5_000) { while (library.imports.get() == 0) kotlinx.coroutines.yield() }
        caller.cancel()
        library.release?.complete(Unit)

        assertEquals(R.string.sharedImageAdded, withTimeout(5_000) { importer.toastEvents.first() })
    }

    @Test fun onlyTheTrampolineReceivesSharedImagesAndItRunsOutsideRenkinsTask() {
        val context = RuntimeEnvironment.getApplication()
        val share = Intent(Intent.ACTION_SEND).setType("image/png").setPackage(context.packageName)

        val receivers = context.packageManager.queryIntentActivities(share, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.name }
        val trampoline = context.packageManager.getActivityInfo(ComponentName(context, ShareImportActivity::class.java), 0)

        assertEquals(listOf(ShareImportActivity::class.java.name), receivers)
        assertEquals("", trampoline.taskAffinity ?: "")
    }
}
