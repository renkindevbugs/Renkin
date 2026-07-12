package dev.renkinProject.renkin.data.transfer

import android.content.Context
import dev.renkinProject.renkin.apk.IconPackBuilder
import dev.renkinProject.renkin.data.IconPack
import dev.renkinProject.renkin.data.PackVerdict
import dev.renkinProject.renkin.data.RenkinPackRepository
import dev.renkinProject.renkin.data.VERDICT_FREE
import dev.renkinProject.renkin.data.VERDICT_LISTED
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
 * Verdicts come from [StoreLookup] (Play + F-Droid; injectable for tests) and are re-tried by
 * [verifyPendingVerdicts] whenever it's called with connectivity — import, app start and
 * the periodic watch worker all call it, so an offline import self-heals later.
 *
 * The policy deliberately requires INSTALLING any pack that is still available anywhere: a
 * pack found on a store (free or paid, Play or F-Droid) keeps its shared icons LOCKED until
 * installed, so we never redistribute a developer's pack in place of an install. Only a pack
 * on no known store unlocks (its icons would otherwise be lost). Icon Pack Studio exports —
 * per-user packages that were never on any store — are locked by package pattern regardless.
 */
class PackVerdictManager(
    private val context: Context,
    private val repo: RenkinPackRepository,
    private val lookup: suspend (String) -> StoreLookupResult = StoreLookup::lookup
) {
    /** Icon Pack Studio stamps every export with this same package — never store-installable. */
    private fun isIconPackStudioExport(pack: String): Boolean =
        pack.contains("iconpackstudio.exported", ignoreCase = true)
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
                // Installed here (now or ever) — owned, always usable.
                verdict?.seenInstalled == true -> false
                isInstalled(pack) -> false
                // Icon Pack Studio exports are never on a store to install from, and are
                // personal-use — locked unless the recipient actually installed one.
                isIconPackStudioExport(pack) -> true
                // On no known store: can't be installed anywhere, so keep its icons usable.
                verdict?.verdict == VERDICT_UNLISTED -> false
                // Available somewhere (free/paid/F-Droid) or not verified yet -> install to use.
                else -> true
            }
        }.toSet()
    }

    /**
     * Looks up every referenced pack that still lacks a decisive verdict. Quiet best effort:
     * offline or blocked lookups leave the verdict UNKNOWN and a later call retries (after
     * [RETRY_INTERVAL_MS], so a burst of triggers doesn't hammer the store). Returns true
     * when any verdict became decisive — the caller can reload to unlock verified-free icons.
     */
    suspend fun verifyPendingVerdicts(): Boolean = withContext(Dispatchers.Default) {
        val packs = repo.distinctSourcePacks().toSet()
            .filter { it.isNotEmpty() && !IconPackBuilder.isOwnPack(it) }
        if (packs.isEmpty()) return@withContext false
        val before = repo.verdicts(packs)
        val undecidedBefore = packs.filter {
            (before[it]?.verdict ?: VERDICT_UNKNOWN) == VERDICT_UNKNOWN
        }.toSet()
        ensureVerdicts(packs.toSet())
        val after = repo.verdicts(undecidedBefore.toList())
        undecidedBefore.any { (after[it]?.verdict ?: VERDICT_UNKNOWN) != VERDICT_UNKNOWN }
    }

    /**
     * Makes sure each of [packs] has a verdict, looking up the undecided ones now. Returns
     * (advisory) the packs still installable somewhere or not yet verified — i.e. everything
     * except those found on no known store. Locking is enforced by [lockedPacksAmong]; this
     * set is a hint for callers that want to warn about install-required packs.
     */
    suspend fun ensureVerdicts(packs: Set<String>): Set<String> = withContext(Dispatchers.Default) {
        val candidates = packs.filter { it.isNotEmpty() && !IconPackBuilder.isOwnPack(it) }
        if (candidates.isEmpty()) return@withContext emptySet()
        val verdicts = repo.verdicts(candidates).toMutableMap()
        val now = System.currentTimeMillis()

        val updates = mutableListOf<PackVerdict>()
        for (pack in candidates) {
            val row = verdicts[pack] ?: PackVerdict(pack)
            // Installed packs are owned; record that so they never lock.
            if (isInstalled(pack) && !row.seenInstalled) {
                verdicts[pack] = row.copy(seenInstalled = true).also { updates.add(it) }
            }
            // Icon Pack Studio exports are never on a store — don't waste a lookup; record a
            // decisive verdict so it isn't retried hourly (the package-pattern lock covers it).
            if (isIconPackStudioExport(pack) && (verdicts[pack] ?: row).verdict == VERDICT_UNKNOWN) {
                verdicts[pack] = (verdicts[pack] ?: row).copy(verdict = VERDICT_UNLISTED, checkedAt = now)
                    .also { updates.add(it) }
                continue
            }
            val undecided = (verdicts[pack] ?: row).verdict == VERDICT_UNKNOWN
            val retryDue = now - ((verdicts[pack] ?: row).checkedAt) >= RETRY_INTERVAL_MS
            if (undecided && retryDue) {
                val result = lookup(pack)
                val verdictValue = when (result.verdict) {
                    StoreVerdict.FREE -> VERDICT_FREE
                    StoreVerdict.PAID -> VERDICT_PAID
                    StoreVerdict.LISTED -> VERDICT_LISTED
                    StoreVerdict.UNLISTED -> VERDICT_UNLISTED
                    StoreVerdict.UNKNOWN -> VERDICT_UNKNOWN
                }
                var updated = (verdicts[pack] ?: row).copy(verdict = verdictValue, checkedAt = now)
                // The store page also names the pack — fills the gap for packs this device
                // never saw installed (otherwise the dialog shows a bare package name).
                if (updated.label.isEmpty() && !result.label.isNullOrEmpty()) {
                    updated = updated.copy(label = result.label)
                }
                verdicts[pack] = updated
                updates.add(updated)
            }
        }
        repo.upsertVerdicts(updates.distinctBy { it.packageName }.map { verdicts[it.packageName] ?: it })

        candidates.filter { pack ->
            (verdicts[pack]?.verdict ?: VERDICT_UNKNOWN) != VERDICT_UNLISTED
        }.toSet()
    }

    companion object {
        // Between failed lookup retries; keeps startup + worker triggers from spamming.
        private const val RETRY_INTERVAL_MS = 60 * 60 * 1000L
    }
}
