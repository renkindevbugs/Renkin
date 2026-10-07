package dev.renkinProject.renkin.data

import android.util.JsonReader
import android.util.JsonToken
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.mutablePreferencesOf
import dev.renkinProject.renkin.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.StringReader

internal data class ProfileStyleUpdate(val profileId: Long, val document: String, val legacySnapshot: String)

/** Room owns profile values; DataStore retains a derived projection for existing UI readers. */
class ProfileStyleStore internal constructor(
    private val repository: RenkinPackRepository,
    private val preferences: DataStore<Preferences>
) {
    private var initializedEpoch: Long? = null

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
            publishProjection(candidates.single { it.profileId == activeId }.legacySnapshot, activeId, blockOnFailure = false)
            initializedEpoch = StorageOperationGate.epoch
        }
    }

    internal suspend fun snapshot(): Preferences = access {
        initialize()
        preferences.data.first()
    }

    internal suspend fun edit(transform: (MutablePreferences) -> Unit) = access {
        val before = snapshot()
        val target = before.toMutablePreferences().apply(transform)
        val activeId = before[ActiveProfileIdKey] ?: DEFAULT_PROFILE_ID
        val requestedId = target[ActiveProfileIdKey] ?: DEFAULT_PROFILE_ID
        val current = checkNotNull(repository.profile(activeId)) { "Active profile no longer exists" }
        val desired = if (requestedId == activeId) ProfileStylePreferences.capture(target) else {
            require(ProfileStylePreferences.capture(before) == ProfileStylePreferences.capture(target)) {
                "A profile switch cannot edit the departing style"
            }
            decode(checkNotNull(repository.profile(requestedId)) { "Target profile no longer exists" })
        }
        val unchanged = requestedId == activeId && desired == ProfileStylePreferences.capture(before)
        val change = update(requestedId, desired, current.profileStyle.takeIf { unchanged })
        val writesRoom = requestedId != activeId || current.profileStyle != change.document || current.prefsSnapshot != change.legacySnapshot
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) {
            if (writesRoom) {
                repository.writeProfileStyles(listOf(change))
            }
            publishProjection(change.legacySnapshot, requestedId, target, blockOnFailure = writesRoom)
        }
    }

    internal suspend fun switchTo(profileId: Long) = access {
        initialize()
        val profile = checkNotNull(repository.profile(profileId)) { "Target profile no longer exists" }
        val change = update(profileId, decode(profile), profile.profileStyle)
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) { publishProjection(change.legacySnapshot, profileId, blockOnFailure = false) }
    }

    /** Only the confirmed keep-current recovery action may replace a non-empty invalid document. */
    internal suspend fun repairInvalidDocuments(preserveOriginals: suspend () -> Unit) = access {
        val repairs = repository.profiles().filter { it.profileStyle.isNotEmpty() }.mapNotNull { profile ->
            try {
                decode(profile)
                null
            } catch (error: StorageRecoverySnapshotException) {
                Log.error("ProfileStyleStore", "Repairing invalid style for profile ${profile.id} from its saved legacy copy", error)
                update(profile.id, validatedLegacyStyle(profile.prefsSnapshot))
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
            val activeId = preferences.data.first()[ActiveProfileIdKey] ?: DEFAULT_PROFILE_ID
            if (activeId == profileId) publishProjection(change.legacySnapshot, activeId)
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

    private fun validatedLegacyStyle(snapshot: String): ProfileStyle {
        require(snapshot.length <= ProfileStyleCodec.MAX_DOCUMENT_CHARS) { "Legacy style is too large" }
        snapshot.requireProfileUnicode()
        require(snapshot.toByteArray(Charsets.UTF_8).size <= ProfileStyleCodec.MAX_DOCUMENT_BYTES) { "Legacy style is too large" }
        val values = JSONObject()
        val keys = ProfilePrefKeys.map { it.name }.toSet()
        JsonReader(StringReader(snapshot)).use { reader ->
            reader.isLenient = false
            reader.beginObject()
            val seen = mutableSetOf<String>()
            while (reader.hasNext()) {
                val name = reader.nextName()
                require(seen.add(name)) { "Duplicate legacy style field" }
                require(name in keys) { "Unknown legacy style field" }
                val value: Any = when (reader.peek()) {
                    JsonToken.BOOLEAN -> reader.nextBoolean()
                    JsonToken.NUMBER -> reader.profileInt(name)
                    JsonToken.STRING -> reader.nextString()
                    else -> throw IllegalArgumentException("Invalid legacy style value")
                }
                require(isValidPersistedPreference(name, value)) { "Invalid legacy style type" }
                values.put(name, value)
            }
            reader.endObject()
            require(reader.peek() == JsonToken.END_DOCUMENT) { "Trailing legacy style content" }
        }
        return legacyStyle(values.toString())
    }

    private fun update(id: Long, style: ProfileStyle, originalDocument: String? = null): ProfileStyleUpdate =
        ProfileStyleUpdate(id, originalDocument ?: ProfileStyleCodec.encode(style),
            ProfileStylePreferences.toPreferences(style).snapshotProfilePrefs())

    private suspend fun publishProjection(
        snapshot: String, activeId: Long, applicationValues: Preferences? = null, blockOnFailure: Boolean = true
    ) {
        try {
            preferences.edit { target ->
                if (applicationValues != null) {
                    target.clear()
                    target += applicationValues
                }
                target.replaceProfilePrefs(snapshot)
                target[ActiveProfileIdKey] = activeId
            }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            // Room may already have committed. Block readers/writers until startup recovery
            // re-projects that authoritative row instead of exposing an obsolete UI cache.
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

    internal fun resetForTests() { resolve = null }
}
