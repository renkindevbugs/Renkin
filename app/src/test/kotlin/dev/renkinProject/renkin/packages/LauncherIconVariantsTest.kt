package dev.renkinProject.renkin.packages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherIconVariantsTest {
    @Test fun currentActivityRepresentsItsResourceGroupAndLeadsTheDistinctIcons() {
        val entries = listOf(
            LauncherAliasEntry("classic", 2, "Classic"),
            LauncherAliasEntry("default-copy", 1, null),
            LauncherAliasEntry("current", 1, "Current"),
            LauncherAliasEntry("classic-copy", 2, null),
            LauncherAliasEntry("blue", 3, "Blue")
        )
        assertEquals(listOf(entries[2], entries[0], entries[4]), groupAlternateIcons("current", entries))
    }

    @Test fun oneArtworkOrAnUnresolvedCurrentActivityDoesNotOfferAnAlternatePicker() {
        assertTrue(groupAlternateIcons("current", emptyList()).isEmpty())
        assertTrue(groupAlternateIcons("current", listOf(
            LauncherAliasEntry("current", 1, null), LauncherAliasEntry("duplicate", 1, null)
        )).isEmpty())
        assertTrue(groupAlternateIcons("missing", listOf(
            LauncherAliasEntry("one", 1, null), LauncherAliasEntry("two", 2, null)
        )).isEmpty())
    }
}
