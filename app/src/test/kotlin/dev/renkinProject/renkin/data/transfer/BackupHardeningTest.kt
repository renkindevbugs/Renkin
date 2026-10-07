package dev.renkinProject.renkin.data.transfer

import android.app.Application
import dev.renkinProject.renkin.data.DbApplication
import dev.renkinProject.renkin.data.Profile
import dev.renkinProject.renkin.data.watch.AppComponent
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.StringWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33], shadows = [BackupOsShadow::class])
class BackupHardeningTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var root: File

    @Before fun setUp() {
        root = temporary.root
        ShadowStatFs.registerStats(root, 1_000_000, 1_000_000, 1_000_000)
    }

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            for ((name, text) in entries) {
                val bytes = text.toByteArray()
                val entry = ZipEntry(name).apply {
                    method = ZipEntry.STORED
                    size = bytes.size.toLong()
                    compressedSize = size
                    crc = CRC32().apply { update(bytes) }.value
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private suspend fun read(bytes: ByteArray) = StagedBackupArchive.read(root) { ByteArrayInputStream(bytes) }

    private fun sampleData(rules: List<BackupWatchRule> = emptyList()) = BackupData(
        listOf(BackupProfile(Profile(id = 1, name = "Saved"), listOf(
            DbApplication("app.one", "Main", false, false, "payload", baseDrawable = "payload")
        ), rules)), emptyMap()
    )

    @Test fun archive_fingerprintAndCleanupUseExactSourceBytes() = runBlocking {
        val bytes = zip("data.json" to "{}")
        read(bytes).use { archive ->
            assertEquals("{}", archive.entries.getValue("data.json").readText())
            assertEquals(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, archive.fingerprint)
        }
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test fun archive_rejectsTruncatedCentralDirectoryAndCleansStaging() = runBlocking {
        val bytes = zip("data.json" to "{}")
        assertTrue(runCatching { read(bytes.copyOf(bytes.size - 22)) }.exceptionOrNull() is IOException)
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test fun archive_rejectsActualCrcMismatch() = runBlocking {
        val payload = "integrity-payload"
        val bytes = zip("blob" to payload)
        val offset = bytes.toString(Charsets.ISO_8859_1).indexOf(payload)
        assertTrue(offset >= 0)
        bytes[offset] = (bytes[offset].toInt() xor 1).toByte()
        assertTrue(runCatching { read(bytes) }.exceptionOrNull() is IOException)
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test fun archive_rejectsOversizedDeclaredEntryBeforeAllocation() = runBlocking {
        val bytes = zip("blob" to "small")
        val central = bytes.toString(Charsets.ISO_8859_1).indexOf("PK\u0001\u0002")
        assertTrue(central >= 0)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(central + 24, (BackupArchiveLimits.MAX_ENTRY_BYTES + 1).toInt())
        assertTrue(runCatching { read(bytes) }.exceptionOrNull() is BackupSizeException)
    }

    @Test fun archive_rejectsUnsafeNamesAndNormalizedDuplicates() = runBlocking {
        for (name in listOf("../escape", "/absolute", "a/../b", "a//b", "a\\b", "C:drive", "control\n")) {
            assertTrue(name, runCatching { read(zip(name to "x")) }.exceptionOrNull() is IOException)
        }
        assertTrue(runCatching { read(zip("same/" to "", "same" to "x")) }.exceptionOrNull() is IOException)
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test fun archive_cancellationCleansPartialCopy() = runBlocking {
        val bytes = zip("data.json" to "{}")
        val job = launch {
            val self = currentCoroutineContext().job
            StagedBackupArchive.read(root) {
                object : ByteArrayInputStream(bytes) {
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        val count = super.read(buffer, offset, length)
                        self.cancel()
                        return count
                    }
                }
            }.close()
        }
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test fun archive_lowSpaceFailsBeforeOpeningSource() = runBlocking {
        ShadowStatFs.registerStats(root, 1024, 1024, 1024)
        var opened = false
        val error = runCatching { StagedBackupArchive.read(root) { opened = true; ByteArrayInputStream(byteArrayOf()) } }.exceptionOrNull()
        assertTrue(error is BackupSpaceException)
        assertFalse(opened)
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test fun stream_roundTripPreservesPayloadAndSharesIdenticalBases() = runBlocking {
        val original = sampleData().copy(prefs = mapOf("LONG" to BackupPref(BackupPref.LONG, 7_000_000_000L)))
        val output = StringWriter()
        writeBackupData(original, output, BackupCodec.LEGACY_FORMAT_VERSION)
        val decoded = readBackupData(output.toString().reader(), BackupCodec.LEGACY_FORMAT_VERSION)
        assertEquals(original, decoded)
        val icon = decoded.profiles.single().icons.single()
        assertSame(icon.drawable, icon.baseDrawable)
    }

    @Test fun stream_rejectsDuplicateFieldsDepthNonfiniteNumbersAndTrailingContent() = runBlocking {
        val valid = BackupCodec.encode(sampleData(), BackupCodec.LEGACY_FORMAT_VERSION)
        val malformed = listOf(
            "{\"prefs\":{},\"prefs\":{},\"profiles\":[]}",
            "{\"unknown\":{\"a\":1,\"\\u0061\":2},\"prefs\":{},\"profiles\":[]}",
            "{\"unknown\":" + "[".repeat(66) + "0" + "]".repeat(66) + ",\"prefs\":{},\"profiles\":[]}",
            "{\"unknown\":1e999,\"prefs\":{},\"profiles\":[]}",
            "$valid {}"
        )
        for (json in malformed) assertTrue(runCatching { readBackupData(json.reader(), BackupCodec.LEGACY_FORMAT_VERSION) }.isFailure)
    }

    @Test fun validator_rejectsCoreIdentityAndPreferenceErrors() {
        val data = sampleData()
        val profile = data.profiles.single()
        val invalid = listOf(
            data.copy(profiles = listOf(profile, profile)),
            data.copy(profiles = listOf(profile.copy(icons = profile.icons + profile.icons))),
            data.copy(profiles = listOf(profile.copy(icons = profile.icons.map { it.copy(profileId = 2) }))),
            data.copy(profiles = listOf(profile.copy(profile = profile.profile.copy(id = 0)))),
            data.copy(profiles = listOf(profile.copy(profile = profile.profile.copy(prefsSnapshot = "{\"PRIMARY_SOURCE\":\"bad\"}"))))
        )
        invalid.forEach { assertTrue(runCatching { BackupValidator.prepare(it, BackupManager.ImportKind.BACKUP, BackupCodec.LEGACY_FORMAT_VERSION) }.exceptionOrNull() is IOException) }
    }

    @Test fun validator_discardsStaleWatchChildrenAndCompletedRuleCascade() {
        val app = AppComponent("app.one", "Main")
        val valid = BackupWatchCandidate("pack.one", "icon", "hash")
        val rule = BackupWatchRule(false, false, 1, null, listOf(app), listOf("pack.one"),
            baselines = listOf(BackupWatchState("removed.app", "Main", "pack.one", 1, null, null, 1)),
            suggestions = listOf(
                BackupWatchSuggestion("removed.app", "Main", 1, listOf(valid)),
                BackupWatchSuggestion(app.packageName, app.activityName, 2, listOf(valid, valid.copy(iconHash = "")))
            ))
        val completed = rule.copy(completed = true, suggestions = listOf(
            BackupWatchSuggestion(app.packageName, app.activityName, 3, listOf(valid.copy(iconHash = "")))
        ))
        val cleaned = BackupValidator.prepare(sampleData(listOf(rule, completed)), BackupManager.ImportKind.BACKUP, BackupCodec.LEGACY_FORMAT_VERSION)
        val remaining = cleaned.profiles.single().watchRules.single()
        assertTrue(remaining.baselines.isEmpty())
        assertEquals(listOf(valid), remaining.suggestions.single().candidates)
        assertEquals(2, rule.suggestions.size)
    }

    @Test fun staging_startupRemovesAbandonedButProtectsLiveOwnersAndOriginals() {
        val imports = File(root, "backup-imports").apply { mkdirs() }
        val stale = File(imports, "backup-import-abandoned").apply { mkdirs(); resolve("payload").writeText("stale") }
        val live = BackupStaging.create(imports, "backup-export-")
        val originals = File(root, "backup-file-commit-abandoned").apply { mkdirs(); resolve("old-0").writeText("original") }
        try {
            BackupStaging.cleanAfterProcessStart(root)
            assertFalse(stale.exists())
            assertTrue(live.isDirectory)
            assertEquals("original", originals.resolve("old-0").readText())
        } finally { BackupStaging.release(live) }
    }

    @Test fun fileTransaction_applyAndRollbackRestoreReplacementDeletionAndNewFile() = runBlocking {
        val replace = root.resolve("replace").apply { writeText("original") }
        val remove = root.resolve("remove").apply { writeText("keep") }
        val create = root.resolve("create")
        val source = root.resolve("source").apply { writeText("new") }
        val newSource = root.resolve("new-source").apply { writeText("created") }
        BackupFileTransaction.prepare(root, linkedMapOf(replace to source, remove to null, create to newSource)).use { transaction ->
            assertEquals("original", replace.readText())
            assertFalse(source.exists())
            transaction.apply()
            assertEquals("new", replace.readText())
            assertFalse(remove.exists())
            assertEquals("created", create.readText())
            transaction.rollback()
        }
        assertEquals("original", replace.readText())
        assertEquals("keep", remove.readText())
        assertFalse(create.exists())
        assertTrue(root.listFiles().orEmpty().none { it.name.startsWith("backup-file-commit-") })
    }

    @Test fun fileTransaction_midApplyFailureRollsBackEarlierFiles() = runBlocking {
        val first = root.resolve("first").apply { writeText("old") }
        val parent = root.resolve("missing-parent").apply { mkdirs() }
        val second = parent.resolve("second")
        val source = root.resolve("source").apply { writeText("new") }
        val other = root.resolve("other").apply { writeText("other") }
        BackupFileTransaction.prepare(root, linkedMapOf(first to source, second to other)).use { transaction ->
            assertTrue(parent.delete())
            assertTrue(runCatching { transaction.apply() }.exceptionOrNull() is IOException)
            transaction.rollback()
        }
        assertEquals("old", first.readText())
    }

    @Test fun fileTransaction_failedRollbackPreservesOriginalBatch() = runBlocking {
        val parent = root.resolve("target-parent").apply { mkdirs() }
        val target = parent.resolve("icon").apply { writeText("original") }
        val source = root.resolve("source").apply { writeText("new") }
        BackupFileTransaction.prepare(root, mapOf(target to source)).use { transaction ->
            transaction.apply()
            assertTrue(target.delete())
            assertTrue(parent.delete())
            assertTrue(runCatching { transaction.rollback() }.exceptionOrNull() is IOException)
        }
        val batch = root.listFiles().orEmpty().single { it.name.startsWith("backup-file-commit-") }
        assertEquals("original", batch.resolve("old-0").readText())
    }
}
