package dev.renkinProject.renkin.data.transfer

import android.app.Application
import androidx.datastore.preferences.core.emptyPreferences
import dev.renkinProject.renkin.data.ActiveProfileIdKey
import dev.renkinProject.renkin.data.DbApplication
import dev.renkinProject.renkin.data.Profile
import dev.renkinProject.renkin.data.ProfileStyleCodec
import dev.renkinProject.renkin.data.ProfileStylePreferences
import dev.renkinProject.renkin.data.snapshotProfilePrefs
import dev.renkinProject.renkin.data.watch.AppComponent
import dev.renkinProject.renkin.icon.creator.*
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class StagedBackupDataTest {
    @get:Rule val temporary = TemporaryFolder()

    @Before fun storageSpace() {
        ShadowStatFs.registerStats(temporary.root, 1_000_000, 1_000_000, 1_000_000)
    }

    private fun fixture(): BackupData {
        val prefs = emptyPreferences()
        val profile = Profile(id = 1, name = "Saved", description = "Description", packLabel = "Pack",
            prefsSnapshot = prefs.snapshotProfilePrefs(), profileStyle = ProfileStyleCodec.encode(ProfileStylePreferences.capture(prefs)))
        val icon = DbApplication("app.one", "Main", true, true, "rendered\"\\\nΩ",
            calendarEnabled = true, calendarPrefix = "day_", calendarPackName = "calendar.pack",
            sourcePackName = "source.pack", sourceDrawableName = "alternate", isCustomIcon = true,
            isLegacyIcon = true, baseDrawable = "base\"\\\nΩ", baseIsAdaptiveIcon = true, baseIsXml = true,
            sourceUrl = "https://example.org/icon", isFallbackIcon = true,
            modifierRecipe = "invalid optional recipe", modifierInput = "invalid optional input")
        val app = AppComponent(icon.packageName, icon.activityName)
        val rule = BackupWatchRule(false, false, 1, null, listOf(app, app), listOf("source.pack", "source.pack"),
            listOf(BackupWatchState(app.packageName, app.activityName, "source.pack", 4, "old", "old", 2),
                BackupWatchState(app.packageName, app.activityName, "source.pack", 4, "new", "new", 3)),
            listOf(BackupWatchSuggestion(app.packageName, app.activityName, 4,
                listOf(BackupWatchCandidate("source.pack", "alternate", "hash"), BackupWatchCandidate("", "", "")))))
        return BackupData(listOf(BackupProfile(profile, listOf(icon), listOf(rule, rule.copy(completed = true, suggestions = emptyList())))),
            mapOf(ActiveProfileIdKey.name to BackupPref(BackupPref.LONG, 1L)))
    }

    private fun wire(format: Int = 4) = BackupCodec.encodeJson(fixture(), format)
    private fun profile(root: JSONObject) = root.getJSONArray("profiles").getJSONObject(0)
    private fun icon(root: JSONObject) = profile(root).getJSONArray("icons").getJSONObject(0)
    private fun metadata(data: BackupData) = data.copy(profiles = data.profiles.map { it.copy(icons = emptyList()) })

    private suspend fun equivalent(json: String, format: Int, kind: BackupManager.ImportKind = BackupManager.ImportKind.BACKUP) {
        val source = temporary.newFile().apply { writeText(json) }
        val root = temporary.newFolder()
        val full = runCatching { BackupValidator.prepare(readBackupData(json.reader(), format), kind, format) }
        val staged = runCatching { StagedBackupData.read(root, source, format, kind) }
        assertEquals("Staging changed archive acceptance", full.isSuccess, staged.isSuccess)
        staged.getOrNull()?.use { owned ->
            val data = full.getOrThrow()
            assertEquals(metadata(data), owned.metadata)
            assertEquals(data.profiles.sumOf { it.icons.size }, owned.iconCount)
            for (p in data.profiles) {
                val rows = mutableListOf<DbApplication>()
                owned.visit(p.profile.id) { rows += it }
                assertEquals(p.icons, rows)
            }
            assertTrue(BackupStaging.isActive(owned.directory))
        }
        assertTrue(root.listFiles().isNullOrEmpty())
    }

    private fun reversed(value: Any): Any = when (value) {
        is JSONObject -> JSONObject().also { result ->
            value.keys().asSequence().toList().reversed().forEach { result.put(it, reversed(value.get(it))) }
        }
        is JSONArray -> JSONArray().also { result -> repeat(value.length()) { result.put(reversed(value.get(it))) } }
        else -> value
    }

    @Test fun formatsFieldOrderMultipleProfilesAndEmptyProfilesMatchCurrentDecoder() = runBlocking {
        for (format in 1..4) {
            equivalent(wire(format).toString(), format, BackupManager.ImportKind.PROFILE)
            val data = fixture()
            val second = data.profiles.single().let { it.copy(profile = it.profile.copy(id = 7, name = "Second"),
                icons = it.icons.map { row -> row.copy(profileId = 7) }) }
            val third = second.copy(profile = second.profile.copy(id = 9, name = "Empty"), icons = emptyList())
            val json = BackupCodec.encodeJson(data.copy(profiles = data.profiles + second + third), format)
            equivalent(json.toString(), format)
            equivalent(reversed(json).toString(), format)
        }
    }

    @Test fun requiredFieldsCoercionsAndLegacyDefaultsHaveTheSameAcceptance() = runBlocking {
        for (format in 1..4) for (field in listOf("drawable", "packageName", "activityName", "isAdaptiveIcon", "isXml",
            "baseDrawable", "baseIsAdaptiveIcon", "baseIsXml", "isCustomIcon", "isLegacyIcon")) {
            equivalent(wire(format).also { icon(it).remove(field) }.toString(), format)
            for (value in listOf(JSONObject.NULL, true, 7, "", "true", JSONObject(), JSONArray())) {
                equivalent(wire(format).also { icon(it).put(field, value) }.toString(), format)
            }
        }
    }

    @Test fun duplicateIdentityAndLateMetadataErrorsReleaseEveryStagedRow() = runBlocking {
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { profile(it).put("id", 0) }, { profile(it).put("name", " ") },
            { profile(it).getJSONArray("icons").put(icon(it)) },
            { it.getJSONArray("profiles").put(profile(it)) },
            { it.getJSONObject("prefs").getJSONObject(ActiveProfileIdKey.name).put("v", 77) },
            { profile(it).remove("profileStyle") }, { profile(it).put("profileStyle", "invalid") },
            { it.put("colorPresets", JSONArray().put(JSONObject().put("name", "Invalid").put("style", "invalid"))) },
            { it.put("modifierPresets", JSONArray().put(JSONObject().put("name", "Invalid").put("payload", "invalid").put("schemaVersion", 1))) },
            { icon(it).put("activityName", " ") }, { profile(it).getJSONArray("icons").put(JSONObject()) }
        )
        for (mutate in mutations) equivalent(reversed(wire().also(mutate)).toString(), 4)
        equivalent(wire().toString(), 5)
    }

    @Test fun unknownValuesDuplicatesDepthNumbersAndTrailingInputRemainStrict() = runBlocking {
        val json = wire().toString()
        equivalent(wire().also { icon(it).put("unknown", JSONObject().put("drawable", "ignored".repeat(1000))) }.toString(), 4)
        for (bad in listOf(
            json.replaceFirst("{", "{\"unknown\":{\"a\":1,\"\\u0061\":2},"),
            json.replaceFirst("{", "{\"unknown\":1e999,"),
            json.replaceFirst("{", "{\"unknown\":" + "[".repeat(66) + "0" + "]".repeat(66) + ","),
            json.replace("\"drawable\":", "\"unknown\":1,\"unknown\":2,\"drawable\":"),
            "$json {}", json.dropLast(1)
        )) equivalent(bad, 4)
    }

    @Test fun oversizedArtworkReplaysExactlyWithoutArtworkInMetadata() = runBlocking {
        val data = fixture()
        val row = data.profiles.single().icons.single().copy(drawable = "escaped\"\\\nΩ".repeat(210_000),
            baseDrawable = "base\n\t".repeat(230_000))
        equivalent(BackupCodec.encode(data.copy(profiles = listOf(data.profiles.single().copy(icons = listOf(row)))), 4), 4)
    }

    @Test fun optionalInputsAreRetainedOnlyWithVerifiedFilesAndExcludedFromSharing() = runBlocking {
        val bytes = "private optional source".toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val input = checkNotNull(StoredIconModifierInput.encode(
            IconModifierInput(modifierInputTestOptions(), true, ModifierInputOrigin.EXISTING_ICON),
            StoredModifierArtwork(hash, false, false)))
        val recipe = checkNotNull(StoredIconModifierRecipe.fromRenderedOptions(modifierInputTestOptions()))
        val json = wire().also {
            icon(it).put("modifierInput", JSONObject(input).toString(2))
            icon(it).put("modifierRecipe", recipe.lines().reversed().joinToString("\n"))
        }
        val source = temporary.newFile().apply { writeText(json.toString()) }
        val artwork = temporary.newFile().apply { writeBytes(bytes) }
        val name = "$hash.input"
        for (kind in BackupManager.ImportKind.entries) for (available in listOf(true, false)) {
            StagedBackupData.read(temporary.newFolder(), source, 4, kind,
                if (available) mapOf(name to artwork) else emptyMap()).use { staged ->
                staged.visit(1) { row ->
                    assertEquals(if (kind == BackupManager.ImportKind.BACKUP && available) input else "", row.modifierInput)
                    assertEquals(recipe, row.modifierRecipe)
                }
                assertEquals(if (kind == BackupManager.ImportKind.BACKUP && available) setOf(name) else emptySet<String>(),
                    staged.modifierInputs.keys)
            }
        }
        artwork.writeText("corrupted")
        StagedBackupData.read(temporary.newFolder(), source, 4, BackupManager.ImportKind.BACKUP, mapOf(name to artwork)).use { staged ->
            staged.visit(1) { assertEquals("", it.modifierInput) }
            assertTrue(staged.modifierInputs.isEmpty())
        }
        assertTrue(artwork.exists())
    }

    @Test fun cancelledReadAndConsumerFailureCleanUpWithoutDeletingSource() = runBlocking {
        val source = temporary.newFile().apply { writeText(wire().toString()) }
        val root = temporary.newFolder()
        val cancelled = launch {
            currentCoroutineContext().job.cancel()
            StagedBackupData.read(root, source, 4, BackupManager.ImportKind.BACKUP)
            fail("Cancelled staging completed")
        }
        cancelled.join()
        assertTrue(cancelled.isCancelled)
        assertTrue(root.listFiles().isNullOrEmpty())
        val staged = StagedBackupData.read(root, source, 4, BackupManager.ImportKind.BACKUP)
        staged.use { assertTrue(runCatching { it.visit(1) { throw IllegalStateException("Consumer failure") } }.isFailure) }
        assertFalse(staged.directory.exists())
        assertTrue(runCatching { staged.visit(1) {} }.isFailure)
        assertTrue(source.isFile)
    }

    @Test fun truncatedStagedRowsAreRejectedAndStartupCollectsOnlyAbandonedScopes() = runBlocking {
        val source = temporary.newFile().apply { writeText(wire().toString()) }
        val files = temporary.newFolder()
        val root = File(files, "backup-imports")
        StagedBackupData.read(root, source, 4, BackupManager.ImportKind.BACKUP).use { staged ->
            val file = staged.directory.listFiles()!!.single()
            file.writeText(file.readText().dropLast(1))
            assertTrue(runCatching { staged.visit(1) {} }.isFailure)
            BackupStaging.cleanAfterProcessStart(files)
            assertTrue(staged.directory.exists())
        }
        val abandoned = File(root, "backup-import-rows-abandoned").apply { mkdirs() }
        File(abandoned, "partial.json").writeText("[")
        BackupStaging.cleanAfterProcessStart(files)
        assertFalse(abandoned.exists())
        assertTrue(source.exists())
    }

    @Test fun lowStorageFailsBeforePublishingAHandleAndReleasesTheScope() = runBlocking {
        val source = temporary.newFile().apply { writeText(wire().toString()) }
        val root = temporary.newFolder()
        ShadowStatFs.registerStats(root, 1_000_000, 0, 0)
        val failure = runCatching { StagedBackupData.read(root, source, 4, BackupManager.ImportKind.BACKUP) }
        assertTrue(failure.exceptionOrNull() is BackupSpaceException)
        assertTrue(root.listFiles().isNullOrEmpty())
        assertTrue(source.isFile)
    }
}
