package dev.alembiconsProject.alembicons

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingPeriodicWorkPolicy
import dev.alembiconsProject.alembicons.data.WATCH_CHECK_INTERVAL_DEFAULT
import dev.alembiconsProject.alembicons.data.LastWatchCheckAtKey
import dev.alembiconsProject.alembicons.data.WatchCheckIntervalKey
import dev.alembiconsProject.alembicons.data.getPreferenceFlow
import dev.alembiconsProject.alembicons.data.setIntValue
import dev.alembiconsProject.alembicons.data.setLongValue
import dev.alembiconsProject.alembicons.dataStore
import dev.alembiconsProject.alembicons.data.watch.AppComponent
import dev.alembiconsProject.alembicons.data.watch.IconSuggestion
import dev.alembiconsProject.alembicons.data.watch.IconSuggestionCandidate
import dev.alembiconsProject.alembicons.data.watch.RuleWithDetails
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.alembiconsProject.alembicons.data.watch.WatchRepository
import dev.alembiconsProject.alembicons.service.WatchChecker
import dev.alembiconsProject.alembicons.service.WatchWorker
import kotlinx.coroutines.flow.SharingStarted
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
    private val repo: WatchRepository
) : AndroidViewModel(application) {

    /** All watch rules (active + completed); the UI splits them by `completed`. */
    val rules: StateFlow<List<RuleWithDetails>> =
        repo.rules.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Number of completed rules — drives the bell badge on the home screen. */
    val completedCount: StateFlow<Int> =
        repo.completedCount.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** Epoch-millis of the next scheduled periodic check; null when none is pending. */
    val nextCheckAt: StateFlow<Long?> =
        WatchWorker.nextScheduledCheckFlow(application)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Configured periodic check interval in minutes (24h default; debug can lower it). */
    val checkIntervalMinutes: StateFlow<Int> =
        application.dataStore.getPreferenceFlow(WatchCheckIntervalKey)
            .map { it ?: WATCH_CHECK_INTERVAL_DEFAULT }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), WATCH_CHECK_INTERVAL_DEFAULT)

    /** Debug: change how often the periodic check runs and reschedule it immediately. */
    fun setCheckIntervalMinutes(minutes: Int) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            app.dataStore.setIntValue(WatchCheckIntervalKey, minutes)
            WatchWorker.schedulePeriodic(app, minutes, ExistingPeriodicWorkPolicy.UPDATE)
        }
    }

    /** True while a manual check is running; drives the pull-to-refresh spinner. */
    var isChecking by mutableStateOf(false)
        private set

    /** Creates or updates a rule, then snapshots its current icons as the baseline. */
    fun saveRule(
        existing: RuleWithDetails?,
        apps: List<AppComponent>,
        watchAll: Boolean,
        packs: List<String>
    ) {
        viewModelScope.launch {
            val ruleId = if (existing == null) {
                repo.createRule(apps, watchAll, packs)
            } else {
                repo.updateRule(existing.rule.id, apps, watchAll, packs)
                existing.rule.id
            }
            // Snapshot current icons so a later pack update is the trigger, not the
            // icons that already existed when the rule was made.
            WatchChecker(getApplication()).baselineRule(ruleId)
        }
    }

    fun deleteRule(ruleId: Long) {
        viewModelScope.launch { repo.deleteRule(ruleId) }
    }

    fun deleteCompleted() {
        viewModelScope.launch {
            repo.deleteRules(rules.value.filter { it.rule.completed }.map { it.rule.id })
        }
    }

    /** Runs a manual check; [onResult] receives the number of new suggestions found. */
    fun runCheck(onResult: (Int) -> Unit) {
        if (isChecking) return
        viewModelScope.launch {
            isChecking = true
            val fired = WatchChecker(getApplication()).runCheck()
            isChecking = false
            // A manual check counts as a check: record it and re-enqueue the periodic
            // safety-net so its next run (and the "Next check" label) move out by a full
            // interval from now.
            val app = getApplication<Application>()
            app.dataStore.setLongValue(LastWatchCheckAtKey, System.currentTimeMillis())
            WatchWorker.schedulePeriodic(
                app, checkIntervalMinutes.value, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE
            )
            onResult(fired.size)
        }
    }

    /** Debug only: baseline, stale all states, then re-check through the worker so the
     * full notify + deep-link path runs. */
    fun simulate() {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            WatchChecker(ctx).runCheck()
            repo.debugStaleAllStates()
            WatchWorker.runNow(ctx)
        }
    }

    /** Loads a suggestion and its candidate icons for the apply modal. */
    suspend fun loadSuggestion(id: Long): Pair<IconSuggestion?, List<IconSuggestionCandidate>> =
        repo.getSuggestion(id) to repo.getCandidates(id)
}
