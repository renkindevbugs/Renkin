package dev.renkinProject.renkin.apk

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import dev.renkinProject.renkin.data.UnsavedSessionMarker
import dev.renkinProject.renkin.data.UnsavedSessionMarkerStore
import dev.renkinProject.renkin.drawable.BitmapIconDrawable
import dev.renkinProject.renkin.packages.PackageInfoStruct
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class UnsavedSessionMonitorTest {
    private val context = RuntimeEnvironment.getApplication()
    private val store = UnsavedSessionMarkerStore(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before fun clearMarker() {
        File(context.noBackupFilesDir, "unsaved-session.json").delete()
    }

    @After fun stopMonitor() = scope.cancel()

    private fun app(name: String, refreshMade: Boolean = false, withIcon: Boolean = true) = PackageInfoStruct(
        appName = name,
        packageName = "com.$name",
        activityName = "com.$name.Main",
        icon = ColorDrawable(Color.RED),
        iconID = 0,
        createdIcon = if (withIcon) BitmapIconDrawable(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888), false) else null,
        isRefreshMade = refreshMade
    )

    private fun state(
        instance: String,
        applications: List<PackageInfoStruct>,
        unsaved: Set<String>,
        loaded: Boolean = true
    ) = ApplicationListState(
        revision = 1,
        applications = persistentListOf<PackageInfoStruct>().addAll(applications),
        applicationsLoaded = loaded,
        sessionToken = ProfileSessionToken(7, 0, instance),
        unsavedIconKeys = unsaved
    )

    private fun monitor(instance: String, states: MutableStateFlow<ApplicationListState>) =
        UnsavedSessionMonitor(store, { instance }, states, scope)

    private suspend fun awaitStored(expected: UnsavedSessionMarker) {
        var last: UnsavedSessionMarker? = null
        val matched = kotlinx.coroutines.withTimeoutOrNull(5_000) {
            while (store.read().also { last = it } != expected) delay(10)
        }
        if (matched == null) throw AssertionError("expected $expected, stored $last")
    }

    @Test fun refreshOutputCountsAsRefreshedEvenThoughItIsDirty() {
        val picked = app("picked")
        val refreshed = app("refreshed", refreshMade = true)
        val emptied = app("emptied", refreshMade = true, withIcon = false)
        val marker = state(
            "process", listOf(picked, refreshed, emptied),
            unsaved = setOf(picked.key, refreshed.key, emptied.key, "com.gone/com.gone.Main")
        ).unsavedSessionMarker()

        assertEquals(UnsavedSessionMarker("process", 7, editedCount = 2, refreshedCount = 1), marker)
    }

    @Test fun nothingIsRecordedBeforeApplicationsLoad() {
        assertNull(state("process", listOf(app("a")), setOf(app("a").key), loaded = false).unsavedSessionMarker())
    }

    @Test fun markerFromAnEndedProcessIsReportedBeforeBeingReplaced() = runBlocking {
        val lost = UnsavedSessionMarker("old", 7, editedCount = 1, refreshedCount = 45)
        store.write(lost)
        val states = MutableStateFlow(state("new", listOf(app("a")), emptySet()))
        val monitor = monitor("new", states)

        monitor.start()

        assertEquals(lost, withTimeout(5_000) { monitor.lostSession.first { it != null } })
        awaitStored(UnsavedSessionMarker("new", 7, 0, 0))
        monitor.dismiss()
        assertNull(monitor.lostSession.value)
    }

    @Test fun sameProcessMarkerIsNotReported() = runBlocking {
        store.write(UnsavedSessionMarker("same", 7, editedCount = 3, refreshedCount = 0))
        val picked = app("picked")
        val states = MutableStateFlow(state("same", listOf(picked), setOf(picked.key)))
        val monitor = monitor("same", states)

        monitor.start()
        awaitStored(UnsavedSessionMarker("same", 7, 1, 0))

        assertNull(monitor.lostSession.value)
    }

    @Test fun markerWithoutChangesIsNotReported() = runBlocking {
        store.write(UnsavedSessionMarker("old", 7, 0, 0))
        val states = MutableStateFlow(state("new", listOf(app("a")), emptySet()))
        val monitor = monitor("new", states)

        monitor.start()
        awaitStored(UnsavedSessionMarker("new", 7, 0, 0))

        assertNull(monitor.lostSession.value)
    }

    @Test fun laterEditsReplaceTheRecordedCounts() = runBlocking {
        val picked = app("picked")
        val refreshed = app("refreshed", refreshMade = true)
        val states = MutableStateFlow(state("new", listOf(picked, refreshed), emptySet()))
        monitor("new", states).start()
        awaitStored(UnsavedSessionMarker("new", 7, 0, 1))

        states.value = state("new", listOf(picked, refreshed), setOf(picked.key, refreshed.key))
        awaitStored(UnsavedSessionMarker("new", 7, 1, 1))
        states.value = state("new", listOf(picked, refreshed.locked()), emptySet())
        awaitStored(UnsavedSessionMarker("new", 7, 0, 0))
    }

    @Test fun unreadableMarkerIsIgnored() = runBlocking {
        File(context.noBackupFilesDir, "unsaved-session.json").writeText("{not json")

        assertNull(store.read())
    }
}
