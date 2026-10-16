package dev.renkinProject.renkin.data.transfer

import android.app.Application
import androidx.datastore.preferences.core.emptyPreferences
import dev.renkinProject.renkin.data.*
import dev.renkinProject.renkin.data.watch.AppComponent
import dev.renkinProject.renkin.icon.creator.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.Reader

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class BackupInspectionReaderTest {
    private fun fixture() : BackupData {
        val prefs = emptyPreferences()
        val profile = Profile(id = 1, name = "Saved", description = "Description", packLabel = "Pack",
            prefsSnapshot = prefs.snapshotProfilePrefs(), hasUnbuiltChanges = true,
            hideMissingPackWarning = true, profileStyle = ProfileStyleCodec.encode(ProfileStylePreferences.capture(prefs)))
        val icon = DbApplication("app.one", "Main", true, true, "rendered\"\\\nΩ",
            calendarEnabled = true, calendarPrefix = "day_", calendarPackName = "calendar.pack",
            sourcePackName = "source.pack", sourceDrawableName = "alternate", isCustomIcon = true,
            isLegacyIcon = true, baseDrawable = "base\"\\\nΩ", baseIsAdaptiveIcon = true, baseIsXml = true,
            sourceUrl = "https://example.org/icon", isFallbackIcon = true,
            modifierRecipe = "invalid optional recipe", modifierInput = "invalid optional input")
        val app = AppComponent("app.one", "Main")
        val rule = BackupWatchRule(false, false, 1, null, listOf(app, app), listOf("source.pack", "source.pack"),
            listOf(BackupWatchState(app.packageName, app.activityName, "source.pack", 4, "name", "hash", 2)),
            listOf(BackupWatchSuggestion(app.packageName, app.activityName, 3,
                listOf(BackupWatchCandidate("source.pack", "alternate", "hash")))))
        return BackupData(listOf(BackupProfile(profile, listOf(icon), listOf(rule))),
            mapOf(ActiveProfileIdKey.name to BackupPref(BackupPref.LONG, 1L),
                "drawable" to BackupPref(BackupPref.STRING, "keep preference artwork-like text")),
            mapOf("source.pack" to "Source"))
    }

    private fun wire(format: Int = 4) = BackupCodec.encodeJson(fixture(), format)
    private fun profile(root: JSONObject) = root.getJSONArray("profiles").getJSONObject(0)
    private fun icon(root: JSONObject) = profile(root).getJSONArray("icons").getJSONObject(0)
    private fun withoutArtwork(data: BackupData) = data.copy(profiles = data.profiles.map { p ->
        p.copy(icons = p.icons.map { it.copy(drawable = "", baseDrawable = "") })
    })

    private suspend fun equivalent(json: String, format: Int, kind: BackupManager.ImportKind = BackupManager.ImportKind.BACKUP) {
        val full = runCatching { BackupValidator.prepare(readBackupData(json.reader(), format), kind, format) }
        val inspection = runCatching { BackupValidator.prepare(readBackupInspectionData(json.reader(), format), kind, format) }
        assertEquals("Inspection changed archive acceptance", full.isSuccess, inspection.isSuccess)
        if (full.isSuccess) assertEquals(withoutArtwork(full.getOrThrow()), inspection.getOrThrow())
    }

    @Test fun formatsOneThroughFourPreserveAllConsumedMetadataAndNormalization() = runBlocking {
        for (format in 1..4) {
            equivalent(wire(format).toString(), format)
            equivalent(wire(format).toString(), format, BackupManager.ImportKind.PROFILE)
        }
    }

    private fun reversed(value: Any): Any = when (value) {
        is JSONObject -> JSONObject().also { result ->
            value.keys().asSequence().toList().reversed().forEach { result.put(it, reversed(value.get(it))) }
        }
        is JSONArray -> JSONArray().also { result ->
            repeat(value.length()) { result.put(reversed(value.get(it))) }
        }
        else -> value
    }

    @Test fun iconsBeforeProfileIdentityAndProfilesBeforePreferencesRemainSupported() = runBlocking {
        for (format in 1..4) equivalent(reversed(wire(format)).toString(), format)
    }

    @Test fun requiredFieldsLegacyDefaultsAndJsonCoercionsMatchTheContentDecoder() = runBlocking {
        for (format in 1..4) {
            for (field in listOf("drawable", "packageName", "activityName", "isAdaptiveIcon", "isXml",
                "baseDrawable", "isCustomIcon", "isLegacyIcon", "baseIsAdaptiveIcon", "baseIsXml")) {
                equivalent(wire(format).also { icon(it).remove(field) }.toString(), format)
                for (value in listOf(JSONObject.NULL, true, 7, "", "true", JSONObject(), JSONArray())) {
                    equivalent(wire(format).also { icon(it).put(field, value) }.toString(), format)
                }
            }
        }
    }

    @Test fun invalidIdentitiesPreferencesAndTypedStylesStillFail() = runBlocking {
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { profile(it).put("id", 0) },
            { profile(it).put("name", " ") },
            { icon(it).put("packageName", " ") },
            { profile(it).getJSONArray("icons").put(icon(it)) },
            { it.getJSONArray("profiles").put(profile(it)) },
            { it.getJSONObject("prefs").getJSONObject(ActiveProfileIdKey.name).put("v", 77) },
            { profile(it).remove("profileStyle") },
            { profile(it).put("profileStyle", "invalid") },
            { profile(it).put("prefsSnapshot", "{\"GLOBAL_ICON_SCALE\":77}") }
        )
        for (mutate in mutations) {
            val json = wire().also(mutate).toString()
            assertTrue(runCatching { BackupValidator.prepare(readBackupData(json.reader(), 4),
                BackupManager.ImportKind.BACKUP, 4) }.isFailure)
            equivalent(json, 4)
        }
        equivalent(wire(3).also { profile(it).put("profileStyle", fixture().profiles.single().profile.profileStyle) }.toString(), 3)
    }

    @Test fun unknownFieldsAreDiscardedWithoutSkippingStructuralValidation() = runBlocking {
        val valid = wire().also {
            it.put("unknown", JSONObject().put("drawable", "ignored".repeat(10_000)))
            profile(it).put("unknown", JSONArray().put("ignored".repeat(10_000)))
            icon(it).put("unknown", JSONObject().put("baseDrawable", "ignored".repeat(10_000)))
        }.toString()
        equivalent(valid, 4)
        val original = wire().toString()
        val malformed = listOf(
            original.replaceFirst("{", "{\"unknown\":{\"a\":1,\"\\u0061\":2},"),
            original.replaceFirst("{", "{\"unknown\":1e999,"),
            original.replaceFirst("{", "{\"unknown\":" + "[".repeat(66) + "0" + "]".repeat(66) + ","),
            original.replace("\"drawable\":", "\"unknown\":1,\"unknown\":2,\"drawable\":"),
            "$original {}"
        )
        for (json in malformed) {
            assertTrue(runCatching { readBackupInspectionData(json.reader(), 4) }.isFailure)
            equivalent(json, 4)
        }
    }

    @Test fun validOptionalModifierDocumentsAndInactiveFieldsStayCanonical() = runBlocking {
        val options = modifierInputTestOptions()
        val recipe = checkNotNull(StoredIconModifierRecipe.fromRenderedOptions(options))
        val input = checkNotNull(StoredIconModifierInput.encode(
            IconModifierInput(options, true, ModifierInputOrigin.EXISTING_ICON),
            StoredModifierArtwork("a".repeat(64), false, false)))
        for (format in 1..4) {
            val root = wire(format).also {
                icon(it).put("modifierRecipe", recipe.lines().reversed().joinToString("\n"))
                icon(it).put("modifierInput", JSONObject(input).toString(2))
            }
            equivalent(root.toString(), format)
            val inspected = readBackupInspectionData(root.toString().reader(), format).profiles.single().icons.single()
            assertEquals(recipe, inspected.modifierRecipe)
            assertEquals(input, inspected.modifierInput)
        }
    }

    @Test fun artworkObjectsStillReceiveDuplicateAndDepthChecks() = runBlocking {
        val original = wire().toString()
        val root = wire().also { icon(it).put("drawable", JSONObject().put("valid", true)) }
        equivalent(root.toString(), 4)
        val invalid = root.toString().replace("\"valid\":true", "\"valid\":true,\"valid\":false")
        assertTrue(runCatching { readBackupInspectionData(invalid.reader(), 4) }.isFailure)
        equivalent(invalid, 4)
        assertTrue(runCatching { readBackupInspectionData(original.dropLast(1).reader(), 4) }.isFailure)
    }

    @Test fun inspectionModelOwnsNoRenderedOrBasePayloadStrings() = runBlocking {
        val base = fixture()
        val rows = (0 until 40).map { index -> base.profiles.single().icons.single().copy(
            packageName = "app.$index", drawable = "rendered-$index".repeat(2_000), baseDrawable = "base-$index".repeat(2_000)) }
        val data = base.copy(profiles = listOf(base.profiles.single().copy(icons = rows)))
        val json = BackupCodec.encode(data, 4)
        val inspection = BackupValidator.prepare(readBackupInspectionData(json.reader(), 4), BackupManager.ImportKind.BACKUP, 4)
        assertEquals(40, inspection.profiles.single().icons.size)
        assertTrue(inspection.profiles.single().icons.all { it.drawable.isEmpty() && it.baseDrawable.isEmpty() })
        equivalent(json, 4)
    }

    @Test fun cancellationDuringLargeArtworkClosesTheReader() = runBlocking {
        var closed = false
        val json = wire().also { icon(it).put("drawable", "pixel".repeat(10_000)) }.toString()
        val task = launch {
            val owner = currentCoroutineContext().job
            val source = json.reader()
            var read = 0
            val input = object : Reader() {
                override fun read(buffer: CharArray, offset: Int, count: Int): Int {
                    val size = source.read(buffer, offset, count)
                    read += size.coerceAtLeast(0)
                    if (read > 2_000) owner.cancel()
                    return size
                }
                override fun close() { closed = true; source.close() }
            }
            readBackupInspectionData(input, 4)
            fail("Cancelled inspection completed")
        }
        task.join()
        assertTrue(task.isCancelled)
        assertTrue(closed)
    }
}
