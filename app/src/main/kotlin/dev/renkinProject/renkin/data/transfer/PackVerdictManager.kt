package dev.renkinProject.renkin.data.transfer

import android.content.Context
import dev.renkinProject.renkin.apk.IconPackBuilder
import dev.renkinProject.renkin.data.IconPack
import dev.renkinProject.renkin.data.PackVerdict
import dev.renkinProject.renkin.data.RenkinPackRepository
import dev.renkinProject.renkin.data.VERDICT_FREE
import dev.renkinProject.renkin.data.VERDICT_PAID
import dev.renkinProject.renkin.data.VERDICT_UNKNOWN
import dev.renkinProject.renkin.data.VERDICT_UNLISTED
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decides which source packs' icons are usable on this device (see [PackVerdict]) and keeps
 * the verdict cache fresh. The rules, in order:
 *  - a pack currently installed, or ever seen installed here, is always usable;
 *  - a pack verified free or not-on-the-store is usable without installing;
 *  - a pack verified paid — or not verified yet — keeps its imported icons locked
 *    (loaded rows are held back and excluded from builds) until the pack is installed.
 *
 * Verdicts come from [PlayStoreLookup] (injectable for tests) and are re-tried by
 * [verifyPendingVerdicts] whenever it's called with connectivity — import, app start and
 * the periodic watch worker all call it, so an offline import self-heals later.
 */
class PackVerdictManager(
    private val context: Context,
    private val repo: RenkinPackRepository,
    private val lookup: suspend (String) -> StoreVerdict = PlayStoreLookup::lookup
) {
    /** Production entry point: uses the shared singleton database. */
    constructor(context: Context) : this(context, RenkinPackRepository(context))

    private fun isInstalled(packageName: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(packageName, 0)
    }.isSuccess

    /**
     * Records every currently installed icon pack as owned on this device (with its label,
     * shown later if the pack goes missing). Called after the pack list loads.
     */
    suspend fun recordInstalledPacks(packs: List<IconPack>) = withContext(Dispatchers.Default) {
        if (packs.isEmpty()) return@withContext
        val existing = repo.verdicts(packs.map { it.packageName })
        repo.upsertVerdicts(packs.map { pack ->
            val row = existing[pack.packageName] ?: PackVerdict(pack.packageName)
            row.copy(label = pack.applicationName, seenInstalled = true)
        })
    }

    /** Of [packs], those whose stored icons must stay locked on this device right now. */
    suspend fun lockedPacksAmong(packs: Set<String>): Set<String> = withContext(Dispatchers.Default) {
        val candidates = packs.filter { it.isNotEmpty() && !IconPackBuilder.isOwnPack(it) }
        if (candidates.isEmpty()) return@withContext emptySet()
        val verdicts = repo.verdicts(candidates)
        candidates.filter { pack ->
            val verdict = verdicts[pack]
            when {
                verdict?.seenInstalled == true -> false
                isInstalled(pack) -> false
                verdict?.verdict == VERDICT_FREE || verdict?.verdict == VERDICT_UNLISTED -> false
                else -> true // paid, or not verified yet
            }
        }.toSet()
    }

    /**
     * Looks up every referenced pack that still lacks a decisive verdict. Quiet best effort:
     * offline or blocked lookups leave the verdict UNKNOWN and a later call retries (after
     * [RETRY_INTERVAL_MS], so a burst of triggers doesn't hammer the store).
     */
    suspend fun verifyPendingVerdicts() = withContext(Dispatchers.Default) {
        ensureVerdicts(repo.distinctSourcePacks().toSet())
    }

    /**
     * Makes sure each of [packs] has a verdict, looking up the undecided ones now. Returns
     * the packs that must NOT have their image data embedded in a shared file: verified paid,
     * or unverifiable (fail-closed — an offline export must not leak paid icons).
     */
    suspend fun ensureVerdicts(packs: Set<String>): Set<String> = withContext(Dispatchers.Default) {
        val candidates = packs.filter { it.isNotEmpty() && !IconPackBuilder.isOwnPack(it) }
        if (candidates.isEmpty()) return@withContext emptySet()
        val verdicts = repo.verdicts(candidates).toMutableMap()
        val now = System.currentTimeMillis()

        val updates = mutableListOf<PackVerdict>()
        for (pack in candidates) {
            val row = verdicts[pack] ?: PackVerdict(pack)
            // Installed packs are owned; still resolve their price for export stripping.
            if (isInstalled(pack) && !row.seenInstalled) {
                verdicts[pack] = row.copy(seenInstalled = true).also { updates.add(it) }
            }
            val undecided = (verdicts[pack] ?: row).verdict == VERDICT_UNKNOWN
            val retryDue = now - ((verdicts[pack] ?: row).checkedAt) >= RETRY_INTERVAL_MS
            if (undecided && retryDue) {
                val result = when (lookup(pack)) {
                    StoreVerdict.FREE -> VERDICT_FREE
                    StoreVerdict.PAID -> VERDICT_PAID
                    StoreVerdict.UNLISTED -> VERDICT_UNLISTED
                    StoreVerdict.UNKNOWN -> VERDICT_UNKNOWN
                }
                val updated = (verdicts[pack] ?: row).copy(verdict = result, checkedAt = now)
                verdicts[pack] = updated
                updates.add(updated)
            }
        }
        repo.upsertVerdicts(updates.distinctBy { it.packageName }.map { verdicts[it.packageName] ?: it })

        candidates.filter { pack ->
            val verdict = verdicts[pack]?.verdict ?: VERDICT_UNKNOWN
            verdict == VERDICT_PAID || verdict == VERDICT_UNKNOWN
        }.toSet()
    }

    companion object {
        // Between failed lookup retries; keeps startup + worker triggers from spamming.
        private const val RETRY_INTERVAL_MS = 60 * 60 * 1000L
    }
}
