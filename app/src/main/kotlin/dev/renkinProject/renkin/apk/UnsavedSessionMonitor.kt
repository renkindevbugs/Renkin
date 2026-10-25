package dev.renkinProject.renkin.apk

import dev.renkinProject.renkin.data.DbApplication
import dev.renkinProject.renkin.data.UnsavedIconJournal
import dev.renkinProject.renkin.data.UnsavedIconJournalEntry
import dev.renkinProject.renkin.data.UnsavedIconJournalStore
import dev.renkinProject.renkin.data.UnsavedSessionMarker
import dev.renkinProject.renkin.data.UnsavedSessionMarkerStore
import dev.renkinProject.renkin.packages.PackageInfoStruct
import dev.renkinProject.renkin.util.Log
import dev.renkinProject.renkin.util.runCatchingCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** What an ended process left behind: its unsaved counts and, when kept, its hand-made icons. */
data class LostUnsavedWork(val marker: UnsavedSessionMarker?, val offer: UnsavedIconJournal?) {
    val restorableCount: Int get() = offer?.entries?.size ?: 0

    /** Refresh output belonging to the offered journal's process, or to the lost marker alone. */
    val refreshedCount: Int
        get() = marker?.takeIf { offer == null || it.providerInstanceId == offer.providerInstanceId }
            ?.refreshedCount ?: 0
}

/**
 * Unsaved icons live only in memory until Build or Save. This records how many there are, and
 * journals the hand-made ones, so a process Android ended in the background can be explained,
 * and its hand-made icons offered back, after restart.
 */
@Singleton
class UnsavedSessionMonitor internal constructor(
    private val store: UnsavedSessionMarkerStore,
    private val journal: UnsavedIconJournalStore,
    private val currentInstance: () -> String,
    private val applicationState: Flow<ApplicationListState>,
    private val encode: suspend (Long, List<PackageInfoStruct>) -> List<DbApplication?>,
    private val scope: CoroutineScope,
    private val journalDelayMs: Long = JOURNAL_DELAY_MS
) {
    @Inject constructor(
        store: UnsavedSessionMarkerStore,
        journal: UnsavedIconJournalStore,
        appProvider: ApplicationProvider
    ) : this(
        store,
        journal,
        { appProvider.runtimeState.value.sessionToken.providerInstanceId },
        appProvider.applicationState,
        appProvider::encodeUnsavedIcons,
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    )

    private val started = AtomicBoolean(false)
    private val mutableLostWork = MutableStateFlow<LostUnsavedWork?>(null)
    val lostWork: StateFlow<LostUnsavedWork?> = mutableLostWork.asStateFlow()

    private val journalWrites = Mutex()
    @Volatile private var pendingJournal: JournalSnapshot? = null
    private var writtenJournal: JournalSnapshot? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            val instance = currentInstance()
            // Read before this process records its own state, which replaces the previous one.
            // The same instance means only an Activity was recreated; its edits are still live.
            val marker = store.read()?.takeIf { it.providerInstanceId != instance && it.hasChanges }
            val offer = runCatchingCancellable { journal.takeOverPrevious(instance) }
                .onFailure { Log.error(TAG, "Could not read the unsaved icon journal", it) }
                .getOrNull()
            if (marker != null || offer != null) mutableLostWork.value = LostUnsavedWork(marker, offer)
            launch {
                applicationState
                    .mapNotNull { state -> state.unsavedSessionMarker() }
                    .distinctUntilChanged()
                    .conflate()
                    .collect(::record)
            }
            applicationState
                .mapNotNull { state -> state.journalSnapshot() }
                .distinctUntilChanged(JournalSnapshot::sameAs)
                .collectLatest { snapshot ->
                    pendingJournal = snapshot
                    delay(journalDelayMs)
                    writeJournal(snapshot)
                }
        }
    }

    /** Writes pending journal changes now; the app is leaving the foreground. */
    fun flush() {
        if (!started.get()) return
        scope.launch { pendingJournal?.let { writeJournal(it) } }
    }

    fun dismiss() {
        mutableLostWork.value = null
    }

    /** The offer was restored or discarded: forget it here and on disk. */
    suspend fun closeOffer() {
        mutableLostWork.value = null
        journal.discardOffer()
    }

    private suspend fun record(marker: UnsavedSessionMarker) {
        try {
            store.write(marker)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.error(TAG, "Could not record unsaved session state", error)
        }
    }

    // A newer snapshot cancels the delay, never a write already under way.
    private suspend fun writeJournal(snapshot: JournalSnapshot) = withContext(NonCancellable) {
        journalWrites.withLock {
            if (writtenJournal?.sameAs(snapshot) == true) return@withLock
            try {
                val rows = encode(snapshot.owner.profileId, snapshot.apps)
                journal.writeCurrent(
                    UnsavedIconJournal(
                        snapshot.owner.providerInstanceId,
                        snapshot.owner.profileId,
                        snapshot.apps.zip(rows) { app, row -> UnsavedIconJournalEntry(app.key, row) }
                    )
                )
                writtenJournal = snapshot
            } catch (error: Exception) {
                Log.error(TAG, "Could not journal unsaved icons", error)
            }
        }
    }

    private companion object {
        const val TAG = "UnsavedSessionMonitor"
        const val JOURNAL_DELAY_MS = 1_000L
    }
}

/** Hand-made unsaved rows of one session, compared by identity: published rows are immutable. */
internal class JournalSnapshot(val owner: ProfileSessionToken, val apps: List<PackageInfoStruct>) {
    fun sameAs(other: JournalSnapshot): Boolean =
        owner == other.owner && apps.size == other.apps.size && apps.indices.all { apps[it] === other.apps[it] }
}

internal fun ApplicationListState.journalSnapshot(): JournalSnapshot? {
    val owner = sessionToken?.takeIf { applicationsLoaded } ?: return null
    return JournalSnapshot(owner, applications.filter { it.key in unsavedIconKeys && !it.isRefreshMade })
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
