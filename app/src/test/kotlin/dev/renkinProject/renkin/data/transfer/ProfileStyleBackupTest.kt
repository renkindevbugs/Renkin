package dev.renkinProject.renkin.data.transfer

import android.app.Application
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import dev.renkinProject.renkin.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.io.StringWriter

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class ProfileStyleBackupTest {
    private fun fixture(values: Preferences = preferencesOf(
        PrimarySourceKey to -99, GlobalColorizeKey to false, OutlineAddKey to false,
        GlobalColorizerGradientColorsKey to "unresolved,disabled", TextFontKey to "/missing/font.ttf",
        GlobalIconScaleKey to 87
    )): BackupData {
        val style = ProfileStylePreferences.capture(values)
        val document = "\n" + JSONObject(ProfileStyleCodec.encode(style)).toString(2) + "\n"
        val profile = Profile(id = 1, name = "Saved", profileStyle = document,
            prefsSnapshot = values.snapshotProfilePrefs())
        return BackupData(listOf(BackupProfile(profile, emptyList(), emptyList())),
            values.asMap().map { (key, value) -> key.name to checkNotNull(BackupPref.of(value)) }.toMap() +
                (ActiveProfileIdKey.name to BackupPref(BackupPref.LONG, 1L)))
    }

    private fun wire(data: BackupData = fixture()) = JSONObject(BackupCodec.encode(data, 4))
    private fun profile(root: JSONObject) = root.getJSONArray("profiles").getJSONObject(0)
    private fun validate(root: JSONObject) = BackupValidator.prepare(
        BackupCodec.decode(root.toString(), 4), BackupManager.ImportKind.BACKUP, 4)

    private fun rejected(root: JSONObject) {
        assertTrue(root.toString().take(150), runCatching { validate(root) }.isFailure)
    }

    @Test fun versionFourPreservesExactDocumentUnknownOrdinalsDisabledAndThemeValues() {
        val original = fixture()
        val decoded = validate(wire(original))
        assertEquals(original, decoded)
        assertNull(ProfileStyleCodec.decode(decoded.profiles.single().profile.profileStyle)
            .export.background.firstColor)
        assertEquals(-99, ProfileStylePreferences.toPreferences(
            ProfileStyleCodec.decode(decoded.profiles.single().profile.profileStyle))[PrimarySourceKey])
    }

    @Test fun versionFourStreamUsesTheSameContractAsStringCodec() = runBlocking {
        val original = fixture()
        val output = StringWriter()
        writeBackupData(original, output, 4)
        assertEquals(original, BackupValidator.prepare(readBackupData(output.toString().reader(), 4),
            BackupManager.ImportKind.BACKUP, 4))
    }

    @Test fun legacyFormatsRemainExplicitAndCannotCarryTypedDocuments() {
        for (format in 1..3) {
            val root = JSONObject(BackupCodec.encode(fixture(), format))
            assertFalse(profile(root).has("profileStyle"))
            val decoded = BackupValidator.prepare(BackupCodec.decode(root.toString(), format),
                BackupManager.ImportKind.BACKUP, format)
            assertEquals("", decoded.profiles.single().profile.profileStyle)
            profile(root).put("profileStyle", fixture().profiles.single().profile.profileStyle)
            assertTrue(runCatching { BackupCodec.decode(root.toString(), format) }.exceptionOrNull() is IOException)
        }
    }

    @Test fun unsupportedFormatsAreRejectedByEveryPublicBoundary() = runBlocking {
        val data = fixture()
        for (format in listOf(Int.MIN_VALUE, 0, 5, Int.MAX_VALUE)) {
            assertTrue(runCatching { BackupCodec.encode(data, format) }.isFailure)
            assertTrue(runCatching { BackupCodec.decode(wire(data).toString(), format) }.isFailure)
            assertTrue(runCatching { BackupValidator.prepare(data, BackupManager.ImportKind.BACKUP, format) }.isFailure)
            assertTrue(runCatching { writeBackupData(data, StringWriter(), format) }.isFailure)
            assertTrue(runCatching { readBackupData(wire(data).toString().reader(), format) }.isFailure)
        }
    }

    @Test fun missingNullAndNonStringDocumentsNeverFallBackToMatchingLegacyCopy() {
        val missing = wire().also { profile(it).remove("profileStyle") }
        rejected(missing)
        for (value in listOf(JSONObject.NULL, 7, true, JSONObject(), "")) {
            rejected(wire().also { profile(it).put("profileStyle", value) })
        }
    }

    @Test fun malformedUnsupportedDuplicateAndOversizedDocumentsAreRejected() {
        val valid = fixture().profiles.single().profile.profileStyle
        val invalid = listOf("{}", "not json", valid + " {}",
            valid.replace("\"version\": 1", "\"version\": 99"),
            valid.replace("\"version\": 1", "\"version\": 1,\"version\":1"),
            " ".repeat(ProfileStyleCodec.MAX_DOCUMENT_CHARS + 1),
            valid.replace("font.ttf", "\uD800"))
        for ((index, document) in invalid.withIndex()) {
            assertNotEquals("invalid fixture $index must change the document", valid, document)
            assertTrue("invalid fixture $index", runCatching {
                validate(wire().also { profile(it).put("profileStyle", document) })
            }.isFailure)
        }
    }

    @Test fun legacyCopyIsComparedSemanticallyAndNotByWhitespaceOrKeyOrder() {
        val root = wire()
        val values = JSONObject(profile(root).getString("prefsSnapshot"))
        val reordered = JSONObject()
        values.keys().asSequence().toList().reversed().forEach { reordered.put(it, values.get(it)) }
        profile(root).put("prefsSnapshot", reordered.toString(2))
        assertEquals(fixture().profiles.single().profile.profileStyle,
            validate(root).profiles.single().profile.profileStyle)
    }

    @Test fun mismatchedOrInvalidLegacyCopiesCannotReplaceAuthoritativeStyles() {
        val badCopies = listOf("{}", "{\"GLOBAL_ICON_SCALE\":\"87\"}",
            "{\"unknown\":1}", "{\"GLOBAL_ICON_SCALE\":87,\"GLOBAL_ICON_SCALE\":87}",
            "{\"GLOBAL_ICON_SCALE\":87.5}", "{\"GLOBAL_ICON_SCALE\":2147483648}",
            "{\"TEXT_FONT\":\"\uD800\"}", "{} {}",
            " ".repeat(ProfileStyleCodec.MAX_DOCUMENT_CHARS + 10_000))
        for (legacy in badCopies) rejected(wire().also { profile(it).put("prefsSnapshot", legacy) })
    }

    @Test fun fullBackupRequiresActivePreferencesToMatchTheOwningStyle() {
        val root = wire()
        root.getJSONObject("prefs").getJSONObject(GlobalIconScaleKey.name).put("v", 88)
        rejected(root)
        val absent = wire()
        absent.getJSONObject("prefs").remove(GlobalIconScaleKey.name)
        rejected(absent)
    }

    @Test fun rawProfilePreferenceTypesAreCheckedBeforeJsonCoercion() {
        for ((key, entries) in listOf(
            GlobalIconScaleKey.name to listOf("{\"t\":\"int\",\"v\":\"87\"}",
                "{\"t\":\"int\",\"v\":87.5}", "{\"t\":\"long\",\"v\":87}"),
            GlobalColorizeKey.name to listOf("{\"t\":\"bool\",\"v\":\"false\"}",
                "{\"t\":\"int\",\"v\":0}"),
            TextFontKey.name to listOf("{\"t\":\"string\",\"v\":123}",
                "{\"t\":\"future\",\"v\":\"/missing/font.ttf\"}"))) {
            for (entry in entries) rejected(wire().also { it.getJSONObject("prefs").put(key, JSONObject(entry)) })
        }
    }

    @Test fun activeIdentityRequiresAnIntegerLongTagAndExistingProfile() {
        for (entry in listOf("{\"t\":\"int\",\"v\":1}", "{\"t\":\"long\",\"v\":\"1\"}",
            "{\"t\":\"long\",\"v\":1.5}", "{\"t\":\"long\",\"v\":true}",
            "{\"t\":\"long\",\"v\":2}")) {
            rejected(wire().also { it.getJSONObject("prefs").put(ActiveProfileIdKey.name, JSONObject(entry)) })
        }
        val root = wire().also { it.getJSONObject("prefs").remove(ActiveProfileIdKey.name) }
        assertEquals(1L, validate(root).profiles.single().profile.id)
    }

    @Test fun sharedProfileNeedsNoActiveProjectionAndKeepsItsDocument() {
        val data = fixture().copy(prefs = emptyMap())
        val decoded = BackupValidator.prepare(BackupCodec.decode(BackupCodec.encode(data, 4), 4),
            BackupManager.ImportKind.PROFILE, 4)
        assertEquals(data, decoded)
    }

    @Test fun multibyteStylesNearTheTypedLimitKeepTheirLegacyCopyReadable() {
        val empty = fixture(preferencesOf(TextFontKey to "")).profiles.single().profile
        val overhead = ProfileStyleCodec.encode(ProfileStylePreferences.capture(preferencesOf(TextFontKey to "")))
            .toByteArray(Charsets.UTF_8).size
        val font = "é".repeat((ProfileStyleCodec.MAX_DOCUMENT_BYTES - overhead) / 2)
        val values = preferencesOf(TextFontKey to font)
        val document = ProfileStyleCodec.encode(ProfileStylePreferences.capture(values))
        val data = fixture(values).let { original -> original.copy(profiles = listOf(original.profiles.single().copy(
            profile = empty.copy(profileStyle = document, prefsSnapshot = values.snapshotProfilePrefs())))) }
        assertEquals(data, validate(wire(data)))
        assertTrue(runCatching { ProfileStyleCodec.encode(ProfileStylePreferences.capture(
            preferencesOf(TextFontKey to font + "é"))) }.isFailure)
    }
}
