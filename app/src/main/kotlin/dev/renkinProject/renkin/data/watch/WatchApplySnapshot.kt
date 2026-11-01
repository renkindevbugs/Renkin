package dev.renkinProject.renkin.data.watch

internal data class WatchApplySnapshot(
    val rule: RuleWithDetails,
    val suggestion: IconSuggestion,
    val candidates: List<IconSuggestionCandidate>,
    val storageEpoch: Long,
    val ruleCandidates: List<IconSuggestionCandidate> = candidates
)

internal class WatchApplyTargetChangedException : IllegalStateException("The captured watch suggestion changed")
