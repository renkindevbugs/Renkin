package dev.renkinProject.renkin.feature.watch

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingPeriodicWorkPolicy
import dev.renkinProject.renkin.data.WATCH_CHECK_INTERVAL_DEFAULT
import dev.renkinProject.renkin.data.LastWatchCheckAtKey
import dev.renkinProject.renkin.data.WatchCheckIntervalKey
import dev.renkinProject.renkin.data.StorageOperationGate
import dev.renkinProject.renkin.data.getPreferencesAfterPendingWrites
import dev.renkinProject.renkin.data.getIntValue
import dev.renkinProject.renkin.data.normalizeWatchCheckInterval
import dev.renkinProject.renkin.data.getPreferenceFlow
import dev.renkinProject.renkin.data.setIntValue
import dev.renkinProject.renkin.data.setLongValue
import dev.renkinProject.renkin.data.dataStore
import dev.renkinProject.renkin.data.watch.AppComponent
import dev.renkinProject.renkin.data.watch.IconSuggestion
import dev.renkinProject.renkin.data.watch.IconSuggestionCandidate
import dev.renkinProject.renkin.data.watch.RuleWithDetails
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.renkinProject.renkin.apk.ApplicationProvider
import dev.renkinProject.renkin.apk.ProfileSessionToken
import dev.renkinProject.renkin.data.watch.WatchRepository
import dev.renkinProject.renkin.service.WatchChecker
import dev.renkinProject.renkin.util.Log
import dev.renkinProject.renkin.service.RenkinNotifications
import dev.renkinProject.renkin.service.WatchWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Owns the icon-watch domain (rule CRUD, manual checks) so the watch UI is plain
 * composables that call functions and read state, instead of spinning up their own
 * coroutine scopes and reaching for the repository / checker / worker directly.
 */
