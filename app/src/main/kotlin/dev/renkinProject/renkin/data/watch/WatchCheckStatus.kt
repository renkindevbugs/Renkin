package dev.renkinProject.renkin.data.watch

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.renkinProject.renkin.data.LastWatchCheckAtKey
import dev.renkinProject.renkin.data.StorageOperationGate
import dev.renkinProject.renkin.data.dataStore
import dev.renkinProject.renkin.data.getLongValue
import dev.renkinProject.renkin.data.getPreferenceFlow
import dev.renkinProject.renkin.data.getPreferencesAfterPendingWrites
import dev.renkinProject.renkin.data.setLongValue
import dev.renkinProject.renkin.data.setPreferenceValue
import kotlinx.coroutines.flow.map
import org.json.JSONObject

val WatchLastCheckResultKey = stringPreferencesKey("WATCH_LAST_CHECK_RESULT")

enum class WatchCheckOutcome { COMPLETE, PARTIAL, FAILED }

data class WatchCheckStatus(
    val checkedAt: Long,
    val outcome: WatchCheckOutcome,
    val foundCount: Int,
    val lastSuccessfulAt: Long
)

class WatchCheckStatusStore(context: Context) {
    private val preferences = context.applicationContext.dataStore

    val status = preferences.getPreferenceFlow(WatchLastCheckResultKey).map(::decode)

    suspend fun record(epoch: Long, outcome: WatchCheckOutcome, foundCount: Int = 0) = StorageOperationGate.run {
        if (epoch != StorageOperationGate.epoch) return@run
        val now = System.currentTimeMillis()
        val prefs = preferences.getPreferencesAfterPendingWrites()
        val previous = decode(prefs[WatchLastCheckResultKey])
        val lastSuccess = if (outcome == WatchCheckOutcome.COMPLETE) now
            else previous?.lastSuccessfulAt ?: prefs.getLongValue(LastWatchCheckAtKey)
        val encoded = JSONObject()
            .put("checkedAt", now)
            .put("outcome", outcome.name)
            .put("foundCount", foundCount.coerceAtLeast(0))
            .put("lastSuccessfulAt", lastSuccess)
            .toString()
        preferences.setPreferenceValue(WatchLastCheckResultKey, encoded)
        if (outcome == WatchCheckOutcome.COMPLETE) preferences.setLongValue(LastWatchCheckAtKey, now)
    }

    private fun decode(value: String?): WatchCheckStatus? = value?.let {
        runCatching {
            val json = JSONObject(it)
            WatchCheckStatus(
                json.getLong("checkedAt").also { at -> require(at > 0) },
                WatchCheckOutcome.valueOf(json.getString("outcome")),
                json.optInt("foundCount").coerceAtLeast(0),
                json.optLong("lastSuccessfulAt").coerceAtLeast(0)
            )
        }.getOrNull()
    }
}
