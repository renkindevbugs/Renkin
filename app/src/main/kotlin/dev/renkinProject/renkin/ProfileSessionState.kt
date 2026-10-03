package dev.renkinProject.renkin

import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import dev.renkinProject.renkin.apk.ProfileSessionToken
import dev.renkinProject.renkin.apk.ProfileSessionChangedException
import dev.renkinProject.renkin.apk.ProfileSessionFailureReason

private const val SESSION_PROFILE = "profileSession.profile"
private const val SESSION_REVISION = "profileSession.revision"
private const val SESSION_PROVIDER = "profileSession.provider"

internal fun ProfileSessionToken.expiredSessionMessage(current: ProfileSessionToken): Int =
    if (providerInstanceId != current.providerInstanceId) R.string.editorSessionLost
    else R.string.profileSessionChanged

internal fun ProfileSessionChangedException.userMessage(): Int = when (reason) {
    ProfileSessionFailureReason.LOADING -> R.string.profileStillLoading
    ProfileSessionFailureReason.EXPIRED -> R.string.profileSessionChanged
    ProfileSessionFailureReason.PROCESS_LOST -> R.string.editorSessionLost
}

internal fun Intent.withProfileSession(session: ProfileSessionToken?): Intent = apply {
    session?.let {
        putExtra(SESSION_PROFILE, it.profileId)
        putExtra(SESSION_REVISION, it.revision)
        putExtra(SESSION_PROVIDER, it.providerInstanceId)
    }
}

internal fun Intent.profileSession(): ProfileSessionToken? {
    if (!hasExtra(SESSION_PROFILE) || !hasExtra(SESSION_REVISION)) return null
    val provider = getStringExtra(SESSION_PROVIDER) ?: return null
    return ProfileSessionToken(
        getLongExtra(SESSION_PROFILE, -1),
        getLongExtra(SESSION_REVISION, -1),
        provider
    )
}

internal fun SavedStateHandle.profileSession(): ProfileSessionToken? {
    val profile = get<Long>(SESSION_PROFILE) ?: return null
    val revision = get<Long>(SESSION_REVISION) ?: return null
    val provider = get<String>(SESSION_PROVIDER) ?: return null
    return ProfileSessionToken(profile, revision, provider)
}

internal fun SavedStateHandle.saveProfileSession(session: ProfileSessionToken) {
    this[SESSION_PROFILE] = session.profileId
    this[SESSION_REVISION] = session.revision
    this[SESSION_PROVIDER] = session.providerInstanceId
}
