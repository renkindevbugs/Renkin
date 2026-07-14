package dev.renkinProject.renkin

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
import dev.renkinProject.renkin.data.getPreferenceFlow
import dev.renkinProject.renkin.data.setIntValue
import dev.renkinProject.renkin.data.setLongValue
import dev.renkinProject.renkin.dataStore
import dev.renkinProject.renkin.data.watch.AppComponent
import dev.renkinProject.renkin.data.watch.IconSuggestion
import dev.renkinProject.renkin.data.watch.IconSuggestionCandidate
import dev.renkinProject.renkin.data.watch.RuleWithDetails
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.renkinProject.renkin.apk.ApplicationProvider
import dev.renkinProject.renkin.data.watch.WatchRepository
import dev.renkinProject.renkin.service.WatchChecker
import dev.renkinProject.renkin.service.RenkinNotifications
import dev.renkinProject.renkin.service.WatchWorker
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
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

    // Rules and the badge follow the ACTIVE profile: snapshotFlow tracks the Compose state
    // in the provider and re-subscribes the Room flow whenever the profile switches.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun <T> perProfile(source: (Long) -> kotlinx.coroutines.flow.Flow<T>) =
        androidx.compose.runtime.snapshotFlow { appProvider.activeProfileId }
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
        viewModelScope.launch {
            val savedId = try {
                WatchChecker(getApplication()).saveRule(
                    existingRuleId = existing?.rule?.id,
                    apps = apps,
                    watchAllPacks = watchAll,
                    packPackages = packs,
                    profileId = profileId
                )
            } finally {
                isSavingRule = false
            }
            if (savedId > 0L) onSaved()
        }
    }

    fun deleteRule(ruleId: Long) {
        viewModelScope.launch { deleteRulesAndNotifications(listOf(ruleId)) }
    }

    fun deleteCompleted() {
        viewModelScope.launch {
            deleteRulesAndNotifications(rules.value.filter { it.rule.completed }.map { it.rule.id })
        }
    }

    private suspend fun deleteRulesAndNotifications(ruleIds: List<Long>) {
        if (ruleIds.isEmpty()) return
        val removedSuggestions = repo.deleteRules(ruleIds)
        RenkinNotifications().cancelIconAvailable(
            context = getApplication(),
            suggestionIds = removedSuggestions,
            cancelSummary = repo.suggestionCount() == 0
        )
    }

    /** Runs a manual check; [onResult] receives the number of new suggestions found. */
    fun runCheck(onResult: (Int) -> Unit) {
        if (isChecking) return
        viewModelScope.launch {
            isChecking = true
            // finally keeps the spinner from sticking when the checker throws.
            val fired = try {
                WatchChecker(getApplication()).runCheck()
            } finally {
                isChecking = false
            }
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
