package dev.renkinProject.renkin.apk

import dev.renkinProject.renkin.packages.PackageInfoStruct
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf

/** Published under the profile gate so applications and lock keys describe the same operation. */
data class ApplicationListState(
    // Compare the revision first: StateFlow equality must not scan every app on each edit.
    val revision: Long = 0,
    val applications: PersistentList<PackageInfoStruct> = persistentListOf(),
    val lockedIconKeys: Set<String> = emptySet(),
    val applicationsLoaded: Boolean = false,
    val sessionToken: ProfileSessionToken? = null
)
