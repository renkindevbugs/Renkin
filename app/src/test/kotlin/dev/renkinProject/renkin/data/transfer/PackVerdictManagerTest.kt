package dev.renkinProject.renkin.data.transfer

import android.app.Application
import android.content.Context
import androidx.room.Room
import dev.renkinProject.renkin.data.IconPack
import dev.renkinProject.renkin.data.PackVerdict
import dev.renkinProject.renkin.data.RenkinPackDatabase
import dev.renkinProject.renkin.data.RenkinPackRepository
import dev.renkinProject.renkin.data.VERDICT_FREE
import dev.renkinProject.renkin.data.VERDICT_LISTED
import dev.renkinProject.renkin.data.VERDICT_PAID
import dev.renkinProject.renkin.data.VERDICT_UNLISTED
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The paid-pack lock rules against an in-memory database, with an injected fake Play lookup.
 * No test package is ever "installed" under Robolectric, so the installed-pack short-circuit
 * is exercised through [PackVerdict.seenInstalled] and [PackVerdictManager.recordInstalledPacks].
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class PackVerdictManagerTest {

    private lateinit var db: RenkinPackDatabase
    private lateinit var repo: RenkinPackRepository
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, RenkinPackDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = RenkinPackRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun lockedPacksAmong_locksAnythingInstallableOrUnverified_notUnlistedOrOwned() = runBlocking {
        repo.upsertVerdicts(
            listOf(
                // Installable somewhere (free/paid on Play, or on F-Droid) -> must install.
                PackVerdict("pack.free", VERDICT_FREE),
                PackVerdict("pack.paid", VERDICT_PAID),
                PackVerdict("pack.fdroid", VERDICT_LISTED),
                // On no known store -> can't be installed, so its icons stay usable.
                PackVerdict("pack.unlisted", VERDICT_UNLISTED),
                // Owned here beats any verdict.
                PackVerdict("pack.owned", VERDICT_PAID, seenInstalled = true)
            )
        )
        val manager = PackVerdictManager(context, repo) { StoreLookupResult(StoreVerdict.UNKNOWN) }

        val locked = manager.lockedPacksAmong(
            setOf("pack.free", "pack.paid", "pack.fdroid", "pack.unlisted", "pack.owned", "pack.never.seen")
        )

        // Free now locks too (must install); only unlisted + owned stay unlocked.
        assertEquals(setOf("pack.free", "pack.paid", "pack.fdroid", "pack.never.seen"), locked)
    }

    @Test
    fun lockedPacksAmong_alwaysLocksIconPackStudioExports_evenWhenUnlisted() = runBlocking {
        // Icon Pack Studio stamps every export with this same package; it's never on a store.
        repo.upsertVerdicts(listOf(PackVerdict("ginlemon.iconpackstudio.exported", VERDICT_UNLISTED)))
        val manager = PackVerdictManager(context, repo) { StoreLookupResult(StoreVerdict.UNKNOWN) }

        val locked = manager.lockedPacksAmong(setOf("ginlemon.iconpackstudio.exported"))

        assertEquals(setOf("ginlemon.iconpackstudio.exported"), locked)
    }

    @Test
    fun lockedPacksAmong_neverLocksRenkinsOwnPacks() = runBlocking {
        val manager = PackVerdictManager(context, repo) { StoreLookupResult(StoreVerdict.UNKNOWN) }

        val locked = manager.lockedPacksAmong(setOf("dev.renkinProject.renkinpack.p3", ""))

        assertTrue(locked.isEmpty())
    }

    @Test
    fun ensureVerdicts_looksUpUndecided_storesVerdicts_returnsInstallableOnes() = runBlocking {
        val manager = PackVerdictManager(context, repo) { pack ->
            when (pack) {
                "pack.free" -> StoreLookupResult(StoreVerdict.FREE, "Free Icons")
                "pack.paid" -> StoreLookupResult(StoreVerdict.PAID, "Paid Icons")
                "pack.fdroid" -> StoreLookupResult(StoreVerdict.LISTED, "FOSS Icons")
                "pack.gone" -> StoreLookupResult(StoreVerdict.UNLISTED)
                else -> StoreLookupResult(StoreVerdict.UNKNOWN)
            }
        }

        val installable = manager.ensureVerdicts(
            setOf("pack.free", "pack.paid", "pack.fdroid", "pack.gone", "pack.mystery")
        )

        // Everything still installable or unverified; only the found-nowhere pack drops out.
        assertEquals(setOf("pack.free", "pack.paid", "pack.fdroid", "pack.mystery"), installable)
        val stored = repo.verdicts(listOf("pack.free", "pack.paid", "pack.fdroid", "pack.gone"))
        assertEquals(VERDICT_FREE, stored.getValue("pack.free").verdict)
        assertEquals(VERDICT_PAID, stored.getValue("pack.paid").verdict)
        assertEquals(VERDICT_LISTED, stored.getValue("pack.fdroid").verdict)
        assertEquals(VERDICT_UNLISTED, stored.getValue("pack.gone").verdict)
        // The store-listed name fills the label so the missing-packs dialog can show it.
        assertEquals("Paid Icons", stored.getValue("pack.paid").label)
    }

    @Test
    fun ensureVerdicts_marksIconPackStudioUnlisted_withoutALookup() = runBlocking {
        var lookups = 0
        val manager = PackVerdictManager(context, repo) { lookups++; StoreLookupResult(StoreVerdict.FREE) }

        manager.ensureVerdicts(setOf("ginlemon.iconpackstudio.exported"))

        assertEquals(0, lookups) // never hits the network for a per-user export
        val stored = repo.verdicts(listOf("ginlemon.iconpackstudio.exported"))
        assertEquals(VERDICT_UNLISTED, stored.getValue("ginlemon.iconpackstudio.exported").verdict)
    }

    @Test
    fun recordInstalledPacks_marksOwnershipForever() = runBlocking {
        val manager = PackVerdictManager(context, repo) { StoreLookupResult(StoreVerdict.UNKNOWN) }
        manager.recordInstalledPacks(listOf(IconPack("pack.x", "X Icons", 1L, "1.0", 0)))

        val verdict = repo.verdicts(listOf("pack.x")).getValue("pack.x")
        assertTrue(verdict.seenInstalled)
        assertEquals("X Icons", verdict.label)
        // Ownership beats a later paid verdict — the uninstall scenario.
        repo.upsertVerdicts(listOf(verdict.copy(verdict = VERDICT_PAID)))
        assertTrue(manager.lockedPacksAmong(setOf("pack.x")).isEmpty())
    }
}
