package dev.renkinProject.renkin.data.watch

import android.app.Application
import androidx.datastore.preferences.core.edit
import dev.renkinProject.renkin.data.LastWatchCheckAtKey
import dev.renkinProject.renkin.data.StorageOperationGate
import dev.renkinProject.renkin.data.dataStore
import dev.renkinProject.renkin.data.getPreferencesAfterPendingWrites
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class WatchCheckStatusStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun setUp() {
        StorageOperationGate.resetForTests()
        runBlocking { context.dataStore.edit { it.clear() } }
    }

    @After fun tearDown() = runBlocking {
        context.dataStore.getPreferencesAfterPendingWrites()
        context.dataStore.edit { it.clear() }
        StorageOperationGate.resetForTests()
    }

    private suspend fun persistedStatus(): WatchCheckStatus? {
        context.dataStore.getPreferencesAfterPendingWrites()
        return withTimeout(5_000) { WatchCheckStatusStore(context).status.first() }
    }

    @Test fun oldFailedPackNamesRemainReadableWithoutStructuredIssues() = runBlocking {
        context.dataStore.edit {
            it[WatchLastCheckResultKey] = """{
                "checkedAt":5000,"outcome":"PARTIAL","foundCount":9,
                "lastSuccessfulAt":1000,"failedPackNames":["Material You","Other pack"]
            }"""
        }

        assertEquals(
            WatchCheckStatus(5000, WatchCheckOutcome.PARTIAL, 9, 1000, listOf("Material You", "Other pack")),
            persistedStatus()
        )
    }

    @Test fun structuredIssuesRoundTripWithAppPackAndDrawableDetails() = runBlocking {
        val missingDrawable = WatchReadIssue(
            packPackage = "icons.material.you",
            reason = WatchReadFailureReason.MISSING_DRAWABLE,
            appPackage = "app.authenticator",
            activityName = "app.authenticator.MainActivity",
            drawableName = "authenticator",
            packName = "Material You",
            appName = "Authenticator"
        )
        val issues = listOf(
            missingDrawable,
            WatchReadIssue("icons.broken", WatchReadFailureReason.PACK_UNREADABLE),
            WatchReadIssue(
                "icons.other", WatchReadFailureReason.ICON_UNREADABLE,
                appPackage = "app.other", drawableName = "other_icon"
            )
        )

        WatchCheckStatusStore(context).record(
            StorageOperationGate.epoch, WatchCheckOutcome.PARTIAL, foundCount = 9,
            failedPackNames = listOf("Material You", "Material You", "Broken pack"),
            readIssues = issues + missingDrawable
        )

        val status = checkNotNull(persistedStatus())
        assertEquals(WatchCheckOutcome.PARTIAL, status.outcome)
        assertEquals(9, status.foundCount)
        assertEquals(listOf("Material You", "Broken pack"), status.failedPackNames)
        assertEquals(issues, status.readIssues)
        assertTrue(status.checkedAt > 0)
        assertNotNull(context.dataStore.getPreferencesAfterPendingWrites()[WatchLastCheckResultKey])
    }

    @Test fun malformedIssuesAreSkippedAndUnknownReasonKeepsTheRestOfTheStatus() = runBlocking {
        context.dataStore.edit {
            it[WatchLastCheckResultKey] = """{
                "checkedAt":5000,"outcome":"PARTIAL","foundCount":4,"lastSuccessfulAt":1000,
                "failedPackNames":["Material You"],
                "readIssues":[
                    null,"not an issue",42,{},
                    {"packPackage":" "},{"packPackage":7},
                    {"packPackage":"icons.future","reason":"NEW_REASON",
                     "packName":null,"appPackage":"app.authenticator","appName":42,
                     "activityName":" ","drawableName":"authenticator"},
                    {"packPackage":"icons.known","reason":"PACK_UNREADABLE","packName":"Known pack"}
                ]
            }"""
        }

        assertEquals(
            WatchCheckStatus(
                5000, WatchCheckOutcome.PARTIAL, 4, 1000, listOf("Material You"),
                listOf(
                    WatchReadIssue(
                        "icons.future", WatchReadFailureReason.ICON_UNREADABLE,
                        appPackage = "app.authenticator", drawableName = "authenticator"
                    ),
                    WatchReadIssue("icons.known", WatchReadFailureReason.PACK_UNREADABLE, packName = "Known pack")
                )
            ),
            persistedStatus()
        )
    }

    @Test fun partialAndFailedChecksPreservePreviousSuccessfulTime() = runBlocking {
        context.dataStore.edit {
            it[LastWatchCheckAtKey] = 900L
            it[WatchLastCheckResultKey] = """{
                "checkedAt":2000,"outcome":"COMPLETE","foundCount":0,"lastSuccessfulAt":1500
            }"""
        }
        val store = WatchCheckStatusStore(context)

        store.record(StorageOperationGate.epoch, WatchCheckOutcome.PARTIAL, foundCount = 9)
        assertEquals(1500L, persistedStatus()!!.lastSuccessfulAt)

        store.record(StorageOperationGate.epoch, WatchCheckOutcome.FAILED)
        assertEquals(1500L, persistedStatus()!!.lastSuccessfulAt)
        assertEquals(900L, context.dataStore.getPreferencesAfterPendingWrites()[LastWatchCheckAtKey])
    }

    @Test fun firstPartialCheckKeepsTheLegacySuccessfulTimestamp() = runBlocking {
        context.dataStore.edit { it[LastWatchCheckAtKey] = 1234L }

        WatchCheckStatusStore(context).record(StorageOperationGate.epoch, WatchCheckOutcome.PARTIAL)

        assertEquals(1234L, persistedStatus()!!.lastSuccessfulAt)
        assertEquals(1234L, context.dataStore.getPreferencesAfterPendingWrites()[LastWatchCheckAtKey])
    }

    @Test fun completeCheckUpdatesBothSuccessfulTimestampsAndClearsOldFailures() = runBlocking {
        context.dataStore.edit {
            it[LastWatchCheckAtKey] = 1234L
            it[WatchLastCheckResultKey] = """{
                "checkedAt":2000,"outcome":"PARTIAL","foundCount":1,"lastSuccessfulAt":1234,
                "failedPackNames":["Material You"],
                "readIssues":[{"packPackage":"icons.material.you","reason":"PACK_UNREADABLE"}]
            }"""
        }

        WatchCheckStatusStore(context).record(StorageOperationGate.epoch, WatchCheckOutcome.COMPLETE, foundCount = 2)

        val status = checkNotNull(persistedStatus())
        assertEquals(WatchCheckOutcome.COMPLETE, status.outcome)
        assertEquals(2, status.foundCount)
        assertTrue(status.checkedAt > 2000)
        assertEquals(status.checkedAt, status.lastSuccessfulAt)
        assertEquals(status.checkedAt, context.dataStore.getPreferencesAfterPendingWrites()[LastWatchCheckAtKey])
        assertTrue(status.failedPackNames.isEmpty())
        assertTrue(status.readIssues.isEmpty())
    }

    @Test fun resultFromBeforeStorageReplacementDoesNotPublish() = runBlocking {
        val store = WatchCheckStatusStore(context)
        val oldEpoch = StorageOperationGate.epoch
        store.record(oldEpoch, WatchCheckOutcome.COMPLETE, foundCount = 2)
        val before = context.dataStore.getPreferencesAfterPendingWrites()
        val previousStatus = persistedStatus()
        StorageOperationGate.run { StorageOperationGate.markStorageReplaced() }

        store.record(
            oldEpoch, WatchCheckOutcome.PARTIAL, foundCount = 9,
            failedPackNames = listOf("Material You"),
            readIssues = listOf(WatchReadIssue("icons.material.you", WatchReadFailureReason.MISSING_DRAWABLE))
        )

        assertEquals(before, context.dataStore.getPreferencesAfterPendingWrites())
        assertEquals(previousStatus, persistedStatus())
    }
}
