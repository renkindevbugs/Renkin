package dev.renkinProject.renkin.data

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.room.Room
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A corrupt settings file is replaced by defaults plus a recovery marker. Startup must then keep
 * every profile's stored style: the reset values are not the active profile's live settings.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class SettingsCorruptionRecoveryTest {
    private lateinit var db: RenkinPackDatabase
    private lateinit var repo: RenkinPackRepository
    private lateinit var prefs: InMemoryPreferences

    private val replacedAfterCorruption = preferencesOf(SettingsReplacedAfterCorruptionKey to true)

    @Before fun setup() {
        StorageOperationGate.resetForTests()
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), RenkinPackDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = RenkinPackRepository(db)
        prefs = InMemoryPreferences()
    }

    @After fun cleanup() {
        StorageOperationGate.resetForTests()
        db.close()
    }

    private suspend fun seedLegacy(first: String, second: String) {
        repo.replaceEverything(listOf(
            Profile(id = 1, name = "First", prefsSnapshot = first),
            Profile(id = 2, name = "Second", prefsSnapshot = second)
        ), emptyList())
    }

    private fun snapshotOf(vararg values: Preferences.Pair<*>): String = preferencesOf(*values).snapshotProfilePrefs()

    private suspend fun assertStyle(id: Long, expected: Preferences) {
        val row = repo.profile(id)!!
        val restored = ProfileStylePreferences.toPreferences(ProfileStyleCodec.decode(row.profileStyle))
        assertEquals(expected.asMap().filterKeys { it in ProfilePrefKeys }, restored.asMap())
        assertEquals(expected.snapshotProfilePrefs(), row.prefsSnapshot)
    }

    private suspend fun initialize() = ProfileStyleStore(repo, prefs).initialize()

    @Test fun legacyProfilesKeepTheirSnapshotsWhenTheFirstProfileWasActive() = runBlocking {
        seedLegacy(snapshotOf(GlobalIconScaleKey to 73), snapshotOf(GlobalIconScaleKey to 121))
        prefs.values.value = replacedAfterCorruption

        initialize()

        assertStyle(1, preferencesOf(GlobalIconScaleKey to 73))
        assertStyle(2, preferencesOf(GlobalIconScaleKey to 121))
        assertEquals(1L, prefs.values.value[ActiveProfileIdKey])
        assertNull(prefs.values.value[SettingsReplacedAfterCorruptionKey])
    }

    @Test fun profileThatWasActiveBeforeTheCorruptionKeepsItsSnapshotAndTheDefaultBecomesActive() = runBlocking {
        // The lost file also held ACTIVE_PROFILE_ID = 2; only the defaults and the marker survive.
        seedLegacy(snapshotOf(GlobalIconScaleKey to 9), snapshotOf(GlobalIconScaleKey to 140, OutlineAddKey to true))
        prefs.values.value = replacedAfterCorruption

        initialize()

        assertStyle(1, preferencesOf(GlobalIconScaleKey to 9))
        assertStyle(2, preferencesOf(GlobalIconScaleKey to 140, OutlineAddKey to true))
        assertEquals(1L, prefs.values.value[ActiveProfileIdKey])
    }

    @Test fun typedProfilesKeepTheirExactDocuments() = runBlocking {
        seedLegacy(snapshotOf(GlobalIconScaleKey to 73), snapshotOf(GlobalIconScaleKey to 121))
        prefs.values.value = preferencesOf(ActiveProfileIdKey to 1L, GlobalIconScaleKey to 73)
        initialize()
        val before = repo.profiles()
        prefs.values.value = replacedAfterCorruption

        initialize()

        assertEquals(before, repo.profiles())
        assertNull(prefs.values.value[SettingsReplacedAfterCorruptionKey])
    }

    @Test fun invalidActiveSnapshotFollowsTheLegacyDefaultsPolicyWithoutTouchingOtherProfiles() = runBlocking {
        seedLegacy("not json", snapshotOf(GlobalIconScaleKey to 121))
        prefs.values.value = replacedAfterCorruption

        initialize()

        assertStyle(1, preferencesOf())
        assertStyle(2, preferencesOf(GlobalIconScaleKey to 121))
        assertNull(prefs.values.value[SettingsReplacedAfterCorruptionKey])
    }

    @Test fun withoutTheMarkerTheActiveLegacyProfileStillUsesItsLiveSettings() = runBlocking {
        seedLegacy(snapshotOf(GlobalIconScaleKey to 9), snapshotOf(GlobalIconScaleKey to 121))
        prefs.values.value = preferencesOf(ActiveProfileIdKey to 1L, GlobalIconScaleKey to 73)

        initialize()

        assertStyle(1, preferencesOf(GlobalIconScaleKey to 73))
    }

    @Test fun corruptSettingsFileIsReplacedByTheRecoveryMarkerInsteadOfFailingReads() = runBlocking {
        val file = File(RuntimeEnvironment.getApplication().cacheDir, "corrupt-settings.preferences_pb")
        file.writeBytes(ByteArray(64) { 0xFF.toByte() })
        val store = PreferenceDataStoreFactory.create(corruptionHandler = settingsCorruptionHandler) { file }

        val read = store.data.first()

        assertEquals(true, read[SettingsReplacedAfterCorruptionKey])
        assertTrue(read.asMap().keys.all { it == SettingsReplacedAfterCorruptionKey })
    }

    private class InMemoryPreferences : DataStore<Preferences> {
        val values = MutableStateFlow(preferencesOf())
        override val data = values
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(values.value).toPreferences().also { values.value = it }
    }
}
