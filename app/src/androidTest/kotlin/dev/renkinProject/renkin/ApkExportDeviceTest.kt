package dev.renkinProject.renkin

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcel
import android.provider.DocumentsContract
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.MutableState
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.renkinProject.renkin.apk.APK_MIME_TYPE
import dev.renkinProject.renkin.apk.ApkBuildArtifacts
import dev.renkinProject.renkin.apk.ApkInstallResult
import dev.renkinProject.renkin.apk.BuiltIconPack
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking

/** Run only on a backed-up debug app; the picker tests create and remove their own documents. */
@RunWith(AndroidJUnit4::class)
class ApkExportDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val fixtureBytes = "Renkin export regression fixture".toByteArray()

    @Test
    fun restoredPickerReservationSurvivesAnotherBuildAndCopiesExactBytes() = runBlocking {
        val cache = File(context.cacheDir, "export-device-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val artifacts = ApkBuildArtifacts(cache)
            val directory = artifacts.create()
            val source = directory.resolve("app-release.apk").apply { writeBytes(fixtureBytes) }
            artifacts.release(Uri.fromFile(source), completed = true)
            val handle = SavedStateHandle()
            PendingApkExportState(handle).lease = requireNotNull(artifacts.reserveExport(Uri.fromFile(source)))
            val parcel = Parcel.obtain()
            val restored = try {
                parcel.writeBundle(requireNotNull(handle.get<Bundle>("pendingApkExport")))
                parcel.setDataPosition(0)
                PendingApkExportState(SavedStateHandle(mapOf(
                    "pendingApkExport" to requireNotNull(parcel.readBundle(javaClass.classLoader))
                )))
            } finally { parcel.recycle() }
            val restarted = ApkBuildArtifacts(cache)
            restarted.create()
            assertTrue("Picker must retain a completed APK across a restart and prune", source.isFile)
            val output = ByteArrayOutputStream()
            assertTrue(restarted.export(requireNotNull(restored.lease)) { output })
            assertArrayEquals(fixtureBytes, output.toByteArray())
            restarted.create()
            assertFalse(source.exists())
        } finally { cache.deleteRecursively() }
    }

    @Test
    fun documentPickerExportsTheReservedApk() = withFailureFixture { activity, viewModel, source ->
        val target = AtomicReference<Uri?>()
        val launcher = activity.activityResultRegistry.register(
            "export-device-${UUID.randomUUID()}", ActivityResultContracts.CreateDocument(APK_MIME_TYPE)
        ) { uri ->
            target.set(uri)
            viewModel.finishFailedApkExport(uri)
        }
        try {
            instrumentation.runOnMainSync {
                viewModel.beginFailedApkExport { launcher.launch("Renkin-export-device-${UUID.randomUUID()}.apk") }
            }
            await("Android document picker") { pickerIsVisible() }
            assertTrue(source.parentFile!!.listFiles()!!.any { it.name.startsWith(".export-") })
            val anotherBuild = ApkBuildArtifacts(context.cacheDir).create()
            assertTrue("APK must remain while the picker is open", source.isFile)
            clickSave()
            await("picker result") { target.get() != null }
            val uri = requireNotNull(target.get())
            await("export completion") { source.parentFile!!.listFiles()!!.none { it.name.startsWith(".export-") } }
            assertArrayEquals(fixtureBytes, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
            assertTrue(DocumentsContract.deleteDocument(context.contentResolver, uri))
            anotherBuild.deleteRecursively()
        } finally { instrumentation.runOnMainSync { launcher.unregister() } }
    }

    @Test
    fun cancellingDocumentPickerReleasesItsReservation() = withFailureFixture { _, viewModel, source ->
        await("Save APK action in Renkin") {
            val root = instrumentation.uiAutomation.rootInActiveWindow ?: return@await false
            if (root.packageName?.toString() != context.packageName) return@await false
            val label = findNode(root) { it.text?.toString() == "Save APK" } ?: return@await false
            click(label)
        }
        await("Android document picker") { pickerIsVisible() }
        assertTrue(source.parentFile!!.listFiles()!!.any { it.name.startsWith(".export-") })
        dismissPicker()
        await("cancelled picker cleanup") { source.parentFile!!.listFiles()!!.none { it.name.startsWith(".export-") } }
        assertTrue("Cancelling only releases the reservation", source.isFile)
        assertNotNull("The failure report remains usable", viewModel.installFailure)
    }

    @Test
    fun orphanPickerResultRemovesTheEmptyDocument() = withFailureFixture { activity, viewModel, _ ->
        withPickerResult(activity, viewModel, reserve = false) { }
    }

    @Test
    fun missingSourceRemovesTheEmptyDocumentAndReleasesItsReservation() = withFailureFixture { activity, viewModel, source ->
        withPickerResult(activity, viewModel, reserve = true) { assertTrue(source.delete()) }
        assertTrue(source.parentFile!!.listFiles()!!.none { it.name.startsWith(".export-") })
    }

    private fun withPickerResult(activity: MainActivity, viewModel: MainViewModel, reserve: Boolean, beforeSave: () -> Unit) {
        val target = AtomicReference<Uri?>()
        val launcher = activity.activityResultRegistry.register(
            "export-missing-${UUID.randomUUID()}", ActivityResultContracts.CreateDocument(APK_MIME_TYPE)
        ) { uri ->
            uri?.let {
                assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED,
                    context.checkUriPermission(it, android.os.Process.myPid(), android.os.Process.myUid(),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }
            target.set(uri)
            viewModel.finishFailedApkExport(uri)
        }
        try {
            instrumentation.runOnMainSync {
                val open: (String) -> Unit = { launcher.launch("Renkin-export-missing-${UUID.randomUUID()}.apk") }
                if (reserve) viewModel.beginFailedApkExport(open) else open("")
            }
            await("Android document picker") { pickerIsVisible() }
            beforeSave()
            clickSave()
            await("picker result") { target.get() != null }
            await("empty document removal") {
                try {
                    context.contentResolver.openInputStream(requireNotNull(target.get()))?.use { false } ?: true
                } catch (_: java.io.FileNotFoundException) { true }
                catch (_: SecurityException) {
                    // ExternalStorageProvider revokes the document grant when it deletes the file.
                    context.checkUriPermission(requireNotNull(target.get()), android.os.Process.myPid(),
                        android.os.Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION) ==
                        android.content.pm.PackageManager.PERMISSION_DENIED
                }
            }
        } finally { instrumentation.runOnMainSync { launcher.unregister() } }
    }

    private fun withFailureFixture(test: (MainActivity, MainViewModel, File) -> Unit) {
        check(context.packageName == "dev.renkinProject.renkin.dev")
        val automation = instrumentation.uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }) as MainActivity
        lateinit var viewModel: MainViewModel
        instrumentation.runOnMainSync { viewModel = ViewModelProvider(activity)[MainViewModel::class.java] }
        await("Renkin startup") { viewModel.runtimeState.value.startupComplete }
        val directory = File(context.cacheDir, "apk/${UUID.randomUUID()}").apply { mkdirs() }
        val source = directory.resolve("app-release.apk").apply { writeBytes(fixtureBytes) }
        directory.resolve(".finished").createNewFile()
        val pack = BuiltIconPack(Uri.fromFile(source), "dev.renkin.export.fixture", true, 1L,
            "Export device fixture", emptyPreferences(), emptyList(), emptyList())
        instrumentation.runOnMainSync {
            val coordinatorField = MainViewModel::class.java.getDeclaredField("buildCoordinator").apply { isAccessible = true }
            val coordinator = coordinatorField.get(viewModel) as BuildInstallCoordinator
            val stateField = BuildInstallCoordinator::class.java.getDeclaredField("state\$delegate").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val state = stateField.get(coordinator) as MutableState<BuildInstallState>
            state.value = BuildInstallState.Failed(R.string.iconPackInstallFailed, "Device export fixture",
                "Device export fixture", ApkInstallResult.TIMEOUT, pack)
        }
        try { test(activity, viewModel, source) } finally {
            dismissPicker()
            instrumentation.runOnMainSync { activity.finish() }
            directory.deleteRecursively()
        }
    }

    private fun pickerIsVisible(): Boolean = instrumentation.uiAutomation.rootInActiveWindow
        ?.packageName?.toString()?.endsWith("documentsui") == true

    private fun clickSave() {
        await("picker save button") {
            val root = instrumentation.uiAutomation.rootInActiveWindow ?: return@await false
            val match = findNode(root) { it.viewIdResourceName == "android:id/button1" && it.isEnabled }
                ?: return@await false
            click(match)
        }
    }

    private fun click(node: AccessibilityNodeInfo): Boolean {
        var button = node
        while (!button.isClickable) button = button.parent ?: return false
        return button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun dismissPicker() {
        repeat(8) {
            if (!pickerIsVisible()) return
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            Thread.sleep(300)
        }
        check(!pickerIsVisible()) { "Document picker did not close" }
    }

    private fun findNode(node: AccessibilityNodeInfo, matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (matches(node)) return node
        repeat(node.childCount) { index -> node.getChild(index)?.let { child -> findNode(child, matches)?.let { return it } } }
        return null
    }

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 30_000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        error("Timed out waiting for $description")
    }
}
