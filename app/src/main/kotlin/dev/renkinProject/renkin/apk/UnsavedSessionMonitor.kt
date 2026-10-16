package dev.renkinProject.renkin.apk

import dev.renkinProject.renkin.data.UnsavedSessionMarker
import dev.renkinProject.renkin.data.UnsavedSessionMarkerStore
import dev.renkinProject.renkin.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Unsaved icons live only in memory until Build or Save. This records how many there are, so a
 * process Android ended in the background is explained after restart instead of silently
 * showing the last saved set.
 */
@Singleton
class UnsavedSessionMonitor internal constructor(
    private val store: UnsavedSessionMarkerStore,
    private val currentInstance: () -> String,
    private val applicationState: Flow<ApplicationListState>,
    private val scope: CoroutineScope
) {
    @Inject constructor(store: UnsavedSessionMarkerStore, appProvider: ApplicationProvider) : this(
        store,
        { appProvider.runtimeState.value.sessionToken.providerInstanceId },
        appProvider.applicationState,
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    )

    private val started = AtomicBoolean(false)
    private val mutableLostSession = MutableStateFlow<UnsavedSessionMarker?>(null)
    val lostSession: StateFlow<UnsavedSessionMarker?> = mutableLostSession.asStateFlow()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            // Read before this process records its own state, which replaces the previous marker.
            // The same instance means only an Activity was recreated; its edits are still live.
            store.read()
                ?.takeIf { it.providerInstanceId != currentInstance() && it.hasChanges }
                ?.let { mutableLostSession.value = it }
            applicationState
                .mapNotNull { state -> state.unsavedSessionMarker() }
                .distinctUntilChanged()
                .conflate()
                .collect(::record)
        }
    }

    fun dismiss() {
        mutableLostSession.value = null
    }

    private suspend fun record(marker: UnsavedSessionMarker) {
        try {
            store.write(marker)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.error("UnsavedSessionMonitor", "Could not record unsaved session state", error)
        }
    }
}

internal fun ApplicationListState.unsavedSessionMarker(): UnsavedSessionMarker? {
    val owner = sessionToken?.takeIf { applicationsLoaded } ?: return null
    // Refresh commits also mark their rows dirty; only the transient flag tells them apart.
    val (refreshed, others) = applications.partition { it.isRefreshMade && it.createdIcon != null }
    return UnsavedSessionMarker(
        providerInstanceId = owner.providerInstanceId,
        profileId = owner.profileId,
        editedCount = others.count { it.key in unsavedIconKeys },
        refreshedCount = refreshed.size
    )
}
