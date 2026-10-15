package dev.renkinProject.renkin

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Bundle
import android.os.SystemClock
import android.os.UserManager
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.renkinProject.renkin.packages.InstalledAppCatalog
import dev.renkinProject.renkin.packages.InstalledIconMemorySnapshot
import dev.renkinProject.renkin.packages.inspectInstalledIconMemory
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking

/** Aggregate-only measurement; run with a backed-up debug app and an external meminfo sampler. */
@RunWith(AndroidJUnit4::class)
class InstalledIconMemoryDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test
    fun measureProductionReload() {
        check(context.packageName == "dev.renkinProject.renkin.dev")
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }) as MainActivity
        try {
            lateinit var viewModel: MainViewModel
            instrumentation.runOnMainSync { viewModel = ViewModelProvider(activity)[MainViewModel::class.java] }
            await { viewModel.runtimeState.value.ready }
            val appCount = viewModel.applicationList.size
            val createdCount = viewModel.applicationList.count { it.createdIcon != null }
            repeat(2) { index ->
                report("reload-$index-before", inspectInstalledIconMemory(viewModel.applicationList.map { it.icon }))
                val token = viewModel.runtimeState.value.sessionToken
                val started = SystemClock.elapsedRealtimeNanos()
                instrumentation.runOnMainSync { viewModel.refreshApps() }
                await {
                    val runtime = viewModel.runtimeState.value
                    runtime.ready && runtime.sessionToken != token &&
                        viewModel.applicationState.value.sessionToken == runtime.sessionToken
                }
                report("reload-$index-completed", inspectInstalledIconMemory(viewModel.applicationList.map { it.icon }),
                    (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0)
                assertEquals(token.profileId, viewModel.runtimeState.value.sessionToken.profileId)
                assertEquals(appCount, viewModel.applicationList.size)
                assertEquals(createdCount, viewModel.applicationList.count { it.createdIcon != null })
                Thread.sleep(6_000)
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test
    fun measureSourceAcquisitionAndRetainedCatalogOverlap() = runBlocking {
        check(context.packageName == "dev.renkinProject.renkin.dev")
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }) as MainActivity
        try {
            lateinit var viewModel: MainViewModel
            instrumentation.runOnMainSync { viewModel = ViewModelProvider(activity)[MainViewModel::class.java] }
            await { viewModel.runtimeState.value.ready }
            val original = viewModel.applicationState.value
            val originalIcons = original.applications.map { it.icon }
            report("live-catalog", inspectInstalledIconMemory(originalIcons))

            val started = SystemClock.elapsedRealtimeNanos()
            val candidate = InstalledAppCatalog(context).getAllInstalledApps()
            val loadedMs = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
            assertEquals(originalIcons.size, candidate.size)
            report("second-catalog", inspectInstalledIconMemory(candidate.map { it.icon }), loadedMs)
            report("retained-catalog-overlap", inspectInstalledIconMemory(originalIcons + candidate.map { it.icon }))
            // The explicit hold lets the external sampler observe this controlled ownership case.
            // It is not a claim that production always retains both catalogs this long.
            Thread.sleep(2_000)
            report("retained-catalog-overlap-end", inspectInstalledIconMemory(originalIcons + candidate.map { it.icon }))

            val users = context.getSystemService(Context.USER_SERVICE) as UserManager
            val launcher = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            val entries = users.userProfiles.flatMap { launcher.getActivityList(null, it) }
            repeat(2) { pass ->
                val durations = ArrayList<Double>(entries.size)
                var failures = 0
                var bitmaps = 0
                var bitmapBytes = 0L
                val wallStart = SystemClock.elapsedRealtimeNanos()
                entries.forEach { entry ->
                    val start = SystemClock.elapsedRealtimeNanos()
                    val icon = runCatching { entry.getIcon(context.resources.displayMetrics.densityDpi) }.getOrNull()
                    durations += (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
                    if (icon == null) failures++ else {
                        val inspected = inspectInstalledIconMemory(listOf(icon))
                        bitmaps += inspected.bitmaps
                        bitmapBytes += inspected.bitmapBytes
                    }
                }
                val wallMs = (SystemClock.elapsedRealtimeNanos() - wallStart) / 1_000_000.0
                durations.sort()
                fun percentile(fraction: Double) = durations[((durations.size - 1) * fraction).toInt()]
                assertTrue(durations.isNotEmpty())
                emit(JSONObject().put("phase", "warm-sequential-source-$pass")
                    .put("entries", entries.size).put("users", users.userProfiles.size)
                    .put("failures", failures).put("wallIncludingInspectionMs", wallMs)
                    .put("acquireTotalMs", durations.sum()).put("p50Ms", percentile(0.50))
                    .put("p95Ms", percentile(0.95)).put("maxMs", durations.last())
                    // Per-read sums deliberately do not deduplicate across acquisitions.
                    .put("perReadBitmapCountSum", bitmaps).put("perReadBitmapBytesSum", bitmapBytes))
            }
            assertTrue(viewModel.runtimeState.value.ready)
            assertEquals(original.sessionToken, viewModel.applicationState.value.sessionToken)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun report(phase: String, value: InstalledIconMemorySnapshot, loadMs: Double? = null) {
        val record = JSONObject().put("phase", phase).put("roots", value.roots)
            .put("drawables", value.drawables).put("bitmaps", value.bitmaps)
            .put("bitmapBytes", value.bitmapBytes).put("hardwareBytes", value.hardwareBytes)
            .put("adaptive", value.adaptiveDrawables).put("vectors", value.vectors)
            .put("opaque", value.opaqueContainers).put("unknown", value.unknownLeaves)
            .put("truncated", value.truncated).put("readFailures", value.readFailures)
        loadMs?.let { record.put("loadMs", it) }
        emit(record)
    }

    private fun emit(record: JSONObject) {
        instrumentation.sendStatus(0, Bundle().apply { putString("installedIconMemory", record.toString()) })
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) { "Catalog startup did not finish" }
            Thread.sleep(50)
        }
    }
}
