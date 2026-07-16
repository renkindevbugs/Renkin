package dev.renkinProject.renkin.apk

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import dev.renkinProject.renkin.data.ActiveProfileIdKey
import dev.renkinProject.renkin.data.DEFAULT_PROFILE_ID
import dev.renkinProject.renkin.data.DbApplication
import dev.renkinProject.renkin.data.Profile
import dev.renkinProject.renkin.data.RenkinPackDatabase
import dev.renkinProject.renkin.data.RenkinPackRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class ProfileManagerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var db: RenkinPackDatabase
    private lateinit var repo: RenkinPackRepository
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, RenkinPackDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = RenkinPackRepository(db)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        db.close()
        scope.cancel()
    }

    private fun manager() = ProfileManager(
        context,
        repo,
        PreferenceDataStoreFactory.create(scope = scope) {
            temporaryFolder.newFile("profile.preferences_pb")
        }
    )

    @Test
    fun initRepairsMissingDefaultProfileWithoutDeletingItsIcons() = runBlocking {
        repo.replaceAll(
            DEFAULT_PROFILE_ID,
            listOf(
                DbApplication(
                    "com.example",
                    "com.example.Main",
                    isAdaptiveIcon = false,
                    isXml = false,
                    drawable = "saved"
                )
            )
        )
        val manager = manager()

        manager.initActiveId()

        assertEquals(DEFAULT_PROFILE_ID, manager.activeProfileId)
        assertNotNull(repo.profile(DEFAULT_PROFILE_ID))
        assertEquals(listOf("com.example"), repo.getAll(DEFAULT_PROFILE_ID).map { it.packageName })
    }

    @Test
    fun initReplacesStalePersistedActiveIdWithDefault() = runBlocking {
        val store = PreferenceDataStoreFactory.create(scope = scope) {
            temporaryFolder.newFile("stale.preferences_pb")
        }
        store.edit { it[ActiveProfileIdKey] = 999L }
        val manager = ProfileManager(context, repo, store)

        manager.initActiveId()

        assertEquals(DEFAULT_PROFILE_ID, manager.activeProfileId)
        assertEquals(DEFAULT_PROFILE_ID, store.data.first()[ActiveProfileIdKey])
    }

    @Test
    fun initKeepsExistingPersistedProfile() = runBlocking {
        repo.ensureDefaultProfile()
        val profileId = repo.createProfile(Profile(name = "Dark"))
        val store = PreferenceDataStoreFactory.create(scope = scope) {
            temporaryFolder.newFile("valid.preferences_pb")
        }
        store.edit { it[ActiveProfileIdKey] = profileId }
        val manager = ProfileManager(context, repo, store)

        manager.initActiveId()

        assertEquals(profileId, manager.activeProfileId)
        assertEquals(profileId, store.data.first()[ActiveProfileIdKey])
    }

    @Test
    fun updateProfileDetailsPersistsEveryEditableField() = runBlocking {
        val profileId = repo.createProfile(Profile(name = "Old", packLabel = "Old Pack"))

        manager().updateProfileDetails(profileId, "New", "Description", "New Pack")

        val updated = repo.profile(profileId)
        assertEquals("New", updated?.name)
        assertEquals("Description", updated?.description)
        assertEquals("New Pack", updated?.packLabel)
    }

    @Test
    fun deleteProfileRemovesProfileAndItsIcons() = runBlocking {
        repo.ensureDefaultProfile()
        val profileId = repo.createProfile(Profile(name = "Disposable"))
        repo.replaceAll(
            profileId,
            listOf(
                DbApplication(
                    "com.disposable",
                    "com.disposable.Main",
                    isAdaptiveIcon = false,
                    isXml = false,
                    drawable = "saved",
                    profileId = profileId
                )
            )
        )

        manager().deleteProfile(profileId)

        assertEquals(null, repo.profile(profileId))
        assertEquals(emptyList<DbApplication>(), repo.getAll(profileId))
    }
}
