package dev.renkinProject.renkin.data.transfer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import dev.renkinProject.renkin.MainActivity
import dev.renkinProject.renkin.MainViewModel
import dev.renkinProject.renkin.data.RenkinPackDatabase
import dev.renkinProject.renkin.data.RenkinPackRepository
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipFile
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Aggregate-only probes. Back up debug data first; preparation can update prefsSnapshot. */
@RunWith(AndroidJUnit4::class)
class BackupMemoryDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val repository get() = RenkinPackRepository(context)
    private val runtime get() = Runtime.getRuntime()

    private fun inspectionFixture() = File(context.cacheDir, "perf6b-inspection-fixture.renkin")

    @Test
    fun stageInspectionFixture() = withReadyApp { manager ->
        val file = inspectionFixture()
        check(!file.exists()) { "Refusing to replace an inspection fixture" }
        try {
            manager.exportBackup { file.outputStream() }
            emit(JSONObject().put("phase", "inspection-fixture").put("zipBytes", file.length()))
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
    }

    @Test
    fun measurePreparedBackupInspection() = withReadyApp(activateLargest = true) { manager ->
        val file = inspectionFixture()
        check(file.isFile)
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val fingerprint = digest.digest().joinToString("") { "%02x".format(it) }
        repeat(3) { index ->
            val prepared = measured("full-${index + 1}-prepare-import") { manager.prepareImport(Uri.fromFile(file)) }
            try {
                assertEquals(BackupManager.ImportKind.BACKUP, prepared.inspection.kind)
                assertEquals(fingerprint, prepared.inspection.fingerprint)
                emit(JSONObject().put("phase", "inspection-only-verified").put("trial", index + 1)
                    .put("zipBytes", file.length()).put("fingerprint", fingerprint)
                    .put("profiles", prepared.inspection.backup?.profiles?.size)
                    .put("icons", prepared.inspection.backup?.iconCount))
            } finally { manager.discardImport(prepared) }
            assertTrue(prepared.staged.entries.values.none { it.exists() })
            delay(500)
        }
    }

    @Test
    fun measureIdleReadyBaseline() = withReadyApp {
        measured("idle-ready-control") { delay(10_000) }
    }

    @Test
    fun measureLargestProfileExport() = withReadyApp { manager ->
        val largest = footprint()
        repeat(3) { trial -> export(manager, largest.first, largest.second, trial + 1) }
    }

    @Test
    fun measureFullBackupExport() = withReadyApp { manager ->
        footprint()
        repeat(3) { trial -> export(manager, null, null, trial + 1) }
    }

    @Test
    fun measureFullBackupWithLargestProfileActive() = withReadyApp(activateLargest = true) { manager ->
        repeat(3) { trial -> export(manager, null, null, trial + 1) }
    }

    @Test
    fun measureLargestProfileCodecOwnership() = withReadyApp {
        val largest = footprint()
        val data = measured("codec-read-rows") {
            BackupData(listOf(BackupProfile(requireNotNull(repository.profile(largest.first)),
                repository.getAll(largest.first), emptyList())), emptyMap())
        }
        inspectEncodedReferences(data)
        val file = File.createTempFile("backup-memory-codec-", ".json", context.cacheDir)
        try {
            measured("codec-write-file") {
                file.writer(Charsets.UTF_8).use { writeBackupData(data, it, BackupCodec.FORMAT_VERSION) }
            }
            emit(JSONObject().put("phase", "codec-file").put("jsonBytes", file.length()))
            // Deliberate old/new DTO overlap, separate from the real manager export probes.
            val decoded = measured("codec-read-with-original-retained") {
                file.reader(Charsets.UTF_8).use { readBackupData(it, BackupCodec.FORMAT_VERSION) }
            }
            val original = data.profiles.single().icons
            val restored = decoded.profiles.single().icons
            assertEquals(original.size, restored.size)
            assertTrue("Codec changed persisted artwork", original.zip(restored).all { (a, b) ->
                a.drawable == b.drawable && a.baseDrawable == b.baseDrawable
            })
            emit(JSONObject().put("phase", "codec-verified").put("icons", original.size)
                .put("sharedDecodedBases", restored.count { it.drawable === it.baseDrawable }))
        } finally { assertTrue(file.delete()) }
    }

    private suspend fun inspectEncodedReferences(data: BackupData) {
        measured("codec-encode-tree") {
            val tree = BackupCodec.encodeJson(data, BackupCodec.FORMAT_VERSION)
            val rows = tree.getJSONArray("profiles").getJSONObject(0).getJSONArray("icons")
            val source = data.profiles.single().icons
            val shared = source.indices.count { index ->
                rows.getJSONObject(index).getString("drawable") === source[index].drawable &&
                    rows.getJSONObject(index).getString("baseDrawable") === source[index].baseDrawable
            }
            assertEquals(source.size, shared)
            emit(JSONObject().put("phase", "tree-references").put("icons", source.size)
                .put("bothPayloadReferencesShared", shared))
        }
    }

    private suspend fun export(manager: BackupManager, profileId: Long?, expectedIcons: Int?, trial: Int) {
        val kind = if (profileId == null) "full" else "profile"
        val prefix = "$kind-$trial"
        emit(JSONObject().put("phase", "$prefix-before").put("heapUsedBytes", heapUsed()))
        delay(500)
        val prepared = measured("$prefix-prepare") {
            if (profileId == null) manager.prepareBackupExport() else manager.prepareProfileExport(profileId)
        }
        val directory = prepared.directory
        try {
            val archiveBytes = prepared.archive.length()
            ZipFile(prepared.archive).use { zip ->
                emit(JSONObject().put("phase", "$prefix-archive").put("zipBytes", archiveBytes)
                    .put("jsonBytes", requireNotNull(zip.getEntry("data.json")).size)
                    .put("entries", zip.size()).put("expectedIcons", expectedIcons))
            }
            val inspection = measured("$prefix-inspect") { manager.inspectFile { prepared.archive.inputStream() } }
            assertEquals(if (profileId == null) BackupManager.ImportKind.BACKUP else BackupManager.ImportKind.PROFILE,
                inspection.kind)
            val pending = measured("$prefix-prepare-import") { manager.prepareImport(Uri.fromFile(prepared.archive)) }
            try {
                assertTrue("Prepared inspection differs from direct inspection", inspection == pending.inspection)
            } finally { manager.discardImport(pending) }
            val expectedDigest = withContext(Dispatchers.IO) {
                val digest = MessageDigest.getInstance("SHA-256")
                prepared.archive.inputStream().use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                digest.digest()
            }
            val sink = CountingSink()
            measured("$prefix-transfer") { manager.writePreparedExport(prepared) { sink } }
            assertEquals(archiveBytes, sink.bytes)
            assertTrue("Immutable archive transfer changed bytes", expectedDigest.contentEquals(sink.digest.digest()))
            assertFalse(directory.exists())
            emit(JSONObject().put("phase", "$prefix-verified").put("copiedBytes", sink.bytes)
                .put("inspectedProfiles", inspection.backup?.profiles?.size)
                .put("inspectedIcons", inspection.backup?.iconCount).put("stagingReleased", true))
        } finally { manager.discardExport(prepared) }
        delay(500)
    }

    private fun withReadyApp(activateLargest: Boolean = false, operation: suspend (BackupManager) -> Unit) = runBlocking {
        check(context.packageName == "dev.renkinProject.renkin.dev")
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }) as MainActivity
        try {
            lateinit var viewModel: MainViewModel
            instrumentation.runOnMainSync { viewModel = ViewModelProvider(activity)[MainViewModel::class.java] }
            val deadline = SystemClock.elapsedRealtime() + 60_000
            while (!viewModel.runtimeState.value.ready && SystemClock.elapsedRealtime() < deadline) delay(100)
            check(viewModel.runtimeState.value.ready)
            if (activateLargest) {
                val largest = withContext(Dispatchers.IO) { footprint().first }
                instrumentation.runOnMainSync { viewModel.switchProfile(largest) }
                val switchDeadline = SystemClock.elapsedRealtime() + 60_000
                while (SystemClock.elapsedRealtime() < switchDeadline) {
                    val state = viewModel.runtimeState.value
                    if (state.ready && state.sessionToken.profileId == largest &&
                        viewModel.applicationState.value.sessionToken == state.sessionToken) break
                    delay(100)
                }
                check(viewModel.runtimeState.value.ready && viewModel.runtimeState.value.sessionToken.profileId == largest)
                delay(2_000)
            }
            emit(JSONObject().put("phase", "ready").put("maxHeapBytes", runtime.maxMemory())
                .put("activeProfileId", viewModel.runtimeState.value.sessionToken.profileId)
                .put("liveApps", viewModel.applicationList.size)
                .put("liveCreatedIcons", viewModel.applicationList.count { it.createdIcon != null }))
            val manager = EntryPointAccessors.fromApplication(context.applicationContext, BackupManagerEntryPoint::class.java)
                .backupManager()
            withContext(Dispatchers.IO) { operation(manager) }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    private fun footprint(): Pair<Long, Int> {
        val result = mutableListOf<Pair<Long, Int>>()
        val values = org.json.JSONArray()
        RenkinPackDatabase.get(context).openHelper.readableDatabase.query(
            "SELECT profileId, COUNT(*), SUM(length(CAST(drawable AS BLOB)) + length(CAST(baseDrawable AS BLOB))) " +
                "FROM DbApplication GROUP BY profileId ORDER BY 3 DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += cursor.getLong(0) to cursor.getInt(1)
                values.put(JSONObject().put("profileId", cursor.getLong(0)).put("icons", cursor.getInt(1))
                    .put("artworkUtf8Bytes", cursor.getLong(2)))
            }
        }
        emit(JSONObject().put("phase", "stored-footprint").put("profiles", values))
        return result.first()
    }

    private fun heapUsed() = runtime.totalMemory() - runtime.freeMemory()

    private suspend fun <T> measured(phase: String, operation: suspend () -> T): T = coroutineScope {
        val started = SystemClock.elapsedRealtimeNanos()
        val before = heapUsed()
        val peak = AtomicLong(before)
        val nativePeak = AtomicLong(Debug.getNativeHeapAllocatedSize())
        val samples = AtomicLong()
        val gcBefore = Debug.getRuntimeStats()
        emit(JSONObject().put("phase", "$phase-start"))
        val sampler = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            while (isActive) {
                peak.accumulateAndGet(heapUsed(), ::maxOf)
                nativePeak.accumulateAndGet(Debug.getNativeHeapAllocatedSize(), ::maxOf)
                samples.incrementAndGet()
                delay(10)
            }
        }
        try { operation() } finally {
            sampler.cancelAndJoin()
            val after = heapUsed()
            val stats = Debug.getRuntimeStats()
            fun delta(key: String) = (stats[key]?.toLongOrNull() ?: 0) - (gcBefore[key]?.toLongOrNull() ?: 0)
            emit(JSONObject().put("phase", phase)
                .put("elapsedMs", (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0)
                .put("heapBeforeBytes", before).put("heapAfterBytes", after)
                .put("sampledHeapPeakBytes", maxOf(peak.get(), after)).put("sampledNativePeakBytes", nativePeak.get())
                .put("samples", samples.get()).put("allocatedBytes", delta("art.gc.bytes-allocated"))
                .put("gcCount", delta("art.gc.gc-count")).put("gcTimeMs", delta("art.gc.gc-time")))
        }
    }

    private fun emit(value: JSONObject) {
        value.put("elapsedRealtimeMs", SystemClock.elapsedRealtime())
        instrumentation.sendStatus(0, Bundle().apply { putString("backupMemory", value.toString()) })
    }

    private class CountingSink : OutputStream() {
        var bytes = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        override fun write(value: Int) { bytes++; digest.update(value.toByte()) }
        override fun write(buffer: ByteArray, offset: Int, count: Int) {
            bytes += count
            digest.update(buffer, offset, count)
        }
    }
}
