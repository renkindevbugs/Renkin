package dev.renkinProject.renkin.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import dev.renkinProject.renkin.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.withContext

internal data class ProfileStyleUpdate(val profileId: Long, val document: String, val legacySnapshot: String)

/** Room owns profile styles; DataStore persists application settings and the active profile id. */
class ProfileStyleStore internal constructor(
    private val repository: RenkinPackRepository,
    private val preferences: DataStore<Preferences>
) {
    private var initializedEpoch: Long? = null
    private data class StyleProjection(val document: String, val values: Preferences)
    private var cachedProjection: StyleProjection? = null
    private data class ObservedPreferences(val epoch: Long, val values: Preferences)
    // This singleton's sharing scope belongs to the application, not to an individual collector.
    private val observationScope = lazy { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    private val observations by lazy {
        val snapshots = combine(repository.profilesFlow(), preferences.data) { _, _ -> Unit }
            .conflate()
            .mapNotNull { snapshotForObservation() }
            .distinctUntilChanged()
            .catch { error ->
                if (error is CancellationException || error !is Exception) throw error
                if (error !is StorageRecoveryUnavailableException) {
                    Log.error("ProfileStyleStore", "Cannot observe profile settings", error)
                    StorageOperationGate.requireRecovery(error)
                }
            }
        StorageOperationGate.observe(snapshots).conflate().shareIn(
            observationScope.value,
            SharingStarted.WhileSubscribed(stopTimeoutMillis = 0, replayExpirationMillis = 0),
            replay = 1
        )
    }

    private suspend fun <T> access(operation: suspend () -> T): T = StorageOperationGate.run {
        withContext(Dispatchers.Default) { operation() }
    }

    internal fun owns(store: DataStore<Preferences>): Boolean = store === preferences

    internal suspend fun initialize(force: Boolean = false) = access {
        if (!force && initializedEpoch == StorageOperationGate.epoch) return@access
        repository.ensureDefaultProfile()
        val stored = preferences.data.first()
        val profiles = repository.profiles()
        val activeId = stored[ActiveProfileIdKey]?.takeIf { id -> profiles.any { it.id == id } } ?: DEFAULT_PROFILE_ID
        // Validate every candidate before committing any conversion. The active legacy row
        // can be stale: only its live preference projection represents the pre-cutover state.
        val candidates = profiles.map { profile ->
            val style = if (profile.profileStyle.isNotEmpty()) decode(profile)
            else if (profile.id == activeId) ProfileStylePreferences.capture(stored)
            else legacyStyle(profile.prefsSnapshot)
            update(profile.id, style, profile.profileStyle.takeIf(String::isNotEmpty))
        }
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) {
            val changed = candidates.zip(profiles).filter { (candidate, profile) ->
                candidate.document != profile.profileStyle || candidate.legacySnapshot != profile.prefsSnapshot
            }.map { it.first }
            if (changed.isNotEmpty()) repository.writeProfileStyles(changed)
            persistApplicationPreferences(activeId, blockOnFailure = false)
            initializedEpoch = StorageOperationGate.epoch
        }
    }

    internal suspend fun snapshot(): Preferences = access {
        initialize()
        val stored = preferences.data.first()
        val activeId = stored[ActiveProfileIdKey] ?: DEFAULT_PROFILE_ID
        val profile = repository.profile(activeId)
            ?: throw StorageRecoverySnapshotException("Active profile $activeId does not exist")
        val values = profilePreferences(profile)
        stored.toMutablePreferences().apply {
            ProfilePrefKeys.forEach { remove(it) }
            this += values
            this[ActiveProfileIdKey] = activeId
        }.toPreferences()
    }

    internal fun observe(): Flow<Preferences> = StorageOperationGate.observe(observations)
        .filter { it.epoch == StorageOperationGate.epoch }
        .map { it.values }
        .distinctUntilChanged()

    private suspend fun snapshotForObservation(): ObservedPreferences? = access {
        try {
            ObservedPreferences(StorageOperationGate.epoch, snapshot())
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            // Report while still holding the gate so a later successful restore cannot be
            // marked failed by an exception from an older read.
            initializedEpoch = null
            Log.error("ProfileStyleStore", "Cannot read observed profile settings", error)
            StorageOperationGate.requireRecovery(error)
            null
        }
    }

    internal fun closeObservations() {
        if (observationScope.isInitialized()) observationScope.value.cancel()
    }

    private fun profilePreferences(profile: Profile): Preferences {
        // Cache only the pure conversion. Every read still resolves the current row under the gate.
        cachedProjection?.takeIf { it.document == profile.profileStyle }?.let { return it.values }
        return ProfileStylePreferences.toPreferences(decode(profile)).also {
            cachedProjection = StyleProjection(profile.profileStyle, it)
        }
    }

    internal suspend fun edit(transform: (MutablePreferences) -> Unit) = access {
        val before = snapshot()
        val target = before.toMutablePreferences().apply(transform)
        val activeId = before[ActiveProfileIdKey] ?: DEFAULT_PROFILE_ID
        val requestedId = target[ActiveProfileIdKey] ?: DEFAULT_PROFILE_ID
        val selected = checkNotNull(repository.profile(requestedId)) { "Target profile no longer exists" }
        val desired = if (requestedId == activeId) ProfileStylePreferences.capture(target) else {
            require(ProfileStylePreferences.capture(before) == ProfileStylePreferences.capture(target)) {
                "A profile switch cannot edit the departing style"
            }
            decode(selected)
        }
        val unchanged = requestedId != activeId || desired == ProfileStylePreferences.capture(before)
        val change = update(requestedId, desired, selected.profileStyle.takeIf { unchanged })
        val writesRoom = selected.profileStyle != change.document || selected.prefsSnapshot != change.legacySnapshot
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) {
            if (writesRoom) {
                repository.writeProfileStyles(listOf(change))
            }
            persistApplicationPreferences(requestedId, target, blockOnFailure = writesRoom)
        }
    }

    internal suspend fun switchTo(profileId: Long) = access {
        initialize()
        val profile = checkNotNull(repository.profile(profileId)) { "Target profile no longer exists" }
        decode(profile)
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) { persistApplicationPreferences(profileId, blockOnFailure = false) }
    }

    /** Only the confirmed keep-current recovery action may replace a non-empty invalid document. */
    internal suspend fun repairInvalidDocuments(preserveOriginals: suspend () -> Unit) = access {
        val repairs = repository.profiles().filter { it.profileStyle.isNotEmpty() }.mapNotNull { profile ->
            try {
                decode(profile)
                null
            } catch (error: StorageRecoverySnapshotException) {
                Log.error("ProfileStyleStore", "Repairing invalid style for profile ${profile.id} from its saved legacy copy", error)
                update(profile.id, ProfileStylePreferences.decodeLegacySnapshot(profile.prefsSnapshot))
            }
        }
        if (repairs.isEmpty()) return@access
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) {
            preserveOriginals()
            repository.writeProfileStyles(repairs)
            initializedEpoch = null
        }
    }

    internal suspend fun createProfile(profile: Profile): Long = access {
        initialize()
        val change = update(profile.id, legacyStyle(profile.prefsSnapshot))
        repository.createProfile(profile.copy(profileStyle = change.document, prefsSnapshot = change.legacySnapshot))
    }

    internal suspend fun recordBuiltPrimary(profileId: Long, source: Preferences) = access {
        initialize()
        val profile = repository.profile(profileId) ?: return@access
        val target = ProfileStylePreferences.toPreferences(decode(profile)).toMutablePreferences()
        target[BuiltPrimarySourceKey] = source.getIntValue(PrimarySourceKey, SOURCE_DEFAULT.ordinal)
        target[BuiltPrimaryIconPackKey] = source.getStringValue(PrimaryIconPackKey)
        val change = update(profileId, ProfileStylePreferences.capture(target))
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) {
            repository.writeProfileStyles(listOf(change))
        }
    }

    private fun decode(profile: Profile): ProfileStyle = try {
        require(profile.profileStyle.isNotEmpty()) { "Profile style has not been initialized" }
        ProfileStyleCodec.decode(profile.profileStyle)
    } catch (error: Exception) {
        throw StorageRecoverySnapshotException("Invalid style document for profile ${profile.id}", error)
    }

    private fun legacyStyle(snapshot: String): ProfileStyle =
        ProfileStylePreferences.capture(mutablePreferencesOf().apply {
            replaceProfilePrefs(snapshot)
        })

    private fun update(id: Long, style: ProfileStyle, originalDocument: String? = null): ProfileStyleUpdate =
        ProfileStyleUpdate(id, originalDocument ?: ProfileStyleCodec.encode(style),
            ProfileStylePreferences.toPreferences(style).snapshotProfilePrefs())

    private suspend fun persistApplicationPreferences(
        activeId: Long, applicationValues: Preferences? = null, blockOnFailure: Boolean = true
    ) {
        try {
            val stored = preferences.data.first()
            val target = (applicationValues ?: stored).toMutablePreferences().apply {
                ProfilePrefKeys.forEach { remove(it) }
                this[ActiveProfileIdKey] = activeId
            }.toPreferences()
            // Style-only commits wake the shared Room observer without a DataStore write.
            if (target == stored) return
            // All writers, including restore, hold the storage gate across this read and update.
            // The transform intentionally uses that frozen target instead of DataStore's current value.
            preferences.updateData { target }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            // A combined style/application edit may already have committed Room. Keep that
            // partial operation behind recovery, where Room remains authoritative.
            if (blockOnFailure) {
                initializedEpoch = null
                StorageOperationGate.requireRecovery(error)
            }
            throw error
        }
    }
}

/** Installed before WorkManager starts; legacy DataStore APIs delegate to the shared owner. */
internal object ProfileStyleAccess {
    @Volatile private var resolve: (() -> ProfileStyleStore)? = null

    fun install(owner: () -> ProfileStyleStore) {
        check(resolve == null) { "Profile style access is already installed" }
        resolve = owner
    }

    fun ownerFor(store: DataStore<Preferences>): ProfileStyleStore? = resolve?.invoke()?.takeIf { it.owns(store) }

    suspend fun initialize(force: Boolean = false) { resolve?.invoke()?.initialize(force) }

    suspend fun repairInvalidDocuments(preserveOriginals: suspend () -> Unit) {
        resolve?.invoke()?.repairInvalidDocuments(preserveOriginals)
    }

    internal fun resetForTests() {
        resolve?.invoke()?.closeObservations()
        resolve = null
    }
}
