package dev.renkinProject.renkin.data.editor

import android.app.Application
import dev.renkinProject.renkin.data.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.lang.reflect.Modifier
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class EditorDraftFingerprintsTest {
    private val row = DbApplication("pkg", "Act", false, false, "rendered", profileId = 7)
    private suspend fun fingerprint(row: DbApplication) = EditorDraftFingerprints.icon(row.profileId, row.packageName, row.activityName, row)
    private val profile get() = Profile(id = 7, name = "Profile", profileStyle = ProfileStyleCodec.encode(ProfileStyle()))

    private suspend fun rejected(block: suspend () -> Unit) {
        try { block(); fail("Invalid baseline accepted") } catch (_: IllegalArgumentException) { }
    }

    @Test fun everyPersistedFieldChangesTheIconHashAndSchemaCoverageIsComplete() = runBlocking {
        val changes = linkedMapOf(
            "profileId" to row.copy(profileId = 8), "packageName" to row.copy(packageName = "other"),
            "activityName" to row.copy(activityName = "Other"), "isAdaptiveIcon" to row.copy(isAdaptiveIcon = true),
            "isXml" to row.copy(isXml = true), "drawable" to row.copy(drawable = "other"),
            "calendarEnabled" to row.copy(calendarEnabled = true), "calendarPrefix" to row.copy(calendarPrefix = "day"),
            "calendarPackName" to row.copy(calendarPackName = "calendar"), "sourcePackName" to row.copy(sourcePackName = "pack"),
            "sourceDrawableName" to row.copy(sourceDrawableName = "reference"), "isCustomIcon" to row.copy(isCustomIcon = true),
            "isLegacyIcon" to row.copy(isLegacyIcon = true), "baseDrawable" to row.copy(baseDrawable = "base"),
            "baseIsAdaptiveIcon" to row.copy(baseIsAdaptiveIcon = true), "baseIsXml" to row.copy(baseIsXml = true),
            "sourceUrl" to row.copy(sourceUrl = "url"), "isFallbackIcon" to row.copy(isFallbackIcon = true),
            "modifierRecipe" to row.copy(modifierRecipe = "recipe"), "modifierInput" to row.copy(modifierInput = "input")
        )
        val fields = DbApplication::class.java.declaredFields.filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }.map { it.name }.toSet()
        assertEquals(fields, changes.keys)
        val original = fingerprint(row)
        assertTrue(original.matches(Regex("[a-f0-9]{64}")))
        for ((field, changed) in changes) assertNotEquals(field, original, fingerprint(changed))
        assertEquals(original, fingerprint(row.copy()))
    }

    @Test fun absentRowHasAStableVersionedDomainSeparatedEncoding() = runBlocking {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use {
            it.writeInt(0x524b4546); it.writeInt(1)
            for (text in listOf("icon")) { val bytes = text.toByteArray(); it.writeInt(bytes.size); it.write(bytes) }
            it.writeLong(7)
            for (text in listOf("pkg", "Act")) { val bytes = text.toByteArray(); it.writeInt(bytes.size); it.write(bytes) }
            it.writeBoolean(false)
        }
        val expected = MessageDigest.getInstance("SHA-256").digest(output.toByteArray()).joinToString("") { "%02x".format(it) }
        val absent = EditorDraftFingerprints.icon(7, "pkg", "Act", null)
        assertEquals(expected, absent)
        assertNotEquals(absent, fingerprint(row.copy(drawable = "")))
        assertNotEquals(absent, EditorDraftFingerprints.profile(profile))
        assertNotEquals(EditorDraftFingerprints.icon(7, "ab", "c", null), EditorDraftFingerprints.icon(7, "a", "bc", null))
    }

    @Test fun ownerMismatchAndInvalidComponentsFail() = runBlocking {
        rejected { EditorDraftFingerprints.icon(8, "pkg", "Act", row) }
        rejected { EditorDraftFingerprints.icon(7, "other", "Act", row) }
        rejected { EditorDraftFingerprints.icon(7, "pkg", "Other", row) }
        rejected { EditorDraftFingerprints.icon(0, "pkg", "Act", null) }
        rejected { EditorDraftFingerprints.icon(7, "", "Act", null) }
    }

    @Test fun profileUsesCanonicalTypedStyleAndExcludesPresentationAndLegacyProjection() = runBlocking {
        val original = EditorDraftFingerprints.profile(profile)
        assertEquals(original, EditorDraftFingerprints.profile(profile.copy(profileStyle = JSONObject(profile.profileStyle).toString(4))))
        assertEquals(original, EditorDraftFingerprints.profile(profile.copy(name = "Renamed", description = "Description",
            packLabel = "Label", prefsSnapshot = "invalid legacy", hasUnbuiltChanges = true, hideMissingPackWarning = true)))
        assertNotEquals(original, EditorDraftFingerprints.profile(profile.copy(id = 8)))
        val changed = ProfileStyleCodec.encode(ProfileStyle(source = ProfileSourceStyle(fontPath = "changed")))
        assertNotEquals(original, EditorDraftFingerprints.profile(profile.copy(profileStyle = changed)))
    }

    @Test fun missingInvalidAndUnconvertedStylesFailWithoutDefaultHash() = runBlocking {
        for (document in listOf("", "{}", "null", profile.profileStyle + "{}"))
            rejected { EditorDraftFingerprints.profile(profile.copy(profileStyle = document)) }
        rejected { EditorDraftFingerprints.profile(profile.copy(id = 0)) }
    }

    @Test fun recipeAndInputDocumentsAreComparedVerbatim() = runBlocking {
        val first = row.copy(modifierRecipe = "{\"a\":1}", modifierInput = "{\"b\":2}")
        assertNotEquals(fingerprint(first), fingerprint(first.copy(modifierRecipe = "{ \"a\":1}")))
        assertNotEquals(fingerprint(first), fingerprint(first.copy(modifierInput = "{ \"b\":2}")))
    }

    @Test fun unicodeChunkBoundariesAreStableAndLimitsUseUtf8Bytes() = runBlocking {
        val longText = "a".repeat(8191) + "😀" + "é".repeat(5000)
        // Standard UTF-8 encoding is independent of the production streaming byte counter.
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            fun text(value: String) { val bytes = value.toByteArray(Charsets.UTF_8); data.writeInt(bytes.size); data.write(bytes) }
            data.writeInt(0x524b4546); data.writeInt(1); text("icon"); data.writeLong(7); text("pkg"); text("Act")
            data.writeBoolean(true); data.writeBoolean(false); data.writeBoolean(false); text(longText)
            data.writeBoolean(false); repeat(4) { text("") }; data.writeBoolean(false); data.writeBoolean(false)
            text(""); data.writeBoolean(false); data.writeBoolean(false); text(""); data.writeBoolean(false); text(""); text("")
        }
        val expected = MessageDigest.getInstance("SHA-256").digest(output.toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals(expected, fingerprint(row.copy(drawable = longText)))
        val atLimit = row.copy(sourceUrl = "é".repeat(32768))
        assertTrue(fingerprint(atLimit).isNotEmpty())
        rejected { fingerprint(atLimit.copy(sourceUrl = atLimit.sourceUrl + "a")) }
        for (invalid in listOf("\uD800", "\uDC00", "x\uD800x")) rejected { fingerprint(row.copy(drawable = invalid)) }
        rejected { fingerprint(row.copy(modifierRecipe = "r".repeat(1024 * 1024 + 1))) }
        rejected { fingerprint(row.copy(modifierInput = "x".repeat(65537))) }
    }

    @Test fun cancelledOwnerCannotPublishAFingerprint() {
        val owner = Job().apply { cancel() }
        assertThrows(CancellationException::class.java) {
            runBlocking(owner) { fingerprint(row.copy(drawable = "x".repeat(1024 * 1024))) }
        }
    }
}
