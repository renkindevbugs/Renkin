package dev.renkinProject.renkin.service

import android.app.Application
import android.content.Intent
import android.content.pm.ResolveInfo
import androidx.room.Room
import dev.renkinProject.renkin.data.StorageOperationGate
import dev.renkinProject.renkin.data.watch.AppComponent
import dev.renkinProject.renkin.data.watch.WatchDatabase
import dev.renkinProject.renkin.data.watch.WatchRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Resetter
import org.robolectric.shadows.ShadowApplicationPackageManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@Implements(className = "android.app.ApplicationPackageManager", isInAndroidSdk = false)
class WatchScanPackageManagerShadow : ShadowApplicationPackageManager() {
    companion object {
        @Volatile var beforeScan: (() -> Unit)? = null
        @JvmStatic @Resetter fun resetScan() { beforeScan = null }
    }

    @Implementation
    public override fun queryIntentActivities(intent: Intent, flags: Int): MutableList<ResolveInfo> {
        if (intent.action == "org.adw.launcher.THEMES") beforeScan?.invoke()
        return super.queryIntentActivities(intent, flags)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], shadows = [WatchScanPackageManagerShadow::class])
class WatchStorageConcurrencyTest {
    private lateinit var database: WatchDatabase
    private lateinit var repository: WatchRepository
    private lateinit var checker: WatchChecker
    private val scanned = CountDownLatch(1)
    private val resume = CountDownLatch(1)
    private val original = AppComponent("app.one", "Main")
    private val replacement = AppComponent("app.two", "Main")

    @Before fun setup() {
        StorageOperationGate.resetForTests()
        val context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, WatchDatabase::class.java).allowMainThreadQueries().build()
        repository = WatchRepository(database)
        checker = WatchChecker(context, repo = repository)
        WatchScanPackageManagerShadow.beforeScan = {
            scanned.countDown()
            check(resume.await(10, TimeUnit.SECONDS)) { "Watch scan was not released" }
        }
    }

    @After fun cleanup() {
        resume.countDown()
        WatchScanPackageManagerShadow.beforeScan = null
        StorageOperationGate.resetForTests()
        database.close()
    }

    @Test fun baselineScanAllowsWritersAndCannotOverwriteAnEditedRule() = runBlocking {
        val id = repository.saveRule(null, listOf(original), true, emptyList(), 1)
        val saving = async(Dispatchers.Default) { checker.saveRule(id, listOf(original), true, emptyList(), 1) }
        try {
            assertTrue(scanned.await(5, TimeUnit.SECONDS))
            withTimeout(2000) { repository.saveRule(id, listOf(replacement), true, emptyList(), 1) }
        } finally { resume.countDown() }
        assertEquals(0L, saving.await())
        assertEquals("app.two", repository.getRule(id)!!.apps.single().packageName)
    }

    @Test fun baselineFromBeforeRestoreCannotCreateRuleInReplacedStorage() = runBlocking {
        val saving = async(Dispatchers.Default) { checker.saveRule(null, listOf(original), true, emptyList(), 1) }
        try {
            assertTrue(scanned.await(5, TimeUnit.SECONDS))
            withTimeout(2000) { StorageOperationGate.run { StorageOperationGate.markStorageReplaced() } }
        } finally { resume.countDown() }
        assertEquals(0L, saving.await())
        assertTrue(repository.getAllRules().isEmpty())
    }

    @Test fun periodicScanDoesNotHoldStorageGateAndInvalidatesResultAfterRestore() = runBlocking {
        repository.saveRule(null, listOf(original), true, emptyList(), 1)
        val checking = async(Dispatchers.Default) { checker.runCheckResult() }
        try {
            assertTrue(scanned.await(5, TimeUnit.SECONDS))
            withTimeout(2000) {
                StorageOperationGate.run {
                    StorageOperationGate.markStorageReplaced()
                    repository.replaceAllRules(emptyList())
                }
            }
        } finally { resume.countDown() }
        val result = checking.await()
        StorageOperationGate.run { assertFalse(result.isCurrent()) }
        assertTrue(result.suggestions.isEmpty())
        assertTrue(repository.getAllRules().isEmpty())
    }
}
