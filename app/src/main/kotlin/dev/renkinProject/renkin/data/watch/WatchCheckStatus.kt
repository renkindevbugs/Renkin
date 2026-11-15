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
import org.json.JSONArray

val WatchLastCheckResultKey = stringPreferencesKey("WATCH_LAST_CHECK_RESULT")

enum class WatchCheckOutcome { COMPLETE, PARTIAL, FAILED }

data class WatchCheckStatus(
    val checkedAt: Long,
    val outcome: WatchCheckOutcome,
    val foundCount: Int,
    val lastSuccessfulAt: Long,
    val failedPackNames: List<String> = emptyList(),
    val readIssues: List<WatchReadIssue> = emptyList()
)

class WatchCheckStatusStore(context: Context) {
    private val preferences = context.applicationContext.dataStore

    val status = preferences.getPreferenceFlow(WatchLastCheckResultKey).map(::decode)

    suspend fun record(
        epoch: Long, outcome: WatchCheckOutcome, foundCount: Int = 0,
        failedPackNames: List<String> = emptyList(),
        readIssues: List<WatchReadIssue> = emptyList()
    ) = StorageOperationGate.run {
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
            .put("failedPackNames", JSONArray(failedPackNames.distinct()))
            .put("readIssues", JSONArray().apply {
                readIssues.distinct().forEach { issue ->
                    put(JSONObject()
                        .put("packPackage", issue.packPackage)
                        .put("packName", issue.packName)
                        .put("reason", issue.reason.name)
                        .put("appPackage", issue.appPackage)
                        .put("activityName", issue.activityName)
                        .put("appName", issue.appName)
                        .put("drawableName", issue.drawableName))
                }
            })
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
                json.optLong("lastSuccessfulAt").coerceAtLeast(0),
                json.optJSONArray("failedPackNames")?.let { names ->
                    List(names.length()) { index -> names.optString(index) }.filter { it.isNotBlank() }
                }.orEmpty(),
                json.optJSONArray("readIssues")?.let { issues ->
                    (0 until issues.length()).mapNotNull { index ->
                        issues.optJSONObject(index)?.let(::decodeIssue)
                    }.distinct()
                }.orEmpty()
            )
        }.getOrNull()
    }

    private fun decodeIssue(json: JSONObject): WatchReadIssue? {
        val pack = json.stringOrNull("packPackage") ?: return null
        val reason = WatchReadFailureReason.entries.firstOrNull { it.name == json.optString("reason") }
            ?: WatchReadFailureReason.ICON_UNREADABLE
        return WatchReadIssue(
            packPackage = pack,
            reason = reason,
            packName = json.stringOrNull("packName") ?: pack,
            appPackage = json.stringOrNull("appPackage"),
            activityName = json.stringOrNull("activityName"),
            appName = json.stringOrNull("appName"),
            drawableName = json.stringOrNull("drawableName")
        )
    }
}

private fun JSONObject.stringOrNull(key: String): String? = (opt(key) as? String)?.takeIf { it.isNotBlank() }
