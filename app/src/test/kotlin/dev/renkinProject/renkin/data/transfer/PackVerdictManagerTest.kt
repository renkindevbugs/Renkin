package dev.renkinProject.renkin.data.transfer

import android.app.Application
import android.content.Context
import androidx.room.Room
import dev.renkinProject.renkin.data.IconPack
import dev.renkinProject.renkin.data.PackVerdict
import dev.renkinProject.renkin.data.RenkinPackDatabase
import dev.renkinProject.renkin.data.RenkinPackRepository
import dev.renkinProject.renkin.data.VERDICT_FREE
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
    fun lockedPacksAmong_locksPaidAndUnverified_notFreeUnlistedOrOwned() = runBlocking {
        repo.upsertVerdicts(
            listOf(
                PackVerdict("pack.free", VERDICT_FREE),
                PackVerdict("pack.unlisted", VERDICT_UNLISTED),
                PackVerdict("pack.paid", VERDICT_PAID),
                PackVerdict("pack.owned", VERDICT_PAID, seenInstalled = true)
            )
        )
        val manager = PackVerdictManager(context, repo) { StoreLookupResult(StoreVerdict.UNKNOWN) }

        val locked = manager.lockedPacksAmong(
            setOf("pack.free", "pack.unlisted", "pack.paid", "pack.owned", "pack.never.seen")
        )

        assertEquals(setOf("pack.paid", "pack.never.seen"), locked)
    }

    @Test
    fun lockedPacksAmong_neverLocksRenkinsOwnPacks() = runBlocking {
        val manager = PackVerdictManager(context, repo) { StoreLookupResult(StoreVerdict.UNKNOWN) }

        val locked = manager.lockedPacksAmong(setOf("dev.renkinProject.renkinpack.p3", ""))

        assertTrue(locked.isEmpty())
    }

    @Test
    fun ensureVerdicts_looksUpUndecided_returnsPacksToStrip() = runBlocking {
        val manager = PackVerdictManager(context, repo) { pack ->
            when (pack) {
                "pack.free" -> StoreLookupResult(StoreVerdict.FREE, "Free Icons")
                "pack.paid" -> StoreLookupResult(StoreVerdict.PAID, "Paid Icons")
                "pack.gone" -> StoreLookupResult(StoreVerdict.UNLISTED)
                else -> StoreLookupResult(StoreVerdict.UNKNOWN)
            }
        }

        val strip = manager.ensureVerdicts(setOf("pack.free", "pack.paid", "pack.gone", "pack.mystery"))

        // Paid AND unverifiable are stripped (fail-closed); free and unlisted embed.
        assertEquals(setOf("pack.paid", "pack.mystery"), strip)
        val stored = repo.verdicts(listOf("pack.free", "pack.paid", "pack.gone"))
        assertEquals(VERDICT_FREE, stored.getValue("pack.free").verdict)
        assertEquals(VERDICT_PAID, stored.getValue("pack.paid").verdict)
        assertEquals(VERDICT_UNLISTED, stored.getValue("pack.gone").verdict)
        // The store-listed name fills the label so the missing-packs dialog can show it.
        assertEquals("Paid Icons", stored.getValue("pack.paid").label)
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
