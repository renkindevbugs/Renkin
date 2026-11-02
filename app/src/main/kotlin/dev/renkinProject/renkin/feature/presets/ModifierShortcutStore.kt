package dev.renkinProject.renkin.feature.presets

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.renkinProject.renkin.data.RenkinPackRepository
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class ModifierShortcutUsage(
    val profileId: Long = 0,
    val recentIds: List<Long> = emptyList(),
    val unusedApplies: Int = 0,
    val dismissed: Boolean = false
) {
    val visible: Boolean get() = recentIds.isNotEmpty() && unusedApplies < 4 && !dismissed
}

data class ModifierShortcutApply(val usedModifiers: Boolean, val presetId: Long?)

/** Device-local usage hints, kept outside profile recipes and portable backups. */
@Singleton
class ModifierShortcutStore @Inject constructor(
    @ApplicationContext context: Context,
    private val repository: RenkinPackRepository
) {
    private val preferences = context.getSharedPreferences("modifier_shortcuts", Context.MODE_PRIVATE)
    private val writes = Mutex()

    fun observe(profileId: Long) = callbackFlow {
        val key = profileId.toString()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changed ->
            if (changed == key) trySend(read(profileId))
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(read(profileId))
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }.flowOn(Dispatchers.IO)

    suspend fun dismiss(profileId: Long) = withContext(Dispatchers.IO) {
        writes.withLock { write(read(profileId).copy(dismissed = true)) }
    }

    suspend fun recordApply(profileId: Long, apply: ModifierShortcutApply) = withContext(Dispatchers.IO) {
        writes.withLock {
            val previous = read(profileId)
            val next = if (apply.usedModifiers) previous.copy(
                recentIds = apply.presetId?.let { (listOf(it) + previous.recentIds).distinct().take(2) }
                    ?: previous.recentIds,
                unusedApplies = 0,
                dismissed = false
            ) else previous.copy(unusedApplies = (previous.unusedApplies + 1).coerceAtMost(4))
            write(next)
            if (apply.usedModifiers) apply.presetId?.let { repository.markModifierPresetUsed(it) }
        }
    }

    private fun read(profileId: Long): ModifierShortcutUsage {
        val document = try {
            JSONObject(preferences.getString(profileId.toString(), "{}").orEmpty())
        } catch (_: org.json.JSONException) { JSONObject() }
        val recent = document.optJSONArray("recent") ?: JSONArray()
        return ModifierShortcutUsage(
            profileId,
            (0 until recent.length()).map { recent.optLong(it) }.filter { it > 0 }.distinct().take(2),
            document.optInt("unused").coerceIn(0, 4),
            document.optBoolean("dismissed")
        )
    }

    // KTX edit discards commit's result; a failed durable write must reach the caller.
    @android.annotation.SuppressLint("UseKtx")
    private fun write(state: ModifierShortcutUsage) {
        val document = JSONObject().put("recent", JSONArray(state.recentIds))
            .put("unused", state.unusedApplies).put("dismissed", state.dismissed)
        if (!preferences.edit().putString(state.profileId.toString(), document.toString()).commit()) {
            throw IOException("Modifier shortcut usage could not be saved")
        }
    }
}
