package dev.renkinProject.renkin.feature.watch

import android.app.Application
import androidx.annotation.StringRes
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
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.expiredSessionMessage
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
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
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

    // Strings are resolved here because "found N" carries a count the Int toast channels cannot.
    private val mutableToastEvents = Channel<String>(Channel.BUFFERED)
    val toastEvents = mutableToastEvents.receiveAsFlow()

    private fun toast(@StringRes message: Int, vararg args: Any) {
        mutableToastEvents.trySend(getApplication<Application>().getString(message, *args))
    }

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
                toast(R.string.watchIntervalNotSaved)
            }
        }
    }

    private val requestIds = AtomicLong()

    /** A fresh identity for one editor opening or quick action; its save outcome is matched by it. */
    fun newRequestId(): Long = requestIds.incrementAndGet()

    private val ruleSaveOwner = WatchRuleSaveOwner(viewModelScope, ::commitRule) { state, request, error ->
        when (state.status) {
            WatchRuleSaveStatus.NOT_SAVED -> {
                error?.let { Log.error("WatchViewModel", "Could not save the watch rule", it) }
                toast(R.string.watchRuleNotSaved)
            }
            WatchRuleSaveStatus.SESSION_CHANGED -> toast(request.session.expiredSessionMessage(appProvider.sessionToken))
            WatchRuleSaveStatus.SAVING, WatchRuleSaveStatus.SAVED -> Unit
        }
    }

    /** The latest rule save, retained until [acknowledgeRuleSave] by the opener whose id it carries. */
    internal val ruleSaveState: StateFlow<WatchRuleSaveState?> = ruleSaveOwner.state

    /** Atomically creates or updates a rule together with its current icon baseline. */
    fun saveRule(
        requestId: Long,
        existing: RuleWithDetails?,
        apps: List<AppComponent>,
        watchAll: Boolean,
        packs: List<String>
    ): Boolean = ruleSaveOwner.submit(
        WatchRuleSaveRequest(
            id = requestId,
            session = appProvider.sessionToken,
            profileId = appProvider.activeProfileId,
            existingRuleId = existing?.rule?.id,
            apps = apps,
            watchAllPacks = watchAll,
            packs = packs
        )
    )

    fun acknowledgeRuleSave(requestId: Long) {
        ruleSaveOwner.acknowledge(requestId)
    }

    private suspend fun commitRule(request: WatchRuleSaveRequest): WatchRuleSaveStatus {
        if (!appProvider.isCurrentSession(request.session)) return WatchRuleSaveStatus.SESSION_CHANGED
        var sessionChanged = false
        val savedId = WatchChecker(getApplication()).saveRule(
            existingRuleId = request.existingRuleId,
            apps = request.apps,
            watchAllPacks = request.watchAllPacks,
            packPackages = request.packs,
            profileId = request.profileId,
            commit = { save ->
                if (appProvider.isCurrentSession(request.session)) save()
                else 0L.also { sessionChanged = true }
            }
        )
        return when {
            savedId > 0L -> WatchRuleSaveStatus.SAVED
            sessionChanged -> WatchRuleSaveStatus.SESSION_CHANGED
            else -> WatchRuleSaveStatus.NOT_SAVED
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
            toast(R.string.watchRuleNotDeleted)
        }
    }

    private val checkOwner = WatchCheckOwner(viewModelScope, ::checkNow) { found, error ->
        when {
            error != null -> {
                Log.error("WatchViewModel", "Manual watch check failed", error)
                toast(R.string.watchCheckFailed)
            }
            found == 0 -> toast(R.string.watchRefreshNone)
            found != null -> toast(R.string.watchRefreshFound, found)
            else -> Unit
        }
    }

    /** True while a manual check is running; drives the pull-to-refresh spinner. */
    val isChecking: StateFlow<Boolean> = checkOwner.running

    /** Runs a manual check; its outcome arrives as a toast. */
    fun runCheck() {
        checkOwner.start()
    }

    // A restore replacing the storage mid-check makes the result stale: report nothing then.
    private suspend fun checkNow(): Int? {
        val app = getApplication<Application>()
        val result = WatchChecker(app).runCheckResult(refreshCompletedSuggestions = true)
        return StorageOperationGate.run {
            if (!result.isCurrent()) return@run null
            app.dataStore.setLongValue(LastWatchCheckAtKey, System.currentTimeMillis())
            val prefs = app.dataStore.getPreferencesAfterPendingWrites()
            WatchWorker.schedulePeriodic(
                app, normalizeWatchCheckInterval(prefs.getIntValue(WatchCheckIntervalKey, WATCH_CHECK_INTERVAL_DEFAULT)),
                ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE
            )
            result.suggestions.size
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
                toast(R.string.watchCheckFailed)
            }
        }
    }

    /** Loads a transactionally consistent target for the apply modal. */
    internal suspend fun loadSuggestion(id: Long): dev.renkinProject.renkin.data.watch.WatchApplySnapshot? =
        repo.loadApplySnapshot(id)
}
