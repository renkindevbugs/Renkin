package dev.renkinProject.renkin.ui

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.renkinProject.renkin.data.GlobalIconScaleKey
import dev.renkinProject.renkin.data.StorageOperationGate
import dev.renkinProject.renkin.data.StorageRecoverySnapshotException
import dev.renkinProject.renkin.data.getPreferenceValue
import dev.renkinProject.renkin.data.getPreferencesValue
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PreferencesLifecycleComposeTest {
    @get:Rule val compose = createComposeRule()
    private val owner = Owner()
    private val store = TrackedPreferences()

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private class TrackedPreferences : DataStore<Preferences> {
        val values = MutableStateFlow(preferencesOf(GlobalIconScaleKey to 70))
        val subscriptions = AtomicInteger()
        private val mutex = Mutex()
        override val data = flow {
            subscriptions.incrementAndGet()
            try { emitAll(values) } finally { subscriptions.decrementAndGet() }
        }
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            mutex.withLock { transform(values.value).also { values.value = it } }
    }

    @Before fun setup() {
        StorageOperationGate.resetForTests()
        owner.registry.currentState = Lifecycle.State.CREATED
    }

    @After fun cleanup() {
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.DESTROYED }
        awaitSubscriptions(0)
        StorageOperationGate.resetForTests()
    }

    private fun showPreferences() {
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                val snapshot = store.getPreferencesValue()
                val scale = store.getPreferenceValue(GlobalIconScaleKey, 100)
                val recovery by StorageOperationGate.recoveryState.collectAsStateWithLifecycle()
                Text("${snapshot[GlobalIconScaleKey]} / $scale / $recovery")
            }
        }
    }

    private fun moveTo(state: Lifecycle.State) {
        compose.runOnIdle { owner.registry.currentState = state }
    }

    private fun awaitSubscriptions(count: Int) {
        compose.waitUntil(5_000) { store.subscriptions.get() == count }
    }

    @Test fun preferenceHelpersRetainValuesWhileStoppedAndResubscribeToLatestOnStart() {
        showPreferences()
        compose.onNodeWithText("null / 100 / READY").assertIsDisplayed()
        awaitSubscriptions(0)
        moveTo(Lifecycle.State.STARTED)
        awaitSubscriptions(2)
        compose.onNodeWithText("70 / 70 / READY").assertIsDisplayed()

        moveTo(Lifecycle.State.CREATED)
        awaitSubscriptions(0)
        store.values.value = preferencesOf(GlobalIconScaleKey to 80)
        store.values.value = preferencesOf(GlobalIconScaleKey to 120)
        compose.onNodeWithText("70 / 70 / READY").assertIsDisplayed()

        moveTo(Lifecycle.State.STARTED)
        awaitSubscriptions(2)
        compose.onNodeWithText("120 / 120 / READY").assertIsDisplayed()
        store.values.value = preferencesOf()
        compose.onNodeWithText("null / 100 / READY").assertIsDisplayed()
    }

    @Test fun restartingUiDuringRecoveryKeepsDataPausedUntilRecoveryCompletes() = runBlocking<Unit> {
        showPreferences()
        moveTo(Lifecycle.State.STARTED)
        compose.onNodeWithText("70 / 70 / READY").assertIsDisplayed()
        moveTo(Lifecycle.State.CREATED)
        awaitSubscriptions(0)
        StorageOperationGate.requireRecovery(StorageRecoverySnapshotException("Invalid style"))
        store.values.value = preferencesOf(GlobalIconScaleKey to 140)

        moveTo(Lifecycle.State.STARTED)
        compose.onNodeWithText("70 / 70 / FAILED").assertIsDisplayed()
        awaitSubscriptions(0)
        StorageOperationGate.abandonRecovery { }
        awaitSubscriptions(2)
        compose.onNodeWithText("140 / 140 / READY").assertIsDisplayed()
    }
}
