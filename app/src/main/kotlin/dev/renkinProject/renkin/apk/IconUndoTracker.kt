package dev.renkinProject.renkin.apk

import dev.renkinProject.renkin.packages.PackageInfoStruct
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The single "take that back" step the app offers. Holding the previous [PackageInfoStruct] rows
 * costs nothing extra: they are the objects the list already had, and the icons they carry are
 * the same bitmaps that were in memory a moment ago.
 */
class IconUndoStep(
    val profileId: Long,
    val rows: List<PackageInfoStruct>,
    /** Whether the change had reached the database, so undoing it has to be saved as well. */
    val persisted: Boolean,
    val operationId: Long = 0L,
    val unsavedBefore: Set<String> = emptySet()
)

/**
 * Bookkeeping for that one step, kept apart from [ApplicationProvider] so the rules — one step
 * at a time, never across profiles, never for rows that are gone — can be tested without a
 * database, a package manager or an icon generator.
 */
class IconUndoTracker {
    private var nextOperationId = 0L

    private val mutableStep = MutableStateFlow<IconUndoStep?>(null)
    /** Operation-time snapshot; provider UI observes the completed application aggregate. */
    val step: IconUndoStep? get() = mutableStep.value

    /** Number of rows the current offer would restore; 0 when there is nothing to offer. */
    val size: Int get() = step?.rows?.size ?: 0

    /** Records rows about to be overwritten. An empty list withdraws the offer entirely. */
    fun capture(
        rows: List<PackageInfoStruct>, profileId: Long, persisted: Boolean,
        unsavedBefore: Set<String> = emptySet()
    ) {
        mutableStep.value = if (rows.isEmpty()) null else IconUndoStep(
            profileId, rows.toList(), persisted, ++nextOperationId,
            rows.map { it.key }.toSet().intersect(unsavedBefore)
        )
    }

    fun discard(key: String) {
        val pending = step ?: return
        if (pending.rows.none { it.key == key }) return
        val remaining = pending.rows.filterNot { it.key == key }
        mutableStep.value = if (remaining.isEmpty()) null else IconUndoStep(
            pending.profileId, remaining, pending.persisted, pending.operationId,
            pending.unsavedBefore - key
        )
    }

    fun clear() { mutableStep.value = null }

    /**
     * The rows to put back, paired with where they sit in [current]. Empty when the step no
     * longer applies: another profile is active, or every app it described is gone.
     */
    fun restorationFor(
        current: List<PackageInfoStruct>,
        activeProfileId: Long
    ): List<Pair<Int, PackageInfoStruct>> {
        val pending = step ?: return emptyList()
        if (pending.profileId != activeProfileId) return emptyList()
        val indexByKey = current.withIndex().associate { (index, app) -> app.key to index }
        return pending.rows.mapNotNull { row -> indexByKey[row.key]?.let { it to row } }
    }
}
