package dev.renkinProject.renkin

import android.app.Application
import android.net.Uri
import android.os.Bundle
import android.os.Parcel
import androidx.lifecycle.SavedStateHandle
import dev.renkinProject.renkin.apk.ApkExportLease
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class PendingApkExportStateTest {
    @Test
    fun pickerHandoffRestoresFromAParcelWithoutTheBuildSnapshot() {
        val handle = SavedStateHandle()
        val original = PendingApkExportState(handle)
        val lease = ApkExportLease(Uri.parse("file:///cache/apk/example/app-release.apk"), "export-token")
        original.lease = lease
        val saved = requireNotNull(handle.get<Bundle>("pendingApkExport"))
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(saved)
            parcel.setDataPosition(0)
            val restored = PendingApkExportState(SavedStateHandle(mapOf(
                "pendingApkExport" to requireNotNull(parcel.readBundle(javaClass.classLoader))
            )))
            assertEquals(lease, restored.lease)
            restored.lease = null
            assertNull(restored.lease)
        } finally { parcel.recycle() }
    }

    @Test
    fun missingOrIncompleteHandoffHasNoSourceToExport() {
        assertNull(PendingApkExportState(SavedStateHandle()).lease)
        val partial = Bundle().apply { putString("source", "file:///cache/apk/example.apk") }
        assertNull(PendingApkExportState(SavedStateHandle(mapOf("pendingApkExport" to partial))).lease)
    }
}
