package dev.renkinProject.renkin.apk

import dev.renkinProject.renkin.packages.PackageInfoStruct
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf

/** Published under the profile gate so icon state, dirty keys and operation status stay coherent. */
data class ApplicationListState(
    // Compare the revision first: StateFlow equality must not scan every app on each edit.
    val revision: Long = 0,
    val applications: PersistentList<PackageInfoStruct> = persistentListOf(),
    val lockedIconKeys: Set<String> = emptySet(),
    val applicationsLoaded: Boolean = false,
    val sessionToken: ProfileSessionToken? = null,
    val undoStep: IconUndoStep? = null,
    /** Live edits for future dirty-state UI paired with these exact rows; excludes built/saved baselines. */
    val unsavedIconKeys: Set<String> = emptySet(),
    /** Session-owned render/build reservation for UI availability; action handlers still revalidate live state. */
    val longOperationRunning: Boolean = false
)
