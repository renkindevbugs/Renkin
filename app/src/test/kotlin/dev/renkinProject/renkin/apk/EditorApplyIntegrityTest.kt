package dev.renkinProject.renkin.apk

import android.app.Application
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Process
import android.os.UserHandle
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import dev.renkinProject.renkin.data.editor.EditorDraftInvalidation
import dev.renkinProject.renkin.data.UnsavedIconJournal
import dev.renkinProject.renkin.data.UnsavedIconJournalEntry
import dev.renkinProject.renkin.data.UnsavedIconJournalStore
import dev.renkinProject.renkin.data.editor.EditorDraftStore
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.data.DbApplication
import dev.renkinProject.renkin.data.BuiltIcon
import dev.renkinProject.renkin.data.GlobalApplyCustomKey
import dev.renkinProject.renkin.data.GlobalIconScaleKey
import dev.renkinProject.renkin.data.GlobalApplyGeneratedKey
import dev.renkinProject.renkin.data.PrimarySourceKey
import dev.renkinProject.renkin.data.OverrideIconKey
import dev.renkinProject.renkin.data.Source
import dev.renkinProject.renkin.data.StorageOperationGate
import dev.renkinProject.renkin.data.RenkinPackDatabase
import dev.renkinProject.renkin.data.RenkinPackRepository
import dev.renkinProject.renkin.data.dataStore
import dev.renkinProject.renkin.di.AppModule
import dev.renkinProject.renkin.drawable.BitmapIconDrawable
import dev.renkinProject.renkin.drawable.IconPackDrawable
import dev.renkinProject.renkin.icon.creator.globalModifierOptions
import dev.renkinProject.renkin.packages.PackageInfoStruct
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], shadows = [ResolvingLauncherAppsShadow::class])
class EditorApplyIntegrityTest {
    private val draftInvalidation by lazy { EditorDraftInvalidation(EditorDraftStore(context), UnsavedIconJournalStore(context)) }
    private lateinit var context: Application
    private lateinit var database: RenkinPackDatabase
    private lateinit var repository: RenkinPackRepository
    private lateinit var provider: ApplicationProvider
    private lateinit var locks: IconLockManager

