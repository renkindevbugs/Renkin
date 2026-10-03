package dev.renkinProject.renkin.apk

data class ProfileSessionToken(
    val profileId: Long,
    val revision: Long,
    val providerInstanceId: String
)

enum class ProfileSessionFailureReason {
    LOADING,
    EXPIRED,
    PROCESS_LOST
}

class ProfileSessionChangedException(
    val reason: ProfileSessionFailureReason
) : IllegalStateException("The profile session is unavailable: $reason")

class ProfileOperationBusyException : IllegalStateException("A profile operation is already running")

class ProfileReloadFailedException(
    val restoredSession: ProfileSessionToken,
    cause: Exception
) : IllegalStateException("Could not reload the profile session", cause)
