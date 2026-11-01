package dev.renkinProject.renkin.apk

import dev.renkinProject.renkin.packages.PackageInfoStruct
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

/** How one app differs from what the last build put in the pack. */
enum class PackChangeKind { ADDED, CHANGED, REMOVED }

/** Why it differs — the row's subtitle, so the list explains itself without opening anything. */
enum class PackChangeReason { REFRESH, HAND_EDIT, ICON_REMOVED }

data class PackChange(
    val application: PackageInfoStruct,
    val kind: PackChangeKind,
    val reason: PackChangeReason
)

/** The diff may reach its collector after a newer application snapshot. */
data class PackChangesState(
    val sessionToken: ProfileSessionToken? = null,
    val applicationRevision: Long = 0,
    val changes: List<PackChange> = emptyList()
) {
    fun changesFor(state: ApplicationListState): List<PackChange> =
        if (sessionToken != null && sessionToken == state.sessionToken && applicationRevision == state.revision) {
            changes
        } else emptyList()
}

internal data class PackChangeBaselines(
    val owner: ProfileSessionToken?,
    val built: Map<String, String>,
    val saved: Map<String, String>,
    val updated: Set<String>
)

internal fun observePackChanges(
    applications: StateFlow<ApplicationListState>,
    baselines: Flow<PackChangeBaselines>,
    scope: CoroutineScope
): StateFlow<PackChangesState> = combine(applications, baselines) { state, inputs ->
    if (inputs.owner != null && inputs.owner == state.sessionToken) {
        PackChangesState(
            state.sessionToken, state.revision,
            packChanges(state.applications, inputs.built, inputs.saved, inputs.updated)
        )
    } else PackChangesState()
}.flowOn(Dispatchers.Default)
    .stateIn(scope, SharingStarted.WhileSubscribed(5_000), PackChangesState())

/**
 * What a build would change, compared with the icons the last build shipped. [builtKeys] is that
 * pack's contents; [updatedKeys] the apps hand-edited since. Apps uninstalled since the build are
 * left out on purpose: nothing can be shown for them, and the same rule already governs the
 * "unsaved changes" badge ([unsavedApplicationKeys]).
 *
 * Ordered added → changed → removed, alphabetically inside each group, which is the order the
 * list renders them in.
 */
fun packChanges(
    applications: List<PackageInfoStruct>,
    builtHashes: Map<String, String>,
    savedHashes: Map<String, String>,
    updatedKeys: Set<String>
): List<PackChange> = applications.mapNotNull { app ->
    val builtHash = builtHashes[app.key]
    val edited = app.key in updatedKeys
    // A saved icon whose fingerprint moved on, or one changed in this session and not saved yet:
    // both are edits the installed pack does not have.
    val savedDiffers = savedHashes[app.key]?.let { it != builtHash } ?: false
    when {
        app.createdIcon == null ->
            // Only interesting when the build would actually drop something.
            if (builtHash != null) {
                PackChange(app, PackChangeKind.REMOVED, PackChangeReason.ICON_REMOVED)
            } else null
        builtHash == null -> PackChange(app, PackChangeKind.ADDED, reasonFor(app.isRefreshMade, edited))
        app.isRefreshMade || edited || savedDiffers ->
            PackChange(app, PackChangeKind.CHANGED, reasonFor(app.isRefreshMade, edited))
        else -> null
    }
}.sortedWith(
    compareBy<PackChange> { it.kind.ordinal }.thenBy { it.application.appName.lowercase() }
)

// A hand edit outranks the refresh marker: an icon the user picked after a refresh is theirs.
private fun reasonFor(refreshMade: Boolean, edited: Boolean): PackChangeReason = when {
    edited -> PackChangeReason.HAND_EDIT
    refreshMade -> PackChangeReason.REFRESH
    else -> PackChangeReason.HAND_EDIT
}
