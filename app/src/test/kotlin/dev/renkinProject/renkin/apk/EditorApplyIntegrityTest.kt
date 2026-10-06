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
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.data.DbApplication
import dev.renkinProject.renkin.data.GlobalApplyCustomKey
import dev.renkinProject.renkin.data.GlobalIconScaleKey
import dev.renkinProject.renkin.data.RenkinPackDatabase
import dev.renkinProject.renkin.data.RenkinPackRepository
import dev.renkinProject.renkin.dataStore
import dev.renkinProject.renkin.di.AppModule
import dev.renkinProject.renkin.drawable.BitmapIconDrawable
import dev.renkinProject.renkin.drawable.IconPackDrawable
import dev.renkinProject.renkin.icon.creator.globalModifierOptions
import dev.renkinProject.renkin.packages.PackageInfoStruct
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
@Config(application = Application::class, sdk = [28])
class EditorApplyIntegrityTest {
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
        val info = ActivityInfo().apply {
            applicationInfo = context.applicationInfo
            packageName = context.packageName
            name = "${context.packageName}.TestActivity"
            icon = R.drawable.renkinpack_ic_launcher_foreground
        }
        shadowOf(context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps)
            .addActivity(Process.myUserHandle(), ReflectionHelpers.callConstructor(
                LauncherActivityInfo::class.java,
                ClassParameter.from(Context::class.java, context),
                ClassParameter.from(ActivityInfo::class.java, info),
                ClassParameter.from(UserHandle::class.java, Process.myUserHandle())
            ))
        database = Room.inMemoryDatabaseBuilder(context, RenkinPackDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = RenkinPackRepository(database)
        provider = newProvider()
    }

    @After
    fun tearDown() { database.close() }

    private fun newProvider(): ApplicationProvider {
        val catalog = AppModule.provideIconPackCatalog(context)
        val icons = AppModule.provideIconPackRepository(context, catalog)
        locks = AppModule.provideIconLockManager(context, repository)
        val profiles = AppModule.provideProfileManager(context, repository)
        return AppModule.provideApplicationProvider(
            context, AppModule.provideRenkinPackStore(context, repository), repository,
            icons, locks, profiles, AppModule.provideIconGenerationService(context, icons, catalog),
            AppModule.provideIconPackBuildService(context, icons, repository, locks, profiles, catalog)
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
}