    @Before
    fun setUp() = runBlocking {
        context = RuntimeEnvironment.getApplication()
        context.dataStore.edit {
            it.clear()
            it[GlobalApplyCustomKey] = true
            it[GlobalIconScaleKey] = 50
        }
        addLauncherActivity("TestActivity")
        database = Room.inMemoryDatabaseBuilder(context, RenkinPackDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = RenkinPackRepository(database)
        provider = newProvider()
    }

    private fun addLauncherActivity(name: String) {
        val info = ActivityInfo().apply {
            applicationInfo = context.applicationInfo
            packageName = context.packageName
            this.name = "${context.packageName}.$name"
            icon = R.drawable.renkinpack_ic_launcher_foreground
        }
        shadowOf(context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps)
            .addActivity(Process.myUserHandle(), ReflectionHelpers.callConstructor(
                LauncherActivityInfo::class.java,
                ClassParameter.from(Context::class.java, context),
                ClassParameter.from(ActivityInfo::class.java, info),
                ClassParameter.from(UserHandle::class.java, Process.myUserHandle())
            ))
    }

    @After
    fun tearDown() { database.close() }

    private fun newProvider(): ApplicationProvider {
        val catalog = AppModule.provideIconPackCatalog(context)
        val icons = AppModule.provideIconPackRepository(context, catalog)
        locks = AppModule.provideIconLockManager(context, repository)
        val profiles = AppModule.provideProfileManager(context, repository)
        val installedIcons = dev.renkinProject.renkin.packages.InstalledIconLoader(context)
        return AppModule.provideApplicationProvider(
            context, AppModule.provideRenkinPackStore(context, repository), repository,
            icons, locks, profiles, AppModule.provideIconGenerationService(context, icons, catalog, installedIcons),
            AppModule.provideIconPackBuildService(context, icons, repository, locks, profiles, catalog),
            editorDraftInvalidation = draftInvalidation,
            installedIconLoader = installedIcons
        )
    }

    private fun draft(): IconPackDrawable = BitmapIconDrawable(Bitmap.createBitmap(
        32, 32, Bitmap.Config.ARGB_8888
    ).apply { eraseColor(Color.WHITE) })

    private suspend fun initializeApp(): PackageInfoStruct {
        provider.ensureInitialized()
        return provider.applicationList.single()
    }

    private suspend fun apply(app: PackageInfoStruct, icon: IconPackDrawable?): IconApplyOutcome =
        provider.applyPickedIcon(provider.sessionToken, app, icon, context.packageName) { live, origin, confirmed ->
            live.changeExport(
                confirmed, sourcePackName = origin, isCustom = true, isRefreshMade = false,
                baseIcon = icon, sourceUrl = "https://example.com/icon.svg"
            ).changeCalendar(true, "day_", context.packageName)
        }

    private fun reference(isCustom: Boolean = true) = DbApplication(
        packageName = context.packageName, activityName = "${context.packageName}.TestActivity",
        isAdaptiveIcon = false, isXml = false, drawable = "",
        sourcePackName = context.packageName,
        sourceDrawableName = "renkinpack_ic_launcher_foreground", isCustomIcon = isCustom
    )

    @Test
    fun undoOnlyInvalidationPublishesWithoutReplacingApplications() = runBlocking {
        val app = initializeApp()
        apply(app, draft())
        val step = provider.resetIcon(provider.sessionToken, provider.applicationList.single())!!
        val reset = provider.applicationState.value
        assertSame(step, reset.undoStep)
        provider.selectPrimarySource(provider.sessionToken, Source.NONE, null)
        val cleared = provider.applicationState.value
        assertSame(reset.applications, cleared.applications)
        assertEquals(reset.revision + 1, cleared.revision)
        assertNull(cleared.undoStep)
        assertSame(step, reset.undoStep)
        assertFalse(provider.undoLastIconChange(provider.sessionToken, step))
    }

    @Test
    fun resetUndoDoesNotPersistEitherRestoredOrUnrelatedDirtyIcons() = runBlocking {
        addLauncherActivity("OtherActivity")
        provider.ensureInitialized()
        val apps = provider.applicationList.toList()
        val originals = apps.associate { it.key to draft() }
        apps.forEach { apply(it, originals.getValue(it.key)) }
        val dirty = provider.unsavedIconKeys
        val step = provider.resetIcon(provider.sessionToken, provider.applicationList.first())!!
        assertTrue(provider.undoLastIconChange(provider.sessionToken, step))
        assertEquals(dirty, provider.unsavedIconKeys)
        provider.applicationList.forEach { assertSame(originals[it.key], it.createdIcon) }
        assertTrue(repository.getAll(provider.activeProfileId).isEmpty())
        val originalProfile = provider.activeProfileId
        val other = provider.createProfile("Other", "", "Other")
        provider.switchProfile(other)
        provider.switchProfile(originalProfile)
        assertTrue(provider.applicationList.all { it.createdIcon == null })
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun firstSingleGenerationCanReturnToTheEmptySlotWithUndo() = runBlocking {
        context.dataStore.edit {
            it[PrimarySourceKey] = Source.APPLICATION_ICON.ordinal
            it[GlobalApplyGeneratedKey] = false
        }
        val app = initializeApp()
        assertNull(app.createdIcon)
        val step = provider.refreshIcon(provider.sessionToken, app)!!
        assertNotNull(provider.applicationList.single().createdIcon)
        assertSame(step, provider.applicationState.value.undoStep)
        assertTrue(provider.undoLastIconChange(provider.sessionToken, step))
        assertNull(provider.applicationList.single().createdIcon)
        assertTrue(provider.unsavedIconKeys.isEmpty())
        assertTrue(repository.getAll(provider.activeProfileId).isEmpty())
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun mixedBulkRefreshUndoesBothNewAndReplacedIcons() = runBlocking {
        addLauncherActivity("OtherActivity")
        context.dataStore.edit {
            it[PrimarySourceKey] = Source.APPLICATION_ICON.ordinal
            it[GlobalApplyGeneratedKey] = false
            it[OverrideIconKey] = true
        }
        provider.ensureInitialized()
        val existing = provider.applicationList.first()
        val reviewed = draft()
        apply(existing, reviewed)
        val before = provider.applicationList.associateBy { it.key }
        val dirty = provider.unsavedIconKeys
        val result = provider.refreshIcons(provider.sessionToken)
        val step = result.undoStep!!
        assertEquals(2, step.rows.size)
        assertTrue(provider.applicationList.all { it.createdIcon != null })
        assertTrue(provider.undoLastIconChange(provider.sessionToken, step))
        provider.applicationList.forEach { assertSame(before.getValue(it.key).createdIcon, it.createdIcon) }
        assertEquals(dirty, provider.unsavedIconKeys)
    }

    @Test
    fun contendedResetAndUndoLeaveThePublishedStateUntouchedAndCanBeRetried() = runBlocking {
        val app = initializeApp()
        val reviewed = draft()
        apply(app, reviewed)
        suspend fun rejectWhileStorageIsHeld(operation: suspend () -> Unit) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val holder = launch(Dispatchers.Default) {
                StorageOperationGate.run { entered.complete(Unit); release.await() }
            }
            entered.await()
            val before = provider.applicationState.value
            try {
                try {
                    withTimeout(3_000) { operation() }
                    fail("Expected bounded gate contention")
                } catch (_: ProfileOperationBusyException) { }
                assertSame(before, provider.applicationState.value)
            } finally { release.complete(Unit); holder.join() }
        }
        rejectWhileStorageIsHeld { provider.resetIcon(provider.sessionToken, app) }
        val step = provider.resetIcon(provider.sessionToken, app)!!
        rejectWhileStorageIsHeld { provider.undoLastIconChange(provider.sessionToken, step) }
        assertSame(step, provider.applicationState.value.undoStep)
        assertTrue(provider.undoLastIconChange(provider.sessionToken, step))
        assertSame(reviewed, provider.applicationList.single().createdIcon)
    }

    @Test
    fun editorApplyKeepsReviewedDraftWithCustomGlobalStyleEnabled() = runBlocking {
        val app = initializeApp()
        val reviewed = draft()
        assertEquals(IconApplyOutcome.APPLIED, apply(app, reviewed))
        val saved = provider.applicationList.single()
        assertSame(reviewed, saved.createdIcon)
        assertSame(reviewed, saved.baseIcon)
        assertEquals(context.packageName, saved.sourcePackName)
        assertEquals("https://example.com/icon.svg", saved.sourceUrl)
        assertTrue(saved.hasCalendarIcon)
        assertEquals("day_", saved.calendarPrefix)
        assertEquals(context.packageName, saved.calendarPackName)
        assertTrue(app.key in provider.unsavedIconKeys)
    }

    @Test
    fun watchApplyKeepsItsReviewedDraftWithCustomGlobalStyleEnabled() = runBlocking {
        val app = initializeApp()
        val reviewed = draft()
        assertEquals(IconApplyOutcome.APPLIED,
            provider.applyWatchIcon(provider.sessionToken, app, reviewed, context.packageName))
        assertSame(reviewed, provider.applicationList.single().createdIcon)
        assertSame(reviewed, provider.applicationList.single().baseIcon)
        assertTrue(provider.applicationList.single().isCustom)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun explicitGlobalApplyChangesOnlyRenderAndRetainsTheConfirmedBase() = runBlocking {
        val app = initializeApp()
        val reviewed = draft()
        assertEquals(IconApplyOutcome.APPLIED, apply(app, reviewed))
        val preferences = context.dataStore.data.first()
        provider.applyGlobalModifiers(
            provider.sessionToken, preferences, globalModifierOptions(preferences),
            applyGenerated = false, applyExisting = false, applyCustom = true, includeEmpty = false
        )
        val updated = provider.applicationList.single()
        assertSame(reviewed, updated.baseIcon)
        assertNotSame(reviewed, updated.createdIcon)
        assertEquals(Color.WHITE, reviewed.toBitmap().getPixel(0, 0))
        assertEquals(0, Color.alpha(updated.createdIcon!!.toBitmap().getPixel(0, 0)))
        val saved = repository.getAll(provider.activeProfileId).single()
        assertEquals(reviewed.toDbString(), saved.baseDrawable)
        assertEquals(updated.createdIcon!!.toDbString(), saved.drawable)
    }

    @Test
    fun savedIndividualApplySurvivesProviderRecreationWithoutGlobalRestyling() = runBlocking {
        val app = initializeApp()
        val reviewed = draft()
        assertEquals(IconApplyOutcome.APPLIED, apply(app, reviewed))
        provider.saveActiveProfileIcons(provider.sessionToken)
        val restarted = newProvider()
        restarted.ensureInitialized()
        val loaded = restarted.applicationList.single()
        assertEquals(reviewed.toDbString(), loaded.createdIcon!!.toDbString())
        assertEquals(reviewed.toDbString(), loaded.baseIcon!!.toDbString())
        assertTrue(loaded.isCustom)
        assertEquals("day_", loaded.calendarPrefix)
    }

    private suspend fun journalOf(vararg apps: PackageInfoStruct) = UnsavedIconJournal(
        provider.sessionToken.providerInstanceId, 1L,
        apps.toList().zip(provider.encodeUnsavedIcons(1L, apps.toList())) { app, row ->
            UnsavedIconJournalEntry(app.key, row)
        }
    )

    @Test
    fun journaledApplyRestoresAsAnUnsavedEditAfterProviderRecreation() = runBlocking {
        val app = initializeApp()
        val reviewed = draft()
        assertEquals(IconApplyOutcome.APPLIED, apply(app, reviewed))
        val journal = journalOf(provider.applicationList.single())

        val restarted = newProvider()
        restarted.ensureInitialized()
        assertNull(restarted.applicationList.single().createdIcon)
        val restored = restarted.restoreUnsavedIcons(restarted.sessionToken, journal)

        val live = restarted.applicationList.single()
        assertEquals(setOf(app.key), restored)
        assertEquals(setOf(app.key), restarted.unsavedIconKeys)
        assertEquals(reviewed.toDbString(), live.baseIcon!!.toDbString())
        assertTrue(live.isCustom)
        assertFalse(live.isRefreshMade)
        assertEquals("day_", live.calendarPrefix)
        assertEquals("https://example.com/icon.svg", live.sourceUrl)
        assertTrue("Restoring must not save", repository.getAll(1L).isEmpty())
    }

    @Test
    fun journaledRowThatWasSavedMeanwhileIsNotRestoredAgain() = runBlocking {
        val app = initializeApp()
        assertEquals(IconApplyOutcome.APPLIED, apply(app, draft()))
        val journal = journalOf(provider.applicationList.single())
        provider.saveActiveProfileIcons(provider.sessionToken)

        val restarted = newProvider()
        restarted.ensureInitialized()

        assertEquals(emptySet<String>(), restarted.restoreUnsavedIcons(restarted.sessionToken, journal))
        assertEquals(emptySet<String>(), restarted.unsavedIconKeys)
    }

    @Test
    fun journaledClearRemovesTheSavedIconWithoutSaving() = runBlocking {
        val app = initializeApp()
        assertEquals(IconApplyOutcome.APPLIED, apply(app, draft()))
        provider.saveActiveProfileIcons(provider.sessionToken)
        val cleared = UnsavedIconJournal(provider.sessionToken.providerInstanceId, 1L, listOf(UnsavedIconJournalEntry(app.key, null)))

        val restarted = newProvider()
        restarted.ensureInitialized()
        assertEquals(setOf(app.key), restarted.restoreUnsavedIcons(restarted.sessionToken, cleared))

        assertNull(restarted.applicationList.single().createdIcon)
        assertEquals(1, repository.getAll(1L).size)
    }

    @Test
    fun journalForAnotherProfileRestoresNothing() = runBlocking {
        val app = initializeApp()
        assertEquals(IconApplyOutcome.APPLIED, apply(app, draft()))
        val foreign = journalOf(provider.applicationList.single()).copy(profileId = 99L)

        val restarted = newProvider()
        restarted.ensureInitialized()

        assertEquals(emptySet<String>(), restarted.restoreUnsavedIcons(restarted.sessionToken, foreign))
    }

    @Test
    fun customReferenceLoadsWithoutAutomaticallyAddingTheGlobalLayer() = runBlocking {
        repository.replaceAll(1L, listOf(reference()))
        val loaded = initializeApp()
        assertTrue(loaded.isCustom)
        assertTrue(loaded.createdIcon != null)
        assertSame(loaded.baseIcon, loaded.createdIcon)
    }

    @Test
    fun releasedCustomReferenceAlsoKeepsItsUnstyledBase() = runBlocking {
        val app = initializeApp()
        locks.lock(app.key, reference())
        locks.publish()
        assertTrue(app.key in provider.lockedIconKeys)
        provider.refreshIconLocks(provider.sessionToken)
        val loaded = provider.applicationList.single()
        assertTrue(loaded.isCustom)
        assertTrue(loaded.createdIcon != null)
        assertSame(loaded.baseIcon, loaded.createdIcon)
        assertTrue(provider.lockedIconKeys.isEmpty())
    }

    @Test
    fun expiredEditorCannotCommitOrRunItsCallback() = runBlocking {
        val app = initializeApp()
        val oldSession = provider.sessionToken
        provider.clearIcons(oldSession)
        val outcome = provider.applyPickedIcon(oldSession, app, draft(), null) { _, _, _ ->
            fail("An expired edit must not run")
            app
        }
        assertEquals(IconApplyOutcome.PROFILE_CHANGED, outcome)
        assertNull(provider.applicationList.single().createdIcon)
    }

    @Test
    fun nullApplyDoesNotTurnGlobalStyleIntoANewIcon() = runBlocking {
        val app = initializeApp()
        assertEquals(IconApplyOutcome.APPLIED, apply(app, draft()))
        assertEquals(IconApplyOutcome.APPLIED, apply(app, null))
        assertNull(provider.applicationList.single().createdIcon)
        assertNull(provider.applicationList.single().baseIcon)
        assertNull(provider.applicationList.single().sourcePackName)
    }

    @Test
    fun baselineReadAllowsNewLiveEditsWithoutClearingTheirDirtyKeys() = runBlocking {
        val app = initializeApp()
        val read = provider.readIconChangeBaselines(provider.sessionToken)
        val reviewed = draft()
        apply(app, reviewed)
        assertTrue(provider.commitIconChangeBaselines(read) {
            assertTrue(it.saved.isEmpty())
        })
        assertSame(reviewed, provider.applicationList.single().createdIcon)
        assertEquals(setOf(app.key), provider.applicationState.value.unsavedIconKeys)
    }

    @Test
    fun savedArtworkRejectsAnOlderBaselineReadAndFreshReadUsesTheSavedRows() = runBlocking {
        val app = initializeApp()
        val read = provider.readIconChangeBaselines(provider.sessionToken)
        apply(app, draft())
        provider.saveActiveProfileIcons(provider.sessionToken)
        assertFalse(provider.commitIconChangeBaselines(read) { fail("Stale save baseline published") })
        val fresh = provider.readIconChangeBaselines(provider.sessionToken)
        val rows = repository.getAll(provider.activeProfileId)
        assertEquals(setOf(app.key), fresh.baselines.saved.keys)
        assertEquals(rows.single().fingerprint(), fresh.baselines.saved.getValue(app.key))
        assertTrue(provider.commitIconChangeBaselines(fresh) { assertEquals(fresh.baselines, it) })
    }

    @Test
    fun changedBuildRecordRejectsBaselineEvenWhenSavedArtworkDidNotChange() = runBlocking {
        val app = initializeApp()
        val read = provider.readIconChangeBaselines(provider.sessionToken)
        repository.replaceBuiltIcons(provider.activeProfileId, listOf(
            BuiltIcon(provider.activeProfileId, app.packageName, app.activityName, "previous-apk")
        ))
        assertFalse(provider.commitIconChangeBaselines(read) { fail("Stale build baseline published") })
        val fresh = provider.readIconChangeBaselines(provider.sessionToken)
        assertTrue(provider.commitIconChangeBaselines(fresh) {
            assertEquals(mapOf(app.key to "previous-apk"), it.built)
        })
    }

    @Test
    fun switchingExpiresBaselineReadsAndSuppliesOnlyTheTargetsLoadedKeys() = runBlocking {
        val app = initializeApp()
        apply(app, draft())
        provider.saveActiveProfileIcons(provider.sessionToken)
        val original = provider.sessionToken
        val read = provider.readIconChangeBaselines(original)
        val other = provider.createProfile("Other", "", "Other icons")
        provider.switchProfile(other)
        assertTrue(provider.loadedSavedIconKeys.isEmpty())
        assertFalse(provider.commitIconChangeBaselines(read) { fail("Other profile baseline published") })
        provider.switchProfile(original.profileId)
        assertEquals(setOf(app.key), provider.loadedSavedIconKeys)
        assertFalse(provider.commitIconChangeBaselines(read) { fail("An old session was revived") })
    }

    @Test
    fun storageReplacementRejectsCapturedBaselineWithoutPublishing() = runBlocking {
        initializeApp()
        val read = provider.readIconChangeBaselines(provider.sessionToken)
        StorageOperationGate.run { StorageOperationGate.markStorageReplaced() }
        assertFalse(provider.commitIconChangeBaselines(read) { fail("Pre-restore baseline published") })
    }

    @Test
    fun legacyBuiltAdoptionHappensOnlyWhenAFreshReadCommits() = runBlocking {
        val app = initializeApp()
        apply(app, draft())
        provider.saveActiveProfileIcons(provider.sessionToken)
        val profile = repository.profile(provider.activeProfileId)!!
        repository.updateProfile(profile.copy(hasUnbuiltChanges = false))
        val read = provider.readIconChangeBaselines(provider.sessionToken)
        assertTrue(repository.builtIcons(profile.id).isEmpty())
        assertTrue(provider.commitIconChangeBaselines(read) {
            assertEquals(it.saved, it.built)
            assertEquals(setOf(app.key), it.built.keys)
        })
        assertEquals(read.baselines.saved.values.single(), repository.builtIcons(profile.id).single().iconHash)
    }

    @Test
    fun legacyAdoptionRechecksUnbuiltPolicyAtPublication() = runBlocking {
        val app = initializeApp()
        apply(app, draft())
        provider.saveActiveProfileIcons(provider.sessionToken)
        val profile = repository.profile(provider.activeProfileId)!!
        repository.updateProfile(profile.copy(hasUnbuiltChanges = false))
        val read = provider.readIconChangeBaselines(provider.sessionToken)
        repository.updateProfile(profile.copy(hasUnbuiltChanges = true))
        assertTrue(provider.commitIconChangeBaselines(read) { assertTrue(it.built.isEmpty()) })
        assertTrue(repository.builtIcons(profile.id).isEmpty())
    }

    @Test
    fun savingPublishesDirtyOnlyChangesAndRetainedSnapshotsKeepTheirKeys() = runBlocking {
        val app = initializeApp()
        val original = provider.applicationState.value
        apply(app, draft())
        val dirty = provider.applicationState.value
        assertEquals(setOf(app.key), dirty.unsavedIconKeys)
        assertTrue(original.unsavedIconKeys.isEmpty())

        provider.saveActiveProfileIcons(provider.sessionToken)
        val saved = provider.applicationState.value
        assertSame(dirty.applications, saved.applications)
        assertTrue(saved.unsavedIconKeys.isEmpty())
        assertEquals(dirty.revision + 1, saved.revision)
        assertEquals(setOf(app.key), dirty.unsavedIconKeys)
        assertFalse(saved.longOperationRunning)
    }

    @Test
    fun publishedApplicationSnapshotsRemainStableAfterApplyResetAndUndo() = runBlocking {
        val app = initializeApp()
        val original = provider.applicationState.value
        assertTrue(original.applicationsLoaded)
        assertEquals(provider.sessionToken, original.sessionToken)
        val reviewed = draft()
        apply(app, reviewed)
        val applied = provider.applicationState.value
        assertEquals(original.revision + 1, applied.revision)
        assertNull(original.applications.single().createdIcon)
        assertSame(reviewed, applied.applications.single().createdIcon)
        val step = provider.resetIcon(provider.sessionToken, applied.applications.single())!!
        val reset = provider.applicationState.value
        assertEquals(applied.revision + 1, reset.revision)
        assertNull(reset.applications.single().createdIcon)
        assertSame(reviewed, applied.applications.single().createdIcon)
        assertTrue(provider.undoLastIconChange(provider.sessionToken, step))
        assertSame(reviewed, provider.applicationState.value.applications.single().createdIcon)
        assertNull(reset.applications.single().createdIcon)
    }

    @Test
    fun heldRowReleasePublishesItsIconAndLockKeysTogetherAndResetClearsBoth() = runBlocking {
        val app = initializeApp()
        locks.lock(app.key, reference())
        provider.commitSessionState(provider.sessionToken) { }
        val locked = provider.applicationState.value
        assertEquals(setOf(app.key), locked.lockedIconKeys)
        assertNull(locked.applications.single().createdIcon)
        provider.refreshIconLocks(provider.sessionToken)
        val released = provider.applicationState.value
        assertTrue(released.lockedIconKeys.isEmpty())
        assertTrue(released.applications.single().createdIcon != null)
        assertEquals(setOf(app.key), locked.lockedIconKeys)
        locks.lock(app.key, reference())
        provider.commitSessionState(provider.sessionToken) { }
        val beforeReset = provider.applicationState.value
        provider.resetIcon(provider.sessionToken, beforeReset.applications.single())
        val reset = provider.applicationState.value
        assertTrue(reset.lockedIconKeys.isEmpty())
        assertNull(reset.applications.single().createdIcon)
        assertEquals(beforeReset.revision + 1, reset.revision)
    }

    @Test
    fun unchangedOperationsDoNotPublishAndFailedPartialLockChangesDo() = runBlocking {
        val app = initializeApp()
        val original = provider.applicationState.value
        provider.commitSessionState(provider.sessionToken) { }
        assertSame(original, provider.applicationState.value)
        try {
            provider.commitSessionState(provider.sessionToken) {
                locks.lock(app.key, reference())
                throw IllegalArgumentException("partial operation")
            }
            fail("Expected failure")
        } catch (_: IllegalArgumentException) { }
        val partial = provider.applicationState.value
        assertEquals(original.revision + 1, partial.revision)
        assertEquals(setOf(app.key), partial.lockedIconKeys)
        assertTrue(original.lockedIconKeys.isEmpty())
    }

    @Test
    fun newSessionPublishesItsOwnerAndRejectsEditsFromTheRetainedSnapshot() = runBlocking {
        val app = initializeApp()
        apply(app, draft())
        val previous = provider.applicationState.value
        val other = provider.createProfile("Other", "", "Other icons")
        provider.switchProfile(other)
        val current = provider.applicationState.value
        assertEquals(other, current.sessionToken!!.profileId)
        assertEquals(provider.sessionToken, current.sessionToken)
        assertNull(current.applications.single().createdIcon)
        assertTrue(previous.applications.single().createdIcon != null)
        assertEquals(IconApplyOutcome.PROFILE_CHANGED,
            provider.applyPickedIcon(previous.sessionToken!!, previous.applications.single(), draft(), null) { _, _, _ ->
                fail("A retained snapshot must not authorize an edit")
                app
            })
        assertSame(current, provider.applicationState.value)
    }
}
