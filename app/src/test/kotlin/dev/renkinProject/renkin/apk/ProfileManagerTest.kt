package dev.renkinProject.renkin.apk

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.preferencesOf
import androidx.room.Room
import dev.renkinProject.renkin.data.ActiveProfileIdKey
import dev.renkinProject.renkin.data.BuiltPrimaryIconPackKey
import dev.renkinProject.renkin.data.BuiltPrimarySourceKey
import dev.renkinProject.renkin.data.DEFAULT_PROFILE_ID
import dev.renkinProject.renkin.data.DbApplication
import dev.renkinProject.renkin.data.GlobalColorizeInverseKey
import dev.renkinProject.renkin.data.PrimaryIconPackKey
import dev.renkinProject.renkin.data.PrimarySourceKey
import dev.renkinProject.renkin.data.Profile
import dev.renkinProject.renkin.data.RenkinPackDatabase
import dev.renkinProject.renkin.data.RenkinPackRepository
import dev.renkinProject.renkin.data.Source
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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

    @Test
    fun switchToSnapshotsLeavingPrefsAndRestoresEachProfile() = runBlocking {
        repo.ensureDefaultProfile()
        val profileId = repo.createProfile(
            Profile(
                name = "Inverse",
                prefsSnapshot = """{"PRIMARY_ICON_PACK":"second.pack","GLOBAL_COLORIZE_INVERSE":true}"""
            )
        )
        val store = PreferenceDataStoreFactory.create(scope = scope) {
            temporaryFolder.newFile("switch.preferences_pb")
        }
        store.edit {
            it[ActiveProfileIdKey] = DEFAULT_PROFILE_ID
            it[PrimaryIconPackKey] = "default.pack"
            it[GlobalColorizeInverseKey] = false
        }
        val manager = ProfileManager(context, repo, store)
        manager.initActiveId()

        manager.switchTo(profileId)

        assertEquals("second.pack", store.data.first()[PrimaryIconPackKey])
        assertEquals(true, store.data.first()[GlobalColorizeInverseKey])
        val savedDefault = JSONObject(repo.profile(DEFAULT_PROFILE_ID)!!.prefsSnapshot)
        assertEquals("default.pack", savedDefault.getString(PrimaryIconPackKey.name))
        assertEquals(false, savedDefault.getBoolean(GlobalColorizeInverseKey.name))

        manager.switchTo(DEFAULT_PROFILE_ID)

        assertEquals("default.pack", store.data.first()[PrimaryIconPackKey])
        assertEquals(false, store.data.first()[GlobalColorizeInverseKey])
        val savedSecond = JSONObject(repo.profile(profileId)!!.prefsSnapshot)
        assertEquals("second.pack", savedSecond.getString(PrimaryIconPackKey.name))
        assertEquals(true, savedSecond.getBoolean(GlobalColorizeInverseKey.name))
    }

    @Test
    fun markUnbuiltUpdatesOnlyRequestedProfile() = runBlocking {
        repo.ensureDefaultProfile()
        val profileId = repo.createProfile(Profile(name = "Pending"))
        val manager = manager()

        manager.markUnbuilt(profileId, true)

        assertEquals(false, repo.profile(DEFAULT_PROFILE_ID)?.hasUnbuiltChanges)
        assertEquals(true, repo.profile(profileId)?.hasUnbuiltChanges)
    }

    @Test
    fun recordBuiltPrimaryUpdatesActiveProfileDataStore() = runBlocking {
        repo.ensureDefaultProfile()
        val store = PreferenceDataStoreFactory.create(scope = scope) {
            temporaryFolder.newFile("active-build.preferences_pb")
        }
        store.edit { it[ActiveProfileIdKey] = DEFAULT_PROFILE_ID }
        val manager = ProfileManager(context, repo, store)
        manager.initActiveId()
        val built = preferencesOf(
            PrimarySourceKey to Source.ICON_PACK.ordinal,
            PrimaryIconPackKey to "built.active"
        )

        manager.recordBuiltPrimary(DEFAULT_PROFILE_ID, built)

        val stored = store.data.first()
        assertEquals(Source.ICON_PACK.ordinal, stored[BuiltPrimarySourceKey])
        assertEquals("built.active", stored[BuiltPrimaryIconPackKey])
    }

    @Test
    fun recordBuiltPrimaryUpdatesInactiveSnapshotWithoutTouchingActiveDataStore() = runBlocking {
        repo.ensureDefaultProfile()
        val profileId = repo.createProfile(
            Profile(name = "Inactive", prefsSnapshot = """{"PRIMARY_ICON_PACK":"inactive.current"}""")
        )
        val store = PreferenceDataStoreFactory.create(scope = scope) {
            temporaryFolder.newFile("inactive-build.preferences_pb")
        }
        store.edit {
            it[ActiveProfileIdKey] = DEFAULT_PROFILE_ID
            it[BuiltPrimarySourceKey] = Source.APPLICATION_ICON.ordinal
            it[BuiltPrimaryIconPackKey] = "built.active"
        }
        val manager = ProfileManager(context, repo, store)
        manager.initActiveId()
        val built = preferencesOf(
            PrimarySourceKey to Source.ICON_PACK.ordinal,
            PrimaryIconPackKey to "built.inactive"
        )

        manager.recordBuiltPrimary(profileId, built)

        val active = store.data.first()
        assertEquals(Source.APPLICATION_ICON.ordinal, active[BuiltPrimarySourceKey])
        assertEquals("built.active", active[BuiltPrimaryIconPackKey])
        val inactive = JSONObject(repo.profile(profileId)!!.prefsSnapshot)
        assertEquals("inactive.current", inactive.getString(PrimaryIconPackKey.name))
        assertEquals(Source.ICON_PACK.ordinal, inactive.getInt(BuiltPrimarySourceKey.name))
        assertEquals("built.inactive", inactive.getString(BuiltPrimaryIconPackKey.name))
    }
}
