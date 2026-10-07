package dev.renkinProject.renkin.data

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.room.Room
import dev.renkinProject.renkin.apk.ProfileManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class ProfileStyleStoreTest {
    private lateinit var db: RenkinPackDatabase
    private lateinit var repo: RenkinPackRepository
    private lateinit var prefs: FaultInjectingPreferences
    private lateinit var owner: ProfileStyleStore

    @Before fun setup() {
        StorageOperationGate.resetForTests()
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), RenkinPackDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = RenkinPackRepository(db)
        prefs = FaultInjectingPreferences()
        owner = ProfileStyleStore(repo, prefs)
        ProfileStyleAccess.install { owner }
    }

    @After fun cleanup() {
        StorageOperationGate.resetForTests()
        db.close()
    }

    private suspend fun seed(active: Preferences = preferencesOf(GlobalIconScaleKey to 73),
                             inactive: Preferences = preferencesOf(GlobalIconScaleKey to 121)) {
        repo.replaceEverything(listOf(
            Profile(id = 1, name = "Active", prefsSnapshot = preferencesOf(GlobalIconScaleKey to 9).snapshotProfilePrefs()),
            Profile(id = 2, name = "Inactive", prefsSnapshot = inactive.snapshotProfilePrefs())
        ), emptyList())
        prefs.values.value = active.toMutablePreferences().apply { this[ActiveProfileIdKey] = 1L }.toPreferences()
    }

    private fun installBarrier() = StorageOperationGate.installRecovery { ProfileStyleAccess.initialize() }

    private suspend fun assertStyle(id: Long, expected: Preferences) {
        val row = repo.profile(id)!!
        val restored = ProfileStylePreferences.toPreferences(ProfileStyleCodec.decode(row.profileStyle))
        assertEquals(expected.asMap().filterKeys { it in ProfilePrefKeys }, restored.asMap())
        assertEquals(expected.snapshotProfilePrefs(), row.prefsSnapshot)
    }

    @Test fun bootstrapUsesLiveActiveValuesAndInactiveSnapshotsWithoutNormalizingConfiguration() = runBlocking {
        val active = preferencesOf(GlobalIconScaleKey to 73, GlobalColorizeKey to false,
            GlobalColorizeColorKey to "configured theme colour", PrimarySourceKey to -17,
            TextFontKey to "/missing/font.ttf", DarkModeKey to DarkMode.DARK.ordinal)
        val inactive = preferencesOf(GlobalIconScaleKey to 121, OutlineAddKey to false, OutlineWidthKey to -3)
        seed(active, inactive)
        installBarrier()
        val snapshot = prefs.getPreferencesAfterPendingWrites()
        assertStyle(1, active)
        assertStyle(2, inactive)
        assertEquals(DarkMode.DARK.ordinal, snapshot[DarkModeKey])
        assertEquals(1L, snapshot[ActiveProfileIdKey])
        assertEquals(StorageRecoveryState.READY, StorageOperationGate.recoveryState.value)
    }

    @Test fun bootstrapRepairsInvalidActiveIdAndMissingDefaultWithoutDeletingIcons() = runBlocking {
        repo.replaceEverything(listOf(Profile(id = 2, name = "Other")), listOf(
            DbApplication("app.one", "Main", false, false, "kept")))
        prefs.values.value = preferencesOf(ActiveProfileIdKey to 999L, GlobalIconScaleKey to 64)
        installBarrier()
        assertEquals(1L, prefs.getPreferencesAfterPendingWrites()[ActiveProfileIdKey])
        assertStyle(1, preferencesOf(GlobalIconScaleKey to 64))
        assertEquals("kept", repo.getAll(1).single().drawable)
    }

    @Test fun restartTrustsExactTypedDocumentAndRepairsStaleLegacyAndUiCopies() = runBlocking {
        seed()
        owner.initialize()
        val row = repo.profile(1)!!
        val exact = " \n${row.profileStyle}\n"
        db.profileDao().update(row.copy(profileStyle = exact, prefsSnapshot = "{}"))
        prefs.values.value = preferencesOf(ActiveProfileIdKey to 1L, GlobalIconScaleKey to 3)
        owner = ProfileStyleStore(repo, prefs)
        assertEquals(73, prefs.getPreferencesAfterPendingWrites()[GlobalIconScaleKey])
        assertEquals(exact, repo.profile(1)!!.profileStyle)
        assertStyle(1, preferencesOf(GlobalIconScaleKey to 73))
    }

    @Test fun invalidDocumentBlocksBootstrapBeforeAnyStyleConversionOrUiWrite() = runBlocking {
        seed()
        val before = repo.profiles()
        db.profileDao().update(before[1].copy(profileStyle = "broken"))
        val preferencesBefore = prefs.values.value
        installBarrier()
        assertTrue(runCatching { prefs.getPreferencesAfterPendingWrites() }.exceptionOrNull() is StorageRecoverySnapshotException)
        assertEquals(StorageRecoveryState.FAILED, StorageOperationGate.recoveryState.value)
        assertEquals(before[0], db.profileDao().get(1))
        assertEquals("broken", db.profileDao().get(2)!!.profileStyle)
        assertEquals(preferencesBefore, prefs.values.value)
    }

    @Test fun oversizedLegacyCandidateLeavesAllRowsUnconverted() = runBlocking {
        seed(inactive = preferencesOf(TextFontKey to "x".repeat(ProfileStyleCodec.MAX_DOCUMENT_CHARS)))
        val before = repo.profiles()
        assertTrue(runCatching { owner.initialize() }.isFailure)
        assertEquals(before, repo.profiles())
    }

    @Test fun roomFailureOnSecondBootstrapRowRollsBackFirstRowAndLeavesPreferencesUntouched() = runBlocking {
        seed()
        val before = repo.profiles()
        val projection = prefs.values.value
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_second_style BEFORE UPDATE ON Profile " +
            "WHEN NEW.id = 2 BEGIN SELECT RAISE(ABORT, 'injected style write failure'); END")
        assertTrue(runCatching { owner.initialize() }.isFailure)
        assertEquals(before, repo.profiles())
        assertEquals(projection, prefs.values.value)
    }

    @Test fun concurrentWorkerAndApplicationFirstAccessShareBootstrapWithoutAnActivity() = runBlocking {
        seed()
        installBarrier()
        val worker = async(Dispatchers.Default) { prefs.getPreferencesAfterPendingWrites() }
        val application = async(Dispatchers.Default) { ProfileStyleAccess.initialize() }
        assertEquals(73, withTimeout(5000) { worker.await() }[GlobalIconScaleKey])
        withTimeout(5000) { application.await() }
        assertEquals(1, prefs.successfulWrites)
        assertStyle(2, preferencesOf(GlobalIconScaleKey to 121))
    }

    @Test fun everyRegisteredProfileKeyPersistsThroughExistingPreferenceSetter() = runBlocking {
        seed(emptyPreferences(), emptyPreferences())
        owner.initialize()
        for ((index, key) in ProfilePrefKeys.withIndex()) {
            val value = listOf<Any>(index % 2 == 0, -500 - index, "${key.name}:$index")
                .single { isValidPersistedPreference(key.name, it) }
            @Suppress("UNCHECKED_CAST")
            prefs.setPreferenceValue(key as Preferences.Key<Any>, value)
            assertStyle(1, prefs.values.value)
        }
        owner = ProfileStyleStore(repo, prefs)
        assertStyle(1, prefs.getPreferencesAfterPendingWrites())
        assertStyle(2, emptyPreferences())
    }

    @Test fun bundledSourceColourAndGlobalSaveWritersUseTheSameOwner() = runBlocking {
        seed()
        prefs.setPrimarySource(Source.ICON_PACK, "pack.one")
        assertStyle(1, prefs.values.value)
        prefs.setColorStyle(OutlineStyleKeys, 1, 0, 37, Color.Red, listOf(0xff123456.toInt()), listOf(0f, 1f))
        assertStyle(1, prefs.values.value)
        val staged = preferencesOf(PrimarySourceKey to Source.APPLICATION_NAME.ordinal,
            PrimaryIconPackKey to "", GlobalIconScaleKey to 91, GlobalColorizeKey to false,
            GlobalColorizeInverseKey to true, OutlineAddKey to true, OutlineWidthKey to 6)
        prefs.persistGlobalStylePrefs(staged)
        assertStyle(1, prefs.values.value)
        assertEquals(91, prefs.values.value[GlobalIconScaleKey])
        prefs.persistGlobalModifierPrefs(staged.toMutablePreferences().apply { this[GlobalIconScaleKey] = 82 })
        assertEquals(82, prefs.values.value[GlobalIconScaleKey])
        assertStyle(1, prefs.values.value)
        prefs.persistBuiltPrimaryPrefs(staged)
        prefs.restoreBuiltPrimarySource(prefs.values.value)
        assertStyle(1, prefs.values.value)
        prefs.restoreProfilePrefs(preferencesOf(GlobalIconScaleKey to 101).snapshotProfilePrefs())
        assertStyle(1, preferencesOf(GlobalIconScaleKey to 101))
    }

    @Test fun switchesIgnoreStaleTargetArgumentsAndNeverSaveLeavingCache() = runBlocking {
        seed()
        prefs.setPreferenceValue(GlobalIconScaleKey, 88)
        var leavingCalled = false
        prefs.switchProfilePrefs("{}", 2) { leavingCalled = true }
        assertFalse(leavingCalled)
        assertEquals(121, prefs.values.value[GlobalIconScaleKey])
        prefs.switchProfilePrefs("{}", 1) { leavingCalled = true }
        assertEquals(88, prefs.values.value[GlobalIconScaleKey])
        assertStyle(1, preferencesOf(GlobalIconScaleKey to 88))
    }

    @Test fun editRejectsSimultaneousProfileSelectionAndDepartingStyleChange() = runBlocking {
        seed()
        owner.initialize()
        val before = repo.profiles()
        assertTrue(runCatching { owner.edit { it[ActiveProfileIdKey] = 2L; it[GlobalIconScaleKey] = 11 } }.isFailure)
        assertEquals(before, repo.profiles())
        assertEquals(1L, prefs.values.value[ActiveProfileIdKey])
    }

    @Test fun staleMetadataCopiesCannotOverwriteNewStyleOrItsLegacyProjection() = runBlocking {
        seed()
        owner.initialize()
        val stale = repo.profile(1)!!
        prefs.setPreferenceValue(GlobalIconScaleKey, 95)
        repo.updateProfile(stale.copy(name = "Renamed", packLabel = "Label", hasUnbuiltChanges = true))
        assertStyle(1, preferencesOf(GlobalIconScaleKey to 95))
        assertEquals("Renamed", repo.profile(1)!!.name)
        assertTrue(repo.profile(1)!!.hasUnbuiltChanges)
    }

    @Test fun profileCreationStartsConvertedAndBuiltBaselineTargetsInactiveOwner() = runBlocking {
        seed()
        val manager = ProfileManager(RuntimeEnvironment.getApplication(), repo, prefs)
        manager.initActiveId()
        val created = manager.createProfile("New", "Description", "Pack")
        assertStyle(created, emptyPreferences())
        manager.switchTo(2)
        val activeBefore = prefs.values.value
        manager.recordBuiltPrimary(1, preferencesOf(PrimarySourceKey to Source.ICON_PACK.ordinal, PrimaryIconPackKey to "built.pack"))
        assertEquals(activeBefore, prefs.values.value)
        assertStyle(1, preferencesOf(GlobalIconScaleKey to 73, BuiltPrimarySourceKey to Source.ICON_PACK.ordinal,
            BuiltPrimaryIconPackKey to "built.pack"))
    }

    @Test fun applicationOnlyFailureDoesNotWriteRoomOrEnterRecovery() = runBlocking {
        seed()
        owner.initialize()
        val before = repo.profiles()
        prefs.failWrites = true
        assertTrue(runCatching { prefs.setPreferenceValue(DarkModeKey, DarkMode.DARK.ordinal) }.exceptionOrNull() is IOException)
        assertEquals(before, repo.profiles())
        assertEquals(StorageRecoveryState.READY, StorageOperationGate.recoveryState.value)
        prefs.failWrites = false
        prefs.setPreferenceValue(AppSortOrderKey, 1)
        assertEquals(1, prefs.getPreferencesAfterPendingWrites()[AppSortOrderKey])
    }

    @Test fun successfulApplicationOnlyEditNeverAttemptsAProfileUpdate() = runBlocking {
        seed()
        owner.initialize()
        val before = repo.profiles()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_style_write BEFORE UPDATE ON Profile " +
            "BEGIN SELECT RAISE(ABORT, 'application settings must not write profiles'); END")
        prefs.setPreferenceValue(DarkModeKey, DarkMode.DARK.ordinal)
        assertEquals(DarkMode.DARK.ordinal, prefs.values.value[DarkModeKey])
        assertEquals(before, repo.profiles())
    }

    @Test fun failedProfileSelectionKeepsOldActiveStateWithoutRecovery() = runBlocking {
        seed()
        owner.initialize()
        prefs.failWrites = true
        assertTrue(runCatching { owner.switchTo(2) }.exceptionOrNull() is IOException)
        assertEquals(1L, prefs.values.value[ActiveProfileIdKey])
        assertEquals(StorageRecoveryState.READY, StorageOperationGate.recoveryState.value)
        prefs.failWrites = false
        owner.switchTo(2)
        assertEquals(2L, prefs.values.value[ActiveProfileIdKey])
    }

    @Test fun committedStyleProjectionFailureBlocksAccessUntilRetryReprojectsRoom() = runBlocking {
        seed()
        installBarrier()
        prefs.getPreferencesAfterPendingWrites()
        prefs.failWrites = true
        assertTrue(runCatching { prefs.setPreferenceValue(GlobalIconScaleKey, 92) }.exceptionOrNull() is IOException)
        assertEquals(73, prefs.values.value[GlobalIconScaleKey])
        assertEquals(StorageRecoveryState.FAILED, StorageOperationGate.recoveryState.value)
        assertTrue(runCatching { prefs.getPreferencesAfterPendingWrites() }.exceptionOrNull() is StorageRecoveryUnavailableException)
        prefs.failWrites = false
        StorageOperationGate.retryRecovery { }
        assertEquals(92, prefs.getPreferencesAfterPendingWrites()[GlobalIconScaleKey])
        assertStyle(1, preferencesOf(GlobalIconScaleKey to 92))
    }

    @Test fun bootstrapProjectionFailureRetriesAlreadyConvertedRoomRows() = runBlocking {
        seed()
        installBarrier()
        prefs.failWrites = true
        assertTrue(runCatching { prefs.getPreferencesAfterPendingWrites() }.exceptionOrNull() is IOException)
        val document = db.profileDao().get(1)!!.profileStyle
        assertTrue(document.isNotEmpty())
        prefs.failWrites = false
        StorageOperationGate.retryRecovery { }
        assertEquals(document, repo.profile(1)!!.profileStyle)
        assertEquals(73, prefs.values.value[GlobalIconScaleKey])
    }

    @Test fun cancellationBeforeAcceptanceLeavesBothStoresUnchanged() = runBlocking {
        seed()
        owner.initialize()
        val before = repo.profiles()
        val edit = async { owner.edit { it[GlobalIconScaleKey] = 12; cancel() } }
        edit.join()
        assertTrue(edit.isCancelled)
        assertEquals(before, repo.profiles())
        assertEquals(73, prefs.values.value[GlobalIconScaleKey])
    }

    @Test fun cancellationAfterRoomCommitStillFinishesProjection() = runBlocking {
        seed()
        owner.initialize()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        prefs.beforeCommit = { entered.complete(Unit); release.await() }
        val edit = async { prefs.setPreferenceValue(GlobalIconScaleKey, 87) }
        withTimeout(5000) { entered.await() }
        edit.cancel()
        release.complete(Unit)
        withTimeout(5000) { edit.join() }
        assertTrue(edit.isCancelled)
        assertStyle(1, preferencesOf(GlobalIconScaleKey to 87))
        assertEquals(87, prefs.values.value[GlobalIconScaleKey])
    }

    @Test fun explicitRepairPreservesOriginalsAndKeepsUnknownOrdinalsAndValidDocuments() = runBlocking {
        seed()
        owner.initialize()
        val valid = repo.profile(2)!!
        val legacy = preferencesOf(PrimarySourceKey to -123, GlobalIconScaleKey to 77).snapshotProfilePrefs()
        db.profileDao().update(repo.profile(1)!!.copy(profileStyle = "broken", prefsSnapshot = legacy))
        var preserved = false
        owner.repairInvalidDocuments {
            assertEquals("broken", repo.profile(1)!!.profileStyle)
            preserved = true
        }
        assertTrue(preserved)
        assertStyle(1, preferencesOf(PrimarySourceKey to -123, GlobalIconScaleKey to 77))
        assertEquals(valid, repo.profile(2))
    }

    @Test fun repairValidatesEveryLegacyCopyBeforePreservingOrWritingAnything() = runBlocking {
        seed()
        owner.initialize()
        db.profileDao().update(repo.profile(1)!!.copy(profileStyle = "broken"))
        val malformed = listOf("", "{", "{} trailing", "{\"${GlobalIconScaleKey.name}\":1,\"${GlobalIconScaleKey.name}\":2}",
            "{\"unknown\":1}", "{\"${GlobalIconScaleKey.name}\":\"wrong\"}", "{\"${GlobalIconScaleKey.name}\":2147483648}",
            "{\"${GlobalIconScaleKey.name}\":1.5}", "{\"${TextFontKey.name}\":null}", "{\"${TextFontKey.name}\":\"\\ud800\"}")
        for (legacy in malformed) {
            db.profileDao().update(repo.profile(2)!!.copy(profileStyle = "also broken", prefsSnapshot = legacy))
            val before = repo.profiles()
            assertTrue(legacy, runCatching { owner.repairInvalidDocuments { fail("must validate before preserving") } }.isFailure)
            assertEquals(legacy, before, repo.profiles())
        }
    }

    @Test fun failedOriginalPreservationPreventsRepairWrites() = runBlocking {
        seed()
        owner.initialize()
        db.profileDao().update(repo.profile(1)!!.copy(profileStyle = "broken"))
        val before = repo.profiles()
        assertTrue(runCatching { owner.repairInvalidDocuments { throw IOException("disk full") } }.exceptionOrNull() is IOException)
        assertEquals(before, repo.profiles())
    }

    @Test fun standaloneStagedStoreDoesNotWriteTheInstalledOwnersRoomStyle() = runBlocking {
        seed()
        owner.initialize()
        val before = repo.profiles()
        val staged = FaultInjectingPreferences()
        staged.setPreferenceValue(GlobalIconScaleKey, 31)
        assertEquals(31, staged.getPreferencesAfterPendingWrites()[GlobalIconScaleKey])
        assertEquals(before, repo.profiles())
    }

    private class FaultInjectingPreferences : DataStore<Preferences> {
        val values = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = values
        private val mutex = Mutex()
        var failWrites = false
        var successfulWrites = 0
        var beforeCommit: (suspend () -> Unit)? = null
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = mutex.withLock {
            val next = transform(values.value).toPreferences()
            beforeCommit?.invoke()
            if (failWrites) throw IOException("Injected DataStore write failure")
            values.value = next
            successfulWrites++
            next
        }
    }
}
