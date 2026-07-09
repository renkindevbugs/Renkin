package dev.renkinProject.renkin.data.transfer

import android.app.Application
import dev.renkinProject.renkin.data.DEFAULT_PROFILE_ID
import dev.renkinProject.renkin.data.DbApplication
import dev.renkinProject.renkin.data.Profile
import dev.renkinProject.renkin.data.watch.AppComponent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Round-trips [BackupData] through [BackupCodec] — the backup file's schema contract. Runs
 * under Robolectric only for the real org.json implementation (android.jar ships stubs).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class BackupCodecTest {

    private fun sampleData(): BackupData {
        val defaultProfile = Profile(
            id = DEFAULT_PROFILE_ID,
            name = "Renkin",
            packLabel = "Renkin Pack",
            prefsSnapshot = """{"PRIMARY_SOURCE":1}"""
        )
        val second = Profile(
            id = 5L,
            name = "Dark set",
            description = "night icons — with dash",
            packLabel = "Renkin Dark",
            hasUnbuiltChanges = true
        )
        val icons = listOf(
            DbApplication(
                "com.a", "com.a.Main", isAdaptiveIcon = true, isXml = false,
                drawable = "aGVsbG8=", calendarEnabled = true, calendarPrefix = "day_",
                calendarPackName = "pack.cal", sourcePackName = "pack.x",
                profileId = DEFAULT_PROFILE_ID
            ),
            DbApplication(
                "com.b", "com.b.Main", isAdaptiveIcon = false, isXml = true,
                drawable = "PHZlY3Rvci8+", profileId = DEFAULT_PROFILE_ID
            )
        )
        val rules = listOf(
            BackupWatchRule(
                watchAllPacks = false, completed = false, createdAt = 111L, completedAt = null,
                apps = listOf(AppComponent("com.a", "com.a.Main")),
                packs = listOf("pack.x", "pack.y")
            ),
            BackupWatchRule(
                watchAllPacks = true, completed = true, createdAt = 222L, completedAt = 333L,
                apps = listOf(AppComponent("com.b", "com.b.Main")),
                packs = emptyList()
            )
        )
        return BackupData(
            profiles = listOf(
                BackupProfile(defaultProfile, icons, rules),
                BackupProfile(second, emptyList(), emptyList())
            ),
            prefs = mapOf(
                "SOME_BOOL" to BackupPref(BackupPref.BOOL, true),
                "SOME_INT" to BackupPref(BackupPref.INT, 42),
                "SOME_LONG" to BackupPref(BackupPref.LONG, 7_000_000_000L),
                "SOME_STRING" to BackupPref(BackupPref.STRING, "hodnota s diakritikou č"),
                "SOME_FLOAT" to BackupPref(BackupPref.FLOAT, 1.5f),
                "SOME_DOUBLE" to BackupPref(BackupPref.DOUBLE, 2.25),
                "SOME_SET" to BackupPref(BackupPref.STRING_SET, setOf("a", "b"))
            )
        )
    }

    @Test
    fun roundTrip_preservesEverything() {
        val original = sampleData()

        val decoded = BackupCodec.decode(BackupCodec.encode(original))

        assertEquals(original, decoded)
    }

    @Test
    fun decode_longsSurviveIntRange() {
        val decoded = BackupCodec.decode(BackupCodec.encode(sampleData()))

        // A Long above Int.MAX_VALUE must come back as a Long, not overflow through an Int path.
        assertEquals(7_000_000_000L, decoded.prefs.getValue("SOME_LONG").value)
    }

    @Test
    fun decode_skipsUnknownPrefTags() {
        val json = """{"prefs":{"FUTURE":{"t":"quantum","v":1},"OK":{"t":"int","v":3}},"profiles":[]}"""

        val decoded = BackupCodec.decode(json)

        assertEquals(setOf("OK"), decoded.prefs.keys)
    }

    @Test
    fun decode_malformedInputThrows() {
        val garbage = "not json at all"

        assertTrue(runCatching { BackupCodec.decode(garbage) }.isFailure)
    }

    @Test
    fun decode_iconsCarryTheirProfileId() {
        val decoded = BackupCodec.decode(BackupCodec.encode(sampleData()))

        assertTrue(decoded.profiles.first().icons.all { it.profileId == DEFAULT_PROFILE_ID })
    }
}
