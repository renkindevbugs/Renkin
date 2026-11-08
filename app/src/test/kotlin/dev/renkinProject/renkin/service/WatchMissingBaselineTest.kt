package dev.renkinProject.renkin.service

import dev.renkinProject.renkin.data.watch.AppComponent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Import initialises only watch pairs without a baseline; complete rules resolve nothing. */
class WatchMissingBaselineTest {
    private val mail = AppComponent("app.mail", "Main")
    private val maps = AppComponent("app.maps", "Main")

    @Test
    fun completeRuleNeedsNoPackWork() {
        val existing = setOf(
            Triple("app.mail", "Main", "pack.a"), Triple("app.maps", "Main", "pack.a"),
            Triple("app.mail", "Main", "pack.b"), Triple("app.maps", "Main", "pack.b")
        )

        assertTrue(missingBaselinePairs(listOf(mail, maps), listOf("pack.a", "pack.b"), existing).isEmpty())
    }

    @Test
    fun onlyMissingAppsOfAffectedPacksAreReturned() {
        val existing = setOf(Triple("app.mail", "Main", "pack.a"), Triple("app.maps", "Main", "pack.a"),
            Triple("app.mail", "Main", "pack.b"))

        val missing = missingBaselinePairs(listOf(mail, maps), listOf("pack.a", "pack.b", "pack.c"), existing)

        assertEquals(mapOf("pack.b" to listOf(maps), "pack.c" to listOf(mail, maps)), missing)
    }
}
