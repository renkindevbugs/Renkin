package dev.renkinProject.renkin.feature.presets

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.data.RenkinPackDatabase
import dev.renkinProject.renkin.data.RenkinPackRepository
import dev.renkinProject.renkin.data.StorageOperationGate
import dev.renkinProject.renkin.apk.ApplicationProvider
import dev.renkinProject.renkin.di.AppModule
import dev.renkinProject.renkin.data.watch.WatchDatabase
import dev.renkinProject.renkin.data.watch.WatchRepository
import dev.renkinProject.renkin.data.editor.EditorDraftInvalidation
import dev.renkinProject.renkin.data.editor.EditorDraftStore
import dev.renkinProject.renkin.data.UnsavedIconJournalStore
import dev.renkinProject.renkin.packages.InstalledIconLoader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class PresetLibraryViewModelTest {
    private lateinit var db: RenkinPackDatabase
    private lateinit var repository: RenkinPackRepository
    private lateinit var watchDb: WatchDatabase
    private lateinit var provider: ApplicationProvider
    private lateinit var shortcuts: ModifierShortcutStore
    private val stores = mutableListOf<ViewModelStore>()

    @Before
    fun setUp() {
        StorageOperationGate.resetForTests()
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), RenkinPackDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = RenkinPackRepository(db)
        val context = RuntimeEnvironment.getApplication()
        watchDb = Room.inMemoryDatabaseBuilder(context, WatchDatabase::class.java).allowMainThreadQueries().build()
        val catalog = AppModule.provideIconPackCatalog(context)
        val icons = AppModule.provideIconPackRepository(context, catalog)
        val locks = AppModule.provideIconLockManager(context, repository)
        val profiles = AppModule.provideProfileManager(context, repository, WatchRepository(watchDb))
        val installed = InstalledIconLoader(context)
        provider = AppModule.provideApplicationProvider(context,
            AppModule.provideRenkinPackStore(context, repository), repository, icons, locks, profiles,
            AppModule.provideIconGenerationService(context, icons, catalog, installed),
            AppModule.provideIconPackBuildService(context, icons, repository, locks, profiles, catalog),
            EditorDraftInvalidation(EditorDraftStore(context), UnsavedIconJournalStore(context)), installed)
        shortcuts = ModifierShortcutStore(context, repository)
    }

    @After
    fun tearDown() = runBlocking {
        stores.forEach { it.clear() }
        shadowOf(Looper.getMainLooper()).idle()
        db.close()
        watchDb.close()
        StorageOperationGate.resetForTests()
    }

    private fun owner(): PresetLibraryViewModel = PresetLibraryViewModel(repository, shortcuts, provider).also { vm ->
        stores += ViewModelStore().also { it.put("presets", vm) }
    }

    private suspend fun awaitCondition(condition: () -> Boolean) = withTimeout(10_000) {
        while (!condition()) {
            shadowOf(Looper.getMainLooper()).idle()
            delay(10)
        }
    }

    @Test
    fun bothActivityOwnersObserveWritesButOnlyWriterEmitsSuccess() = runBlocking {
        val main = owner()
        val global = owner()
        val mainEvents = mutableListOf<Int>()
        val globalEvents = mutableListOf<Int>()
        val collectors = listOf(
            launch(start = CoroutineStart.UNDISPATCHED) { main.toastEvents.collect { mainEvents += it } },
            launch(start = CoroutineStart.UNDISPATCHED) { global.toastEvents.collect { globalEvents += it } }
        )
        try {
            main.saveColorPreset("Color", "encoded-color")
            awaitCondition { main.colorPresets.value.size == 1 && global.colorPresets.value.size == 1 && mainEvents.size == 1 }
            assertEquals("encoded-color", repository.allColorPresets().single().style)
            assertEquals(listOf(R.string.savedColorsAdded), mainEvents)
            assertTrue(globalEvents.isEmpty())
            global.deleteColorPreset(global.colorPresets.value.single().id)
            awaitCondition { main.colorPresets.value.isEmpty() && global.colorPresets.value.isEmpty() && globalEvents.size == 1 }
            assertTrue(repository.allColorPresets().isEmpty())
            assertEquals(listOf(R.string.savedColorsRemoved), globalEvents)

            global.saveModifierPreset("Original", "first", 1)
            awaitCondition { main.modifierPresets.value.size == 1 && global.modifierPresets.value.size == 1 && globalEvents.size == 2 }
            val id = main.modifierPresets.value.single().id
            main.renameModifierPreset(id, "Renamed")
            awaitCondition { global.modifierPresets.value.single().name == "Renamed" }
            main.updateModifierPreset(id, "second", 2)
            awaitCondition { global.modifierPresets.value.single().payload == "second" && mainEvents.size == 2 }
            assertEquals(2, repository.allModifierPresets().single().schemaVersion)
            val previousUse = global.modifierPresets.value.single().lastUsedAt
            delay(10)
            main.markModifierPresetUsed(id)
            awaitCondition { global.modifierPresets.value.single().lastUsedAt > previousUse }
            global.deleteModifierPreset(id)
            awaitCondition { main.modifierPresets.value.isEmpty() && global.modifierPresets.value.isEmpty() && globalEvents.size == 3 }
            assertTrue(repository.allModifierPresets().isEmpty())
            assertEquals(listOf(R.string.savedColorsAdded, R.string.modifierPresetUpdated), mainEvents)
            assertEquals(listOf(R.string.savedColorsRemoved, R.string.modifierPresetSaved, R.string.modifierPresetRemoved), globalEvents)
        } finally { collectors.forEach { it.cancelAndJoin() } }
    }

    @Test
    fun successWaitsForStorageGateAndDurableWrite() = runBlocking {
        val vm = owner()
        val events = mutableListOf<Int>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            vm.toastEvents.collect {
                assertEquals("Queued", repository.allColorPresets().single().name)
                events += it
            }
        }
        val release = CompletableDeferred<Unit>()
        val holder = launch(start = CoroutineStart.UNDISPATCHED) { StorageOperationGate.run { release.await() } }
        try {
            vm.saveColorPreset("Queued", "style")
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(events.isEmpty())
            assertTrue(vm.colorPresets.value.isEmpty())
            release.complete(Unit)
            holder.join()
            awaitCondition { events.size == 1 && vm.colorPresets.value.size == 1 }
            assertEquals(listOf(R.string.savedColorsAdded), events)
        } finally {
            release.complete(Unit)
            holder.join()
            collector.cancelAndJoin()
        }
    }

    private fun PresetLibraryViewModel.requestEveryWrite(colorId: Long, modifierId: Long) {
        saveColorPreset("New color", "new")
        deleteColorPreset(colorId)
        saveModifierPreset("New modifier", "new", 2)
        renameModifierPreset(modifierId, "Changed")
        updateModifierPreset(modifierId, "changed", 2)
        markModifierPresetUsed(modifierId)
        deleteModifierPreset(modifierId)
    }

    @Test
    fun failedRecoveryReportsEveryWriteWithoutMutatingLibraryOrReportingSuccess() = runBlocking {
        val colorId = repository.saveColorPreset("Kept color", "old")
        val modifierId = repository.saveModifierPreset("Kept modifier", "old", 1)
        val colors = repository.allColorPresets()
        val modifiers = repository.allModifierPresets()
        val vm = owner()
        awaitCondition { vm.colorPresets.value.size == 1 && vm.modifierPresets.value.size == 1 }
        val events = mutableListOf<Int>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { vm.toastEvents.collect { events += it } }
        try {
            StorageOperationGate.requireRecovery(IOException("Test recovery failure"))
            vm.requestEveryWrite(colorId, modifierId)
            awaitCondition { events.size == 7 }
            assertEquals(List(7) { R.string.presetLibraryWriteFailed }, events)
            StorageOperationGate.resetForTests()
            assertEquals(colors, repository.allColorPresets())
            assertEquals(modifiers, repository.allModifierPresets())
        } finally { collector.cancelAndJoin() }
    }

    @Test
    fun clearingOwnerCancelsQueuedWritesWithoutFailureToasts() = runBlocking {
        val colorId = repository.saveColorPreset("Kept color", "old")
        val modifierId = repository.saveModifierPreset("Kept modifier", "old", 1)
        val colors = repository.allColorPresets()
        val modifiers = repository.allModifierPresets()
        val vm = owner()
        val events = mutableListOf<Int>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { vm.toastEvents.collect { events += it } }
        val release = CompletableDeferred<Unit>()
        val holder: Job = launch(start = CoroutineStart.UNDISPATCHED) { StorageOperationGate.run { release.await() } }
        try {
            vm.requestEveryWrite(colorId, modifierId)
            shadowOf(Looper.getMainLooper()).idle()
            stores.single().clear()
            shadowOf(Looper.getMainLooper()).idle()
            release.complete(Unit)
            holder.join()
            assertEquals(colors, repository.allColorPresets())
            assertEquals(modifiers, repository.allModifierPresets())
            assertTrue(events.isEmpty())
        } finally {
            release.complete(Unit)
            holder.join()
            collector.cancelAndJoin()
        }
    }
}
