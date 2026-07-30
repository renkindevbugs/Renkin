package dev.renkinProject.renkin.service

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class WatchCheckerIsolationTest {

    @Test
    fun failedPackRead_isNotCachedAndCanRetryTheSameVersion() {
        var calls = 0

        val first = readWatchPackOrNull("broken", onFailure = {}) {
            calls++
            error("temporarily unreadable")
        }
        val second = readWatchPackOrNull("broken", onFailure = {}) {
            calls++
            "healthy"
        }

        assertNull(first)
        assertEquals("healthy", second)
        assertEquals(2, calls)
    }

    @Test
    fun successfulNoIconResult_isDistinctFromReadFailure() {
        val result = readWatchPackOrNull("healthy", onFailure = {}) {
            null to null
        }

        assertNotNull(result)
        assertEquals(null to null, result)
    }

    @Test
    fun cancellation_isNeverConvertedToAnUnreadablePack() {
        assertThrows(CancellationException::class.java) {
            readWatchPackOrNull("cancelled", onFailure = {}) {
                throw CancellationException("cancel")
            }
        }
    }
}
