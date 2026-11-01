package dev.renkinProject.renkin.service

import android.content.Context
import dev.renkinProject.renkin.data.RenkinPackRepository
import dev.renkinProject.renkin.data.StorageOperationGate
import dev.renkinProject.renkin.data.watch.WatchRepository

/**
 * Deletes watch rules together with their posted notifications. The notifications are cancelled
 * while the suggestion rows (and so their ids) still exist: an interruption before the delete
 * leaves everything needed to repeat it, and cancelling twice is harmless.
 */
internal suspend fun WatchRepository.deleteRulesAndNotifications(context: Context, ruleIds: List<Long>) {
    if (ruleIds.isEmpty()) return
    StorageOperationGate.run {
        val notifications = RenkinNotifications()
        notifications.cancelIconAvailable(context, suggestionIdsForRules(ruleIds), cancelSummary = false)
        deleteRules(ruleIds)
        if (suggestionCount() == 0) notifications.cancelIconAvailable(context, emptyList(), cancelSummary = true)
    }
}

/**
 * Removes the watch rules of profiles that no longer exist. The profile row and the watch rules
 * live in two databases without a shared transaction, so a profile deletion interrupted between
 * them leaves rules the worker would otherwise keep checking and notifying for.
 */
internal suspend fun removeWatchRulesOfDeletedProfiles(
    context: Context,
    watchRepo: WatchRepository,
    packRepo: RenkinPackRepository
) = StorageOperationGate.run {
    val profileIds = packRepo.profiles().map { it.id }
    // The default profile always exists; no profiles means the store isn't readable yet.
    if (profileIds.isEmpty()) return@run
    watchRepo.deleteRulesAndNotifications(context, watchRepo.ruleIdsOutsideProfiles(profileIds))
}
