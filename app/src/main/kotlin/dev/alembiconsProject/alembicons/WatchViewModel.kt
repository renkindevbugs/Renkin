package dev.alembiconsProject.alembicons

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.alembiconsProject.alembicons.data.watch.AppComponent
import dev.alembiconsProject.alembicons.data.watch.IconSuggestion
import dev.alembiconsProject.alembicons.data.watch.IconSuggestionCandidate
import dev.alembiconsProject.alembicons.data.watch.RuleWithDetails
import dev.alembiconsProject.alembicons.data.watch.WatchRepository
import dev.alembiconsProject.alembicons.service.WatchChecker
import dev.alembiconsProject.alembicons.service.WatchWorker
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Owns the icon-watch domain (rule CRUD, manual checks) so the watch UI is plain
 * composables that call functions and read state, instead of spinning up their own
 * coroutine scopes and reaching for the repository / checker / worker directly.
 */
class WatchViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = WatchRepository(application)

    /** All watch rules (active + completed); the UI splits them by `completed`. */
    val rules: StateFlow<List<RuleWithDetails>> =
        repo.rules.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