@HiltViewModel
class WatchViewModel @Inject constructor(
    application: Application,
    private val repo: WatchRepository,
    private val appProvider: ApplicationProvider
) : AndroidViewModel(application) {

    // Revision/readiness changes keep the same Room subscription until the profile id changes.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun <T> perProfile(source: (Long) -> kotlinx.coroutines.flow.Flow<T>) =
        appProvider.runtimeState.map { it.activeProfileId }.distinctUntilChanged()
            .flatMapLatest(source)

    /** The active profile's watch rules (active + completed); the UI splits them by `completed`. */
    val rules: StateFlow<List<RuleWithDetails>> =
        perProfile { repo.rules(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Completed rules of the active profile — drives the bell badge on the home screen. */
    val completedCount: StateFlow<Int> =
        perProfile { repo.completedCount(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** Epoch-millis of the next scheduled periodic check; null when none is pending. */
    val nextCheckAt: StateFlow<Long?> =
        WatchWorker.nextScheduledCheckFlow(application)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Configured periodic check interval in minutes (24h default; debug can lower it). */
    val checkIntervalMinutes: StateFlow<Int> =
        application.dataStore.getPreferenceFlow(WatchCheckIntervalKey)
            .map { normalizeWatchCheckInterval(it ?: WATCH_CHECK_INTERVAL_DEFAULT) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), WATCH_CHECK_INTERVAL_DEFAULT)

    /** Debug: change how often the periodic check runs and reschedule it immediately. */
    fun setCheckIntervalMinutes(minutes: Int) {
        viewModelScope.launch {
            try {
                StorageOperationGate.run {
                    val app = getApplication<Application>()
                    val normalized = normalizeWatchCheckInterval(minutes)
                    app.dataStore.setIntValue(WatchCheckIntervalKey, normalized)
                    WatchWorker.schedulePeriodic(app, normalized, ExistingPeriodicWorkPolicy.UPDATE)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.error("WatchViewModel", "Could not update the check interval", error)
            }
        }
    }

    /** True while a manual check is running; drives the pull-to-refresh spinner. */
    var isChecking by mutableStateOf(false)
        private set

    /** True while a rule and its baseline are being resolved and committed. */
    var isSavingRule by mutableStateOf(false)
        private set

    /** Atomically creates or updates a rule together with its current icon baseline. */
    fun saveRule(
        existing: RuleWithDetails?,
        apps: List<AppComponent>,
        watchAll: Boolean,
        packs: List<String>,
        onSaved: () -> Unit = {}
    ) {
        if (isSavingRule) return
        isSavingRule = true
        val profileId = appProvider.activeProfileId
        val expected = appProvider.sessionToken
        viewModelScope.launch {
            // Saving reads every selected pack; a broken one must surface as "not saved",
            // never as a crash out of viewModelScope.
            val savedId = try {
                if (!appProvider.isCurrentSession(expected)) return@launch
                WatchChecker(getApplication()).saveRule(
                    existingRuleId = existing?.rule?.id,
                    apps = apps,
                    watchAllPacks = watchAll,
                    packPackages = packs,
                    profileId = profileId,
                    commit = { save -> if (appProvider.isCurrentSession(expected)) save() else 0L }
                )
            } catch (error: CancellationException) {
                throw error
            } catch (e: Exception) {
                Log.error("WatchViewModel", "Could not save the watch rule", e)
                0L
            } finally {
                isSavingRule = false
            }
            if (savedId > 0L) onSaved()
        }
    }

    fun deleteRule(ruleId: Long) {
        val expected = appProvider.sessionToken
        viewModelScope.launch { deleteRulesAndNotifications(listOf(ruleId), expected) }
    }

    fun deleteCompleted() {
        val expected = appProvider.sessionToken
        val ids = rules.value.filter { it.rule.completed }.map { it.rule.id }
        viewModelScope.launch {
            deleteRulesAndNotifications(ids, expected)
        }
    }

    private suspend fun deleteRulesAndNotifications(ruleIds: List<Long>, expected: ProfileSessionToken) {
        if (ruleIds.isEmpty()) return
        try {
            StorageOperationGate.run {
                if (!appProvider.isCurrentSession(expected)) return@run
                val removedSuggestions = repo.deleteRules(ruleIds)
                RenkinNotifications().cancelIconAvailable(
                    context = getApplication(),
                    suggestionIds = removedSuggestions,
                    cancelSummary = repo.suggestionCount() == 0
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.error("WatchViewModel", "Could not delete watch rules", error)
        }
    }

    /** Runs a manual check; [onResult] receives the number of new suggestions found. */
    fun runCheck(onResult: (Int) -> Unit) {
        if (isChecking) return
        viewModelScope.launch {
            isChecking = true
            // finally keeps the spinner from sticking when the checker throws.
            val count = try {
                val app = getApplication<Application>()
                val result = WatchChecker(app).runCheckResult(refreshCompletedSuggestions = true)
                StorageOperationGate.run {
                    if (!result.isCurrent()) return@run 0
                    app.dataStore.setLongValue(LastWatchCheckAtKey, System.currentTimeMillis())
                    val prefs = app.dataStore.getPreferencesAfterPendingWrites()
                    WatchWorker.schedulePeriodic(
                        app, normalizeWatchCheckInterval(prefs.getIntValue(WatchCheckIntervalKey, WATCH_CHECK_INTERVAL_DEFAULT)),
                        ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE
                    )
                    result.suggestions.size
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.error("WatchViewModel", "Manual watch check failed", error)
                return@launch
            } finally {
                isChecking = false
            }
            onResult(count)
        }
    }

    /** Debug only: baseline, stale all states, then re-check through the worker so the
     * full notify + deep-link path runs. */
    fun simulate() {
        viewModelScope.launch {
            try {
                val ctx = getApplication<Application>()
                val result = WatchChecker(ctx).runCheckResult()
                StorageOperationGate.run {
                    if (!result.isCurrent()) return@run
                    repo.debugStaleAllStates()
                    WatchWorker.runNow(ctx)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.error("WatchViewModel", "Watch simulation failed", error)
            }
        }
    }

    /** Loads a transactionally consistent target for the apply modal. */
    internal suspend fun loadSuggestion(id: Long): dev.renkinProject.renkin.data.watch.WatchApplySnapshot? =
        repo.loadApplySnapshot(id)
}
