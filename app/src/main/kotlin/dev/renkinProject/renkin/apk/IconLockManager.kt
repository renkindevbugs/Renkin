package dev.renkinProject.renkin.apk

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.renkinProject.renkin.data.DbApplication
import dev.renkinProject.renkin.data.RenkinPackRepository
import dev.renkinProject.renkin.data.VERDICT_UNKNOWN
import dev.renkinProject.renkin.data.transfer.PackVerdictManager
import dev.renkinProject.renkin.icon.creator.GenerationOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Owns the active profile's HELD-BACK icon rows and every policy question around them:
 *
 *  - **locked rows** — icons whose source pack is paid (or unverified) and not usable on
 *    this device. Invisible to the list and excluded from builds, but preserved across
 *    saves; they load normally once the pack is installed or verified free/unlisted.
 *  - **orphan rows** — icons of apps not installed here (imported sets). Also preserved;
 *    they come back through a normal load once the app appears.
 *  - **provenance** — the component→origin maps carried by Renkin-built packs, so an icon
 *    that arrived through an own pack stays attributed to its REAL source pack.
 *
 * [ApplicationProvider] orchestrates when rows are held or released (it owns the app list
 * and the store); this class is the single home of the held state and the verdict policy.
 */
class IconLockManager(
    private val context: Context,
    private val packRepo: RenkinPackRepository,
    private val verdictManager: PackVerdictManager = PackVerdictManager(context, packRepo)
) {
    private val lockedRows = mutableMapOf<String, DbApplication>()
    private val orphanRows = mutableMapOf<String, DbApplication>()

    /** Keys of the active profile's icons currently locked behind a missing pack. Compose
     * state (snapshot of the internal map) so the list rows/badges react to lock changes. */
    var lockedIconKeys: Set<String> by mutableStateOf(emptySet())
        private set

    /** Live keys of the held-back rows (refresh must not silently refill those slots). */
    val lockedKeys: Set<String> get() = lockedRows.keys

    fun isEmpty(): Boolean = lockedRows.isEmpty()

    /** Snapshot copy for iteration while rows get released along the way. */
    fun lockedRowsSnapshot(): List<Pair<String, DbApplication>> = lockedRows.toList()

    fun lock(key: String, row: DbApplication) {
        lockedRows[key] = row
    }

    fun release(key: String) {
        lockedRows.remove(key)
    }

    fun holdOrphan(key: String, row: DbApplication) {
        orphanRows[key] = row
    }

    fun clear() {
        lockedRows.clear()
        orphanRows.clear()
        publish()
    }

    /** Rows a save must write back verbatim so held icons survive it. */
    fun preservedRows(): Collection<DbApplication> = lockedRows.values + orphanRows.values

    /** A hand-picked or regenerated icon over a locked slot replaces the held-back original. */
    fun releaseReplaced(keys: Set<String>) {
        lockedRows.keys.removeAll(keys)
        publish()
    }

    /** Pushes the current locked-key set into Compose state (call after a batch of changes). */
    fun publish() {
        lockedIconKeys = lockedRows.keys.toSet()
    }

    // ---- Provenance -----------------------------------------------------------------

    // Session cache of the provenance maps carried by installed Renkin-built packs
    // (component key → original source pack). Cleared when packs re-sync or rebuild.
    private val provenanceCache = mutableMapOf<String, Map<String, String>>()

    private fun provenanceFor(packPackage: String): Map<String, String> =
        provenanceCache.getOrPut(packPackage) { PackProvenance.read(context, packPackage) }

    /** Drops cached provenance maps (packs were re-synced or rebuilt). */
    fun clearProvenanceCache() = provenanceCache.clear()

    /**
     * The REAL origin of an icon sourced from [sourcePack]: our own built packs carry a
     * provenance map, so an icon that was originally taken from pack X stays attributed to
     * X even when it arrives through a Renkin pack. Foreign packs pass through unchanged.
     */
    fun resolveOrigin(appKey: String, sourcePack: String?): String? {
        val source = sourcePack?.takeIf { it.isNotEmpty() } ?: return sourcePack
        if (!IconPackBuilder.isOwnPack(source)) return source
        return provenanceFor(source)[appKey] ?: source
    }

    /**
     * Origins recorded by the own packs among [options]' sources that are locked on this
     * device — precomputed so the (non-suspend) generation callbacks can gate on it.
     */
    suspend fun lockedOriginsFor(options: GenerationOptions): Set<String> {
        val ownPacks = listOf(options.primaryIconPack, options.secondaryIconPack)
            .filter { it.isNotEmpty() && IconPackBuilder.isOwnPack(it) }
        if (ownPacks.isEmpty()) return emptySet()
        return lockedPacksAmong(ownPacks.flatMap { provenanceFor(it).values }.toSet())
    }

    /**
     * The hand-pick path's provenance gate: translates an own-pack source to the origin its
     * provenance records and says whether that origin is locked here. Foreign packs pass
     * through unlocked — an installed pack is usable by definition.
     */
    suspend fun resolvePickedSource(appKey: String, sourcePackName: String?): Pair<String?, Boolean> =
        withContext(Dispatchers.Default) {
            val origin = resolveOrigin(appKey, sourcePackName)
            if (origin == null || origin == sourcePackName) origin to false
            else origin to lockedPacksAmong(setOf(origin)).isNotEmpty()
        }

    // ---- Verdict policy ---------------------------------------------------------------

    /** Of [packs], those whose stored icons must stay locked on this device right now. */
    suspend fun lockedPacksAmong(packs: Set<String>): Set<String> =
        verdictManager.lockedPacksAmong(packs)

    /** Records every currently installed pack as owned here forever (see PackVerdictManager). */
    suspend fun recordInstalledPacks(packs: List<dev.renkinProject.renkin.data.IconPack>) =
        verdictManager.recordInstalledPacks(packs)

    /**
     * Looks up any referenced pack still lacking a paid/free verdict (quiet best effort).
     * Returns true when a verdict became decisive — callers reload so freshly-verified-free
     * icons unlock without a restart.
     */
    suspend fun verifyPendingVerdicts(): Boolean = verdictManager.verifyPendingVerdicts()

    /** One missing source pack and how many of the active profile's icons it locks. */
    data class MissingPack(val packageName: String, val label: String, val verdict: String, val iconCount: Int)

    /** The active profile's locked icons grouped by their missing source pack. */
    suspend fun missingPackSummary(): List<MissingPack> = withContext(Dispatchers.Default) {
        val byPack = lockedRows.values.groupBy { it.sourcePackName }.filterKeys { it.isNotEmpty() }
        if (byPack.isEmpty()) return@withContext emptyList()
        val verdicts = packRepo.verdicts(byPack.keys.toList())
        byPack.map { (pack, rows) ->
            val verdict = verdicts[pack]
            MissingPack(
                packageName = pack,
                label = verdict?.label?.ifEmpty { null } ?: pack,
                verdict = verdict?.verdict ?: VERDICT_UNKNOWN,
                iconCount = rows.size
            )
        }.sortedByDescending { it.iconCount }
    }

    /** Source packs of the held-back rows (for stats that count locked icons too). */
    fun lockedSourcePacks(): List<String> =
        lockedRows.values.map { it.sourcePackName }.filter { it.isNotEmpty() }
}
