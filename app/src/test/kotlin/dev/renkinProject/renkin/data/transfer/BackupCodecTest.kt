package dev.renkinProject.renkin.data.transfer

import android.app.Application
import dev.renkinProject.renkin.data.AppTarget
import dev.renkinProject.renkin.data.AppTargetCodec
import dev.renkinProject.renkin.data.DEFAULT_PROFILE_ID
import dev.renkinProject.renkin.data.DbApplication
import dev.renkinProject.renkin.data.Profile
import dev.renkinProject.renkin.data.watch.AppComponent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Round-trips [BackupData] through [BackupCodec] — the backup file's schema contract. Runs
 * under Robolectric only for the real org.json implementation (android.jar ships stubs).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class BackupCodecTest {

    @Test fun recipeRoundTripsAndBadOrMissingMetadataDoesNotDiscardArtwork() {
        val recipe = dev.renkinProject.renkin.icon.creator.ModifierRecipeCodec.encode(
            dev.renkinProject.renkin.icon.creator.ModifierStack(
                dev.renkinProject.renkin.icon.creator.EffectModifier(
                    dev.renkinProject.renkin.data.ImageEdit.NONE,
                    dev.renkinProject.renkin.icon.creator.ColorizerStyle(firstColor = 0xff000000.toInt())
                )
            )
        )
        val data = sampleData()
        val profile = data.profiles.first()
        val icon = profile.icons.first().copy(modifierRecipe = recipe)
        val encoded = BackupCodec.encode(data.copy(profiles = listOf(profile.copy(icons = listOf(icon)))), BackupCodec.LEGACY_FORMAT_VERSION)
        assertEquals(icon, BackupCodec.decode(encoded, BackupCodec.LEGACY_FORMAT_VERSION).profiles.single().icons.single())
        val json = JSONObject(encoded)
        val storedIcon = json.getJSONArray("profiles").getJSONObject(0).getJSONArray("icons").getJSONObject(0)
        storedIcon.put("modifierRecipe", "recipe.version=999")
        assertEquals(icon.copy(modifierRecipe = ""), BackupCodec.decode(json.toString(), BackupCodec.LEGACY_FORMAT_VERSION).profiles.single().icons.single())
        storedIcon.remove("modifierRecipe")
        assertEquals(icon.copy(modifierRecipe = ""), BackupCodec.decode(json.toString(), BackupCodec.LEGACY_FORMAT_VERSION).profiles.single().icons.single())
    }

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
                profileId = DEFAULT_PROFILE_ID, isCustomIcon = true
            ),
            DbApplication(
                "com.b", "com.b.Main", isAdaptiveIcon = false, isXml = true,
                drawable = "PHZlY3Rvci8+", profileId = DEFAULT_PROFILE_ID,
                isLegacyIcon = true
            )
        )
        val rules = listOf(
            BackupWatchRule(
                watchAllPacks = false, completed = false, createdAt = 111L, completedAt = null,
                apps = listOf(AppComponent("com.a", "com.a.Main")),
                packs = listOf("pack.x", "pack.y"),
                baselines = listOf(
                    BackupWatchState(
                        "com.a", "com.a.Main", "pack.x", 12L,
                        "drawable_a", "hash-a", 444L
                    )
                )
            ),
            BackupWatchRule(
                watchAllPacks = true, completed = true, createdAt = 222L, completedAt = 333L,
                apps = listOf(AppComponent("com.b", "com.b.Main")),
                packs = listOf("pack.z"),
                suggestions = listOf(
                    BackupWatchSuggestion(
                        "com.b", "com.b.Main", 333L,
                        listOf(BackupWatchCandidate("pack.z", "drawable_b", "hash-b"))
                    )
                )
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
            ),
            colorPresets = listOf(
                BackupColorPreset("Color 1", "0;0;-65536;-16777216;0.0;false;false;false", 10L),
                BackupColorPreset("Sunset", "1;0;-39424;-16776961;90.0;true;false;false", 20L)
            ),
            modifierPresets = listOf(
                BackupModifierPreset("Warm plate", "v=1\nshape=1", 1, 30L, 40L)
            )
        )
    }

    @Test
    fun decode_acceptsArchivesWithoutSavedColours() {
        // Files written before the colour library existed simply have no entry for it.
        val json = """{"prefs":{},"profiles":[]}"""

        assertTrue(BackupCodec.decode(json, BackupCodec.LEGACY_FORMAT_VERSION).colorPresets.isEmpty())
        assertTrue(BackupCodec.decode(json, BackupCodec.LEGACY_FORMAT_VERSION).modifierPresets.isEmpty())
    }

    @Test
    fun roundTrip_preservesEverything() {
        val original = sampleData()

        val decoded = BackupCodec.decode(BackupCodec.encode(original, BackupCodec.LEGACY_FORMAT_VERSION), BackupCodec.LEGACY_FORMAT_VERSION)

        assertEquals(original, decoded)
    }

    @Test
    fun addedAppsRoundTripAndInvalidEntriesAreDroppedWithoutLosingTheProfile() {
        val targets = AppTargetCodec.encode(listOf(AppTarget("org.fdroid.fdroid", "org.fdroid.fdroid.views.main.MainActivity", "F-Droid")))
        val data = sampleData()
        val withTargets = data.copy(profiles = data.profiles.map { it.copy(profile = it.profile.copy(appTargets = targets)) })
        val decoded = BackupCodec.decode(BackupCodec.encode(withTargets, BackupCodec.LEGACY_FORMAT_VERSION), BackupCodec.LEGACY_FORMAT_VERSION)
        assertEquals(withTargets, decoded)

        val root = JSONObject(BackupCodec.encode(data, BackupCodec.LEGACY_FORMAT_VERSION))
        val first = root.getJSONArray("profiles").getJSONObject(0)
        first.put("appTargets", """[{"packageName":"bad","activityName":"x","label":"Broken"},""" +
            """{"packageName":"com.ok","activityName":".Main","label":"Ok"}]""")
        root.getJSONArray("profiles").getJSONObject(1).remove("appTargets")
        val lenient = BackupCodec.decode(root.toString(), BackupCodec.LEGACY_FORMAT_VERSION)
        assertEquals(listOf(AppTarget("com.ok", "com.ok.Main", "Ok")), AppTargetCodec.decode(lenient.profiles[0].profile.appTargets))
        assertEquals("", lenient.profiles[1].profile.appTargets)
    }

    @Test
    fun decode_longsSurviveIntRange() {
        val decoded = BackupCodec.decode(BackupCodec.encode(sampleData(), BackupCodec.LEGACY_FORMAT_VERSION), BackupCodec.LEGACY_FORMAT_VERSION)

        // A Long above Int.MAX_VALUE must come back as a Long, not overflow through an Int path.
        assertEquals(7_000_000_000L, decoded.prefs.getValue("SOME_LONG").value)
    }

    @Test
    fun decode_skipsUnknownPrefTags() {
        val json = """{"prefs":{"FUTURE":{"t":"quantum","v":1},"OK":{"t":"int","v":3}},"profiles":[]}"""

        val decoded = BackupCodec.decode(json, BackupCodec.LEGACY_FORMAT_VERSION)

        assertEquals(setOf("OK"), decoded.prefs.keys)
    }

    @Test
    fun decode_malformedInputThrows() {
        val garbage = "not json at all"

        assertTrue(runCatching { BackupCodec.decode(garbage, BackupCodec.LEGACY_FORMAT_VERSION) }.isFailure)
    }

    @Test
    fun decode_iconsCarryTheirProfileId() {
        val decoded = BackupCodec.decode(BackupCodec.encode(sampleData(), BackupCodec.LEGACY_FORMAT_VERSION), BackupCodec.LEGACY_FORMAT_VERSION)

        assertTrue(decoded.profiles.first().icons.all { it.profileId == DEFAULT_PROFILE_ID })
    }

    @Test
    fun decode_preClassificationIconsKeepHistoricalOriginMarker() {
        val root = JSONObject(BackupCodec.encode(sampleData(), BackupCodec.LEGACY_FORMAT_VERSION))
        val icon = root.getJSONArray("profiles").getJSONObject(0)
            .getJSONArray("icons").getJSONObject(0)
        icon.remove("isCustomIcon")
        icon.remove("isLegacyIcon")

        val decoded = BackupCodec.decode(root.toString(), BackupCodec.LEGACY_FORMAT_VERSION)

        assertTrue(decoded.profiles.first().icons.first().isLegacyIcon)
    }

    @Test
    fun decode_preBasePayloadUsesRenderedIconAsSafeBase() {
        val root = JSONObject(BackupCodec.encode(sampleData(), BackupCodec.LEGACY_FORMAT_VERSION))
        val icon = root.getJSONArray("profiles").getJSONObject(0)
            .getJSONArray("icons").getJSONObject(0)
        icon.remove("baseDrawable")
        icon.remove("baseIsAdaptiveIcon")
        icon.remove("baseIsXml")

        val decoded = BackupCodec.decode(root.toString(), BackupCodec.LEGACY_FORMAT_VERSION).profiles.first().icons.first()

        assertEquals(decoded.drawable, decoded.baseDrawable)
        assertEquals(decoded.isAdaptiveIcon, decoded.baseIsAdaptiveIcon)
        assertEquals(decoded.isXml, decoded.baseIsXml)
    }
}
