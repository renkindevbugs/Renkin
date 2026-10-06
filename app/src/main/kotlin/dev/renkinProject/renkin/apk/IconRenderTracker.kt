package dev.renkinProject.renkin.apk

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import dev.renkinProject.renkin.data.BuiltPrimaryIconPackKey
import dev.renkinProject.renkin.data.BuiltPrimarySourceKey
import dev.renkinProject.renkin.data.ProfilePrefKeys
import dev.renkinProject.renkin.data.GlobalApplyGeneratedKey
import dev.renkinProject.renkin.data.getBooleanValue
import dev.renkinProject.renkin.icon.creator.GenerationOptions
import dev.renkinProject.renkin.icon.creator.globalModifierOptions

internal data class IconRenderTicket(
    val key: String,
    val requestRevision: Long,
    val mutationRevision: Long
)

/** Accessed only while the provider's profile-operation gate is held. */
internal class IconRenderTracker {
    private var nextRequest = 0L
    private val requests = mutableMapOf<String, Long>()
    private val mutations = mutableMapOf<String, Long>()

    fun reserve(key: String): IconRenderTicket {
        val request = ++nextRequest
        requests[key] = request
        return IconRenderTicket(key, request, mutations[key] ?: 0L)
    }

    fun observe(key: String): IconRenderTicket =
        IconRenderTicket(key, requests[key] ?: 0L, mutations[key] ?: 0L)

    fun isCurrent(ticket: IconRenderTicket): Boolean =
        (requests[ticket.key] ?: 0L) == ticket.requestRevision &&
            (mutations[ticket.key] ?: 0L) == ticket.mutationRevision

    fun changed(key: String) {
        mutations[key] = (mutations[key] ?: 0L) + 1L
    }

    fun clear() {
        requests.clear()
        mutations.clear()
    }
}

internal fun sameProfilePreferences(first: Preferences, second: Preferences): Boolean {
    val left = first.asMap()
    val right = second.asMap()
    return ProfilePrefKeys.filterNot { it == BuiltPrimarySourceKey || it == BuiltPrimaryIconPackKey }
        .all { left[it] == right[it] }
}

internal fun sameRenderPreferences(first: Preferences, second: Preferences, context: Context): Boolean =
    GenerationOptions.fromPreferences(first, context) == GenerationOptions.fromPreferences(second, context) &&
        globalModifierOptions(first) == globalModifierOptions(second) &&
        first.getBooleanValue(GlobalApplyGeneratedKey, true) == second.getBooleanValue(GlobalApplyGeneratedKey, true)
