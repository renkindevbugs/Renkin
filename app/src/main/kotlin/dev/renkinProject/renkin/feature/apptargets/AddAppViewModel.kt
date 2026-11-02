package dev.renkinProject.renkin.feature.apptargets

import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.renkinProject.renkin.apk.ApplicationProvider
import dev.renkinProject.renkin.apk.ApplicationProvider.AppTargetAddResult
import dev.renkinProject.renkin.apk.ApplicationProvider.AppTargetRemoveResult
import dev.renkinProject.renkin.apk.PackAppCandidate
import dev.renkinProject.renkin.apk.ProfileSessionChangedException
import dev.renkinProject.renkin.apk.ProfileSessionToken
import dev.renkinProject.renkin.data.AppTarget
import dev.renkinProject.renkin.drawable.toSafeBitmapOrNull
import dev.renkinProject.renkin.packages.ApplicationIconSource
import dev.renkinProject.renkin.util.Log
import dev.renkinProject.renkin.util.runCatchingCancellable
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AddAppOutcome { ADDED, ALREADY_LISTED, LIMIT_REACHED, SESSION_CHANGED, FAILED }
enum class RemoveAppOutcome { REMOVED, HAS_ICON, SESSION_CHANGED, FAILED }

/**
 * Owns the Add app sheet: candidate discovery (disabled apps, components the installed packs
 * theme) and the add/remove calls. Candidates are read when the sheet opens and dropped when it
 * closes, so a pack installed in between is never missing from the next look.
 */
@HiltViewModel
class AddAppViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val appProvider: ApplicationProvider
) : ViewModel() {
    var disabledApps by mutableStateOf<List<AppTarget>?>(null)
        private set
    var packCandidates by mutableStateOf<List<PackAppCandidate>?>(null)
        private set
    var busy by mutableStateOf(false)
        private set

    private var loadJob: Job? = null
    private val icons = LruCache<String, ImageBitmap>(MAX_CACHED_ICONS)

    fun loadCandidates() {
        if (loadJob != null) return
        loadJob = viewModelScope.launch {
            val disabled = async { loadOrEmpty("disabled apps") { appProvider.disabledAppCandidates() } }
            val packs = async { loadOrEmpty("pack components") { appProvider.packAppCandidates() } }
            disabledApps = disabled.await()
            packCandidates = packs.await()
        }
    }

    fun releaseCandidates() {
        loadJob?.cancel()
        loadJob = null
        disabledApps = null
        packCandidates = null
        icons.evictAll()
    }

    private suspend fun <T> loadOrEmpty(what: String, load: suspend () -> List<T>): List<T> = try {
        load()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Log.error("AddAppViewModel", "Could not read $what", error)
        emptyList()
    }

    suspend fun disabledAppIcon(target: AppTarget, sizePx: Int): ImageBitmap? = cachedIcon("app:${target.key}:$sizePx") {
        val source = ApplicationIconSource.PackageActivity(
            ComponentName(target.packageName, target.activityName),
            Configuration(context.resources.configuration)
        )
        appProvider.installedIcons.preview(source, sizePx)
    }

    suspend fun packCandidateIcon(candidate: PackAppCandidate, sizePx: Int): ImageBitmap? =
        cachedIcon("pack:${candidate.iconPack}:${candidate.drawableName}:$sizePx") {
            withContext(Dispatchers.Default) {
                appProvider.packCandidateDrawable(candidate)?.drawable?.toSafeBitmapOrNull(sizePx, sizePx)
            }
        }

    private suspend fun cachedIcon(key: String, load: suspend () -> android.graphics.Bitmap?): ImageBitmap? {
        icons.get(key)?.let { return it }
        val bitmap = runCatchingCancellable { load() }.getOrNull()
        return bitmap?.asImageBitmap()?.also { icons.put(key, it) }
    }

    fun add(expected: ProfileSessionToken, target: AppTarget, onResult: (AddAppOutcome) -> Unit) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            val outcome = try {
                when (appProvider.addAppTarget(expected, target)) {
                    AppTargetAddResult.ADDED -> AddAppOutcome.ADDED
                    AppTargetAddResult.ALREADY_LISTED -> AddAppOutcome.ALREADY_LISTED
                    AppTargetAddResult.LIMIT_REACHED -> AddAppOutcome.LIMIT_REACHED
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: ProfileSessionChangedException) {
                AddAppOutcome.SESSION_CHANGED
            } catch (error: Exception) {
                Log.error("AddAppViewModel", "Adding an app failed", error)
                AddAppOutcome.FAILED
            } finally {
                busy = false
            }
            onResult(outcome)
        }
    }

    fun remove(expected: ProfileSessionToken, key: String, onResult: (RemoveAppOutcome) -> Unit) {
        viewModelScope.launch {
            val outcome = try {
                when (appProvider.removeAppTarget(expected, key)) {
                    AppTargetRemoveResult.REMOVED, AppTargetRemoveResult.NOT_ADDED -> RemoveAppOutcome.REMOVED
                    AppTargetRemoveResult.HAS_ICON -> RemoveAppOutcome.HAS_ICON
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: ProfileSessionChangedException) {
                RemoveAppOutcome.SESSION_CHANGED
            } catch (error: Exception) {
                Log.error("AddAppViewModel", "Removing an app failed", error)
                RemoveAppOutcome.FAILED
            }
            onResult(outcome)
        }
    }

    private companion object {
        const val MAX_CACHED_ICONS = 160
    }
}
