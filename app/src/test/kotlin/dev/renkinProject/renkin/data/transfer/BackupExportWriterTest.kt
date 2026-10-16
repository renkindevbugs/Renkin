package dev.renkinProject.renkin.data.transfer

import android.app.Application
import dev.renkinProject.renkin.data.DbApplication
import dev.renkinProject.renkin.data.Profile
import dev.renkinProject.renkin.data.ProfileStyle
import dev.renkinProject.renkin.data.ProfileStyleCodec
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.io.StringWriter

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class BackupExportWriterTest {
    private fun fixture(): BackupData {
        val rows = listOf(
            DbApplication("app.one", "Main", true, true, "rendered\"\n\\".repeat(20_000),
                calendarEnabled = true, calendarPrefix = "cal_", calendarPackName = "pack.calendar",
                sourcePackName = "pack.source", sourceDrawableName = "chosen", isCustomIcon = true,
                isLegacyIcon = true, baseDrawable = "base\n\t".repeat(40_000), baseIsAdaptiveIcon = true,
                baseIsXml = true, sourceUrl = "https://example.org/icon", isFallbackIcon = true,
                modifierRecipe = "invalid optional", modifierInput = "invalid optional"),
            DbApplication("app.two", "Other", false, false, "", baseDrawable = "")
        )
        val style = ProfileStyleCodec.encode(ProfileStyle())
        return BackupData(listOf(
            BackupProfile(Profile(id = 1, name = "First", profileStyle = style, prefsSnapshot = "{}"), rows, emptyList()),
            BackupProfile(Profile(id = 7, name = "Empty", profileStyle = style, prefsSnapshot = "{}"), emptyList(), emptyList()),
            BackupProfile(Profile(id = 9, name = "Last", profileStyle = style, prefsSnapshot = "{}"),
                listOf(rows.first().copy(profileId = 9)), emptyList())
        ), mapOf("custom" to BackupPref(BackupPref.STRING_SET, setOf("one", "two"))),
            packLabels = mapOf("pack.source" to "Source"))
    }

    @Test fun formatsAndExactEscapedArtworkMatchTheSharedCodec() = runBlocking {
        val data = fixture()
        val metadata = data.copy(profiles = data.profiles.map { it.copy(icons = emptyList()) })
        for (format in 1..4) {
            val output = StringWriter()
            val visited = mutableListOf<Long>()
            writeBackupExport(metadata, output, format) { id, consume ->
                visited += id
                data.profiles.single { it.profile.id == id }.icons.forEach { consume(it) }
            }
            assertEquals(data.profiles.map { it.profile.id }, visited)
            val expected = StringWriter()
            writeBackupData(data, expected, format)
            assertEquals(expected.toString(), output.toString())
            assertEquals(BackupCodec.decode(BackupCodec.encode(data, format), format),
                readBackupData(output.toString().reader(), format))
        }
    }

    @Test fun streamedIdentityChecksRejectBadRowsAndDuplicates() = runBlocking {
        val data = fixture().let { it.copy(profiles = listOf(it.profiles.first().copy(icons = emptyList()))) }
        val row = fixture().profiles.first().icons.first()
        for (rows in listOf(listOf(row.copy(packageName = " ")), listOf(row.copy(activityName = "")),
            listOf(row.copy(profileId = 9)), listOf(row, row))) {
            val result = runCatching {
                writeBackupExport(data, StringWriter(), 4) { _, consume -> rows.forEach { consume(it) } }
            }
            assertTrue(result.exceptionOrNull() is IOException)
        }
    }

    @Test fun metadataMustNotOwnArtworkRows() = runBlocking {
        assertTrue(runCatching { writeBackupExport(fixture(), StringWriter(), 4) { _, _ -> fail() } }
            .exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun cancellationStopsBeforeAnotherRowIsSerialized() = runBlocking {
        val data = fixture().let { it.copy(profiles = listOf(it.profiles.first().copy(icons = emptyList()))) }
        var rows = 0
        val task = launch {
            writeBackupExport(data, StringWriter(), 4) { _, consume ->
                consume(fixture().profiles.first().icons.first())
                rows++
                currentCoroutineContext().job.cancel()
                consume(fixture().profiles.first().icons.last())
                fail("Cancelled export completed")
            }
        }
        task.join()
        assertTrue(task.isCancelled)
        assertEquals(1, rows)
    }
}
