package dev.renkinProject.renkin.apk

import android.app.Application
import android.content.pm.PackageInfo
import android.net.Uri
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import dev.renkinProject.renkin.data.RenkinPackDatabase
import dev.renkinProject.renkin.data.RenkinPackRepository
import dev.renkinProject.renkin.data.StorageOperationGate
import dev.renkinProject.renkin.data.watch.WatchDatabase
import dev.renkinProject.renkin.data.watch.WatchRepository
import dev.renkinProject.renkin.packages.IconPackCatalog
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class CustomPackInstallGuardTest {
    private lateinit var context: Application
    private lateinit var database: RenkinPackDatabase
    private lateinit var watchDatabase: WatchDatabase
    private lateinit var service: IconPackBuildService

    @Before
    fun setUp() {
        StorageOperationGate.resetForTests()
        context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, RenkinPackDatabase::class.java)
            .allowMainThreadQueries().build()
        watchDatabase = Room.inMemoryDatabaseBuilder(context, WatchDatabase::class.java)
            .allowMainThreadQueries().build()
        val repository = RenkinPackRepository(database)
        val catalog = IconPackCatalog(context)
        val icons = IconPackRepository(context, catalog)
        service = IconPackBuildService(context, icons, repository, IconLockManager(context, repository),
            ProfileManager(context, repository, WatchRepository(watchDatabase)), catalog)
    }

    @After
    fun closeDatabases() {
        database.close()
        watchDatabase.close()
        StorageOperationGate.resetForTests()
    }

    @Test
    fun installNeverOffersReplaceForAnOccupiedForeignCustomId() = runBlocking {
        val name = "example.another.application"
        shadowOf(context.packageManager).installPackage(PackageInfo().apply { packageName = name })
        val outcome = withTimeout(5_000) { service.install(pack(name)) }
        assertEquals(ApkInstallResult.BLOCKED, outcome.result)
        assertNotNull(outcome.detail)
        assertNotNull(context.packageManager.getPackageInfo(name, 0))
        assertNull(shadowOf(context).nextStartedActivity)
    }

    @Test
    fun replaceRechecksForeignOwnershipIncludingCustomNamesUnderTheLegacyPrefix() = runBlocking {
        val name = "${IconPackBuilder.PACKAGE_NAME}.custom"
        shadowOf(context.packageManager).installPackage(PackageInfo().apply { packageName = name })
        val outcome = withTimeout(5_000) { service.replace(pack(name)) }
        assertEquals(ApkInstallResult.BLOCKED, outcome.result)
        assertNotNull(context.packageManager.getPackageInfo(name, 0))
        assertNull(shadowOf(context).nextStartedActivity)
    }

    private fun pack(name: String) = BuiltIconPack(
        uri = Uri.parse("file:///unread-apk.apk"), packageName = name,
        canBeInstalled = true, profileId = 7L, packLabel = "Custom",
        preferences = emptyPreferences(), profileApps = emptyList(), preservedRows = emptyList()
    )
}
