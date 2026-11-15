package dev.renkinProject.renkin.service

import android.content.Context
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.data.RenkinPackRepository
import dev.renkinProject.renkin.data.StorageOperationGate
import dev.renkinProject.renkin.data.watch.WatchRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class IconAvailableSummaryEntry(val appName: String, val profileName: String, val profileId: Long = -1L)

/** Read all unresolved suggestions, rather than only those produced by the latest scan. */
internal suspend fun WatchRepository.iconAvailableSummaryEntries(
    context: Context,
    excludedSuggestionIds: Collection<Long> = emptyList()
): List<IconAvailableSummaryEntry> = StorageOperationGate.run {
    withContext(Dispatchers.Default) {
        val profiles = RenkinPackRepository(context).profiles().associate { it.id to it.name }
        getAllRules().flatMap { rule ->
            rule.suggestions.filterNot { it.id in excludedSuggestionIds }.map { suggestion ->
                val appName = try {
                    context.packageManager.getApplicationLabel(
                        context.packageManager.getApplicationInfo(suggestion.packageName, 0)
                    ).toString()
                } catch (_: Exception) {
                    suggestion.packageName
                }
                IconAvailableSummaryEntry(
                    appName,
                    profiles[rule.rule.profileId] ?: context.getString(R.string.profileDeleted),
                    rule.rule.profileId
                )
            }
        }
    }
}
