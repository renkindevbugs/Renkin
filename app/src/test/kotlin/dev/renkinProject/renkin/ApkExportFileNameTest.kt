package dev.renkinProject.renkin

import android.app.Application
import android.net.Uri
import androidx.datastore.preferences.core.emptyPreferences
import dev.renkinProject.renkin.apk.BuiltIconPack
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class ApkExportFileNameTest {
    private fun pack(label: String) = BuiltIconPack(
        uri = Uri.EMPTY, packageName = "dev.renkinProject.renkinpack.p7", canBeInstalled = true,
        profileId = 7, packLabel = label, preferences = emptyPreferences(),
        profileApps = emptyList(), preservedRows = emptyList()
    )

    @Test
    fun readableLabelsKeepLettersDigitsAndSpaces() {
        assertEquals("Mello Icons 2.apk", apkExportFileName(pack("Mello Icons 2")))
        assertEquals("Ikony čičo.apk", apkExportFileName(pack("Ikony čičo")))
    }

    @Test
    fun pathAndReservedCharactersCannotEscapeTheFileName() {
        assertEquals("a_b_c.apk", apkExportFileName(pack("a/b:c")))
        assertEquals("dots.apk", apkExportFileName(pack("..dots..")))
    }

    @Test
    fun emptyLabelFallsBackToThePackageName() {
        assertEquals("dev.renkinProject.renkinpack.p7.apk", apkExportFileName(pack("  /  ")))
    }
}
