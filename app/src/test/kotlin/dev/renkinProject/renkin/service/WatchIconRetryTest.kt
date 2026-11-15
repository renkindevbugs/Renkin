package dev.renkinProject.renkin.service

import dev.renkinProject.renkin.data.watch.WatchState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchIconRetryTest {
    private fun state(name: String?, hash: String?) =
        WatchState(1, "app", "Main", "pack", 7, name, hash, 123)

    @Test fun aPreviouslyCheckpointedBrokenMappingRetriesWithoutAPackUpdate() {
        assertTrue(shouldCheckWatchIcon(state("authenticator_google", null), 7))
    }

    @Test fun aSuccessfullyReadIconStillSkipsAnUnchangedPack() {
        assertFalse(shouldCheckWatchIcon(state("icon", "hash"), 7))
    }

    @Test fun aGenuinelyUnthemedAppStillSkipsAnUnchangedPack() {
        assertFalse(shouldCheckWatchIcon(state(null, null), 7))
    }

    @Test fun anUpdatedPackAndANewPairAreChecked() {
        assertTrue(shouldCheckWatchIcon(state("icon", "hash"), 8))
        assertTrue(shouldCheckWatchIcon(state(null, null), 8))
        assertTrue(shouldCheckWatchIcon(null, 7))
    }
}
