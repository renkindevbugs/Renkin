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
import java.util.IdentityHashMap
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
    private var encodedRows = IdentityHashMap<PackageInfoStruct, DbApplication?>()

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
                val previous = encodedRows.takeIf { writtenJournal?.owner == snapshot.owner }
                val changed = snapshot.apps.filter { previous?.containsKey(it) != true }
                val changedRows = if (changed.isEmpty()) emptyList() else encode(snapshot.owner.profileId, changed)
                check(changedRows.size == changed.size) { "Journal encoder returned a different row count" }
                val next = IdentityHashMap<PackageInfoStruct, DbApplication?>()
                changed.forEachIndexed { index, app -> next[app] = changedRows[index] }
                snapshot.apps.forEach { app ->
                    if (!next.containsKey(app)) next[app] = previous?.get(app)
                }
                journal.writeCurrent(
                    UnsavedIconJournal(
                        snapshot.owner.providerInstanceId,
                        snapshot.owner.profileId,
                        snapshot.apps.map { app -> UnsavedIconJournalEntry(app.key, next[app]) }
                    )
                )
                writtenJournal = snapshot
                var cachedChars = 0L
                for (app in snapshot.apps) {
                    val row = next[app]
                    val chars = row?.let {
                        it.drawable.length.toLong() + it.baseDrawable.length + it.modifierInput.length
                    } ?: 0L
                    if (chars > MAX_ENCODED_CACHE_CHARS - cachedChars) next.remove(app)
                    else cachedChars += chars
                }
                encodedRows = next
            } catch (error: Exception) {
                Log.error(TAG, "Could not journal unsaved icons", error)
            } catch (error: OutOfMemoryError) {
                encodedRows.clear()
                Log.error(TAG, "Unsaved icon journal exceeded available memory", error)
            }
        }
    }

    private companion object {
        const val TAG = "UnsavedSessionMonitor"
        const val JOURNAL_DELAY_MS = 1_000L
        const val MAX_ENCODED_CACHE_CHARS = 8L * 1024 * 1024
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
