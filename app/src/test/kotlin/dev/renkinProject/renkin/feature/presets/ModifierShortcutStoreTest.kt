package dev.renkinProject.renkin.feature.presets

import android.app.Application
import android.content.Context
import androidx.room.Room
import dev.renkinProject.renkin.data.RenkinPackDatabase
import dev.renkinProject.renkin.data.RenkinPackRepository
import dev.renkinProject.renkin.data.StorageOperationGate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class ModifierShortcutStoreTest {
    private lateinit var database: RenkinPackDatabase
    private lateinit var repository: RenkinPackRepository
    private lateinit var shortcuts: ModifierShortcutStore

    @Before fun setUp() {
        StorageOperationGate.resetForTests()
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("modifier_shortcuts", Context.MODE_PRIVATE).edit().clear().commit()
        database = Room.inMemoryDatabaseBuilder(context, RenkinPackDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = RenkinPackRepository(database)
        shortcuts = ModifierShortcutStore(context, repository)
    }

    @After fun tearDown() {
        database.close()
        StorageOperationGate.resetForTests()
    }

    @Test fun confirmedUseKeepsTwoDistinctPresetsPerProfileAndSurvivesANewOwner() = runBlocking {
        val ids = (1..3).map { repository.saveModifierPreset("Preset $it", "payload", 1) }
        assertFalse(shortcuts.observe(1).first().visible)
        ids.forEach { shortcuts.recordApply(1, ModifierShortcutApply(true, it)) }
        shortcuts.recordApply(1, ModifierShortcutApply(true, ids.last()))
        val restored = ModifierShortcutStore(RuntimeEnvironment.getApplication(), repository).observe(1).first()
        assertEquals(listOf(ids[2], ids[1]), restored.recentIds)
        assertTrue(restored.visible)
        assertTrue(shortcuts.observe(2).first().recentIds.isEmpty())
    }

    @Test fun dismissAndFourUnusedAppliesHideUntilAConfirmedModifierUse() = runBlocking {
        val id = repository.saveModifierPreset("Style", "payload", 1)
        shortcuts.recordApply(1, ModifierShortcutApply(true, id))
        repeat(3) { shortcuts.recordApply(1, ModifierShortcutApply(false, null)) }
        assertTrue(shortcuts.observe(1).first().visible)
        shortcuts.recordApply(1, ModifierShortcutApply(false, null))
        assertFalse(shortcuts.observe(1).first().visible)
        shortcuts.recordApply(1, ModifierShortcutApply(true, null))
        assertTrue(shortcuts.observe(1).first().visible)
        shortcuts.dismiss(1)
        assertFalse(shortcuts.observe(1).first().visible)
        assertEquals(listOf(id), shortcuts.observe(1).first().recentIds)
        shortcuts.recordApply(2, ModifierShortcutApply(true, id))
        assertFalse(shortcuts.observe(1).first().visible)
        shortcuts.recordApply(1, ModifierShortcutApply(true, null))
        assertTrue(shortcuts.observe(1).first().visible)
    }
}
